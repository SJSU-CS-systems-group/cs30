package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.LabProblemInfo
import data.ProblemContent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class ProblemService(
    private val courseRepository: CourseRepository,
    @Value("\${git.server.ssh-host:}") private val sshHost: String,
    @Value("\${git.server.ssh-user:git}") private val sshUser: String,
) {
    private val log = LoggerFactory.getLogger(ProblemService::class.java)

    // Cache for problem lists: key = "email", value = (timestamp, problems)
    private val problemCache = mutableMapOf<String, Pair<Long, List<LabProblemInfo>>>()
    // Cache for content: key = "path", value = (timestamp, content)
    private val contentCache = mutableMapOf<String, Pair<Long, ProblemContent>>()
    private val cacheTtlMs = 5 * 60 * 1000L // 5 minutes

    // ---- SSH helper methods ----

    private fun sshFindAllProblems(repoPath: String): List<String> {
        if (sshHost.isBlank()) return emptyList()
        val command = "find '$repoPath' -path '*/section_*/lab_*/*' -name 'index.html' -type f 2>/dev/null | sed 's|^$repoPath/||' | sed 's|/index.html$||'"
        val process = ProcessBuilder("ssh", "$sshUser@$sshHost", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    private fun sshReadFile(path: String): String? {
        if (sshHost.isBlank()) return null
        val process = ProcessBuilder("ssh", "$sshUser@$sshHost", "cat '$path'")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return if (exitCode == 0) output else null
    }

    /**
     * Lists all problems for a student's currently active labs.
     */
    fun listProblemsForStudent(email: String): List<LabProblemInfo> {
        val cached = problemCache[email]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            log.info("Returning cached problems for {}", email)
            return cached.second
        }

        val courses = courseRepository.findByStudentEmail(email)
        if (courses.isEmpty()) {
            log.warn("No courses found for student: {}", email)
            return emptyList()
        }

        val problems = mutableListOf<LabProblemInfo>()
        val now = LocalDateTime.now()

        for (course in courses) {
            val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: continue
            log.info("Processing course {} for student {} with repo {}", course.id, email, repoPath)

            val activeLabNumbers = course.labs
                .filter { lab -> now.isAfter(lab.startDateTime) && now.isBefore(lab.endDateTime) }
                .map { it.labNumber }.toSet()
            if (activeLabNumbers.isEmpty()) {
                log.warn("No labs found for course {} (student {})", course.id, email)
                continue
            }

            val allProblemPaths = sshFindAllProblems(repoPath)
            log.info("Found {} problem paths in repo", allProblemPaths.size)

            // Parse paths like "section_1/lab_1/breakmaze" and filter by this course's section and active labs
            val pathRegex = Regex("section_(\\d+)/lab_(\\d+)/(.+)")
            for (path in allProblemPaths) {
                val match = pathRegex.matchEntire(path) ?: continue
                val section = match.groupValues[1].toIntOrNull() ?: continue
                val labNumber = match.groupValues[2].toIntOrNull() ?: continue
                val slug = match.groupValues[3]

                // Filter: must match this course's section and be an active lab
                if (section != course.section) continue
                if (labNumber !in activeLabNumbers) continue

                problems.add(
                    LabProblemInfo(
                        courseId = course.id,
                        courseCode = course.code,
                        section = section,
                        labNumber = labNumber,
                        slug = slug,
                        title = formatTitle(slug)
                    )
                )
            }
        }

        val result = problems.sortedWith(compareBy({ it.section }, { it.labNumber }, { it.title }))
        problemCache[email] = System.currentTimeMillis() to result
        log.info("Cached {} problems for {}", result.size, email)
        return result
    }

    /**
     * Gets HTML and CSS content for a specific problem.
     */
    fun getProblemContent(
        email: String,
        courseId: String,
        section: Int,
        labNumber: Int,
        slug: String
    ): ProblemContent? {
        val course = courseRepository.findById(courseId).orElse(null) ?: return null

        if (email !in course.students) {
            log.warn("Student {} not enrolled in course {}", email, courseId)
            return null
        }

        if (course.section != section) {
            log.warn("Section mismatch for course {}", courseId)
            return null
        }

        val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: return null
        val basePath = "$repoPath/section_$section/lab_$labNumber/$slug"

        // Check cache
        val cacheKey = basePath
        val cached = contentCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            log.info("Returning cached content for {}", slug)
            return cached.second
        }

        val html = sshReadFile("$basePath/index.html") ?: return null
        val css = sshReadFile("$basePath/problem.css") ?: ""

        val content = ProblemContent(html = html, css = css)
        contentCache[cacheKey] = System.currentTimeMillis() to content
        log.info("Cached content for {} (html: {} bytes, css: {} bytes)", slug, html.length, css.length)
        return content
    }

    private fun formatTitle(slug: String): String = slug
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .split("-").joinToString(" ") { word ->
            word.split("_").joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercase() }
            }
        }
}
