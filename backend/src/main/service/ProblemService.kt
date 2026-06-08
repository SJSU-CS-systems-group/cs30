package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.LabProblemInfo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ProblemService(
    private val courseRepository: CourseRepository,
    @Value("\${git.server.ssh-host:}") private val sshHost: String,
    @Value("\${git.server.ssh-user:git}") private val sshUser: String,
) {
    private val log = LoggerFactory.getLogger(ProblemService::class.java)

    // Cache for problem lists: key = "email", value = (timestamp, problems)
    private val problemCache = mutableMapOf<String, Pair<Long, List<LabProblemInfo>>>()
    // Cache for HTML/CSS content: key = "path", value = (timestamp, content)
    private val contentCache = mutableMapOf<String, Pair<Long, String>>()
    private val cacheTtlMs = 5 * 60 * 1000L // 5 minutes

    // ---- SSH helper methods ----

    /**
     * Finds all problems in a repo with a single SSH call.
     * Returns paths like: section_1/lab_1/problemSlug
     */
    private fun sshFindAllProblems(repoPath: String): List<String> {
        if (sshHost.isBlank()) return emptyList()
        // Find all index.html files and extract the problem path
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
     * Looks up the student's enrolled courses, filters to active labs,
     * and returns problems from Section_X/Lab_X/ directories.
     * Results are cached for 5 minutes to reduce SSH overhead.
     */
    fun listProblemsForStudent(email: String): List<LabProblemInfo> {
        // Check cache first
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

        for (course in courses) {
            val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: continue
            log.warn("Processing course {} for student {} with repo {}", course.id, email, repoPath)

            // Get active lab numbers for this course
            val activeLabNumbers = course.labs.map { it.labNumber }.toSet()
            if (activeLabNumbers.isEmpty()) {
                log.warn("No labs found for course {} (student {})", course.id, email)
                continue
            }

            // Single SSH call to find all problems in the repo
            log.info("Finding all problems in repo {} via single SSH call", repoPath)
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
            log.info("Found {} problems for course {} section {}", problems.size, course.code, course.section)
        }
        val result = problems.sortedWith(compareBy({ it.section }, { it.labNumber }, { it.title }))

        // Save to cache
        problemCache[email] = System.currentTimeMillis() to result
        log.info("Cached {} problems for {} (TTL: {}ms)", result.size, email, cacheTtlMs)

        if (result.isEmpty()) {
            log.warn("No active problems found for student: {}", email)
        }
        return result
    }

    /**
     * Gets the HTML content for a specific problem via SSH.
     * Verifies the student has access to this problem via their enrollment.
     * Results are cached for 5 minutes.
     */
    fun getProblemHtmlForStudent(
        email: String,
        courseId: String,
        section: Int,
        labNumber: Int,
        slug: String
    ): String? {
        val course = courseRepository.findById(courseId).orElse(null) ?: return null

        // Verify student is enrolled
        if (email !in course.students) {
            log.warn("Student {} not enrolled in course {}", email, courseId)
            return null
        }

        // Verify section matches
        if (course.section != section) {
            log.warn("Section mismatch for course {}: expected {}, got {}", courseId, course.section, section)
            return null
        }

        val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: return null
        val filePath = "$repoPath/section_$section/lab_$labNumber/$slug/index.html"

        // Check cache
        val cacheKey = "html:$filePath"
        val cached = contentCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            log.info("Returning cached HTML for {}", slug)
            return cached.second
        }

        val content = sshReadFile(filePath) ?: return null
        contentCache[cacheKey] = System.currentTimeMillis() to content
        log.info("Cached HTML for {} ({} bytes)", slug, content.length)
        return content
    }

    /**
     * Gets the CSS for a specific problem via SSH.
     * Results are cached for 5 minutes.
     */
    fun getProblemCssForStudent(
        email: String,
        courseId: String,
        section: Int,
        labNumber: Int,
        slug: String
    ): String? {
        val course = courseRepository.findById(courseId).orElse(null) ?: return null

        // Verify student is enrolled
        if (email !in course.students) {
            log.warn("Student {} not enrolled in course {}", email, courseId)
            return null
        }

        val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: return null
        val filePath = "$repoPath/section_$section/lab_$labNumber/$slug/problem.css"

        // Check cache
        val cacheKey = "css:$filePath"
        val cached = contentCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            log.info("Returning cached CSS for {}", slug)
            return cached.second
        }

        val content = sshReadFile(filePath) ?: return null
        contentCache[cacheKey] = System.currentTimeMillis() to content
        log.info("Cached CSS for {} ({} bytes)", slug, content.length)
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
