package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.LabProblemInfo
import data.ProblemContent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime

@Service
class ProblemService(
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(ProblemService::class.java)

    // Cache for problem lists: key = "email", value = (timestamp, problems)
    private val problemCache = mutableMapOf<String, Pair<Long, List<LabProblemInfo>>>()
    // Cache for content: key = "path", value = (timestamp, content)
    private val contentCache = mutableMapOf<String, Pair<Long, ProblemContent>>()
    private val cacheTtlMs = 5 * 60 * 1000L // 5 minutes

    /**
     * Lists all problems for a student's currently active labs.
     * Problems are read from the database (Course -> Labs -> Problems).
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
            log.info("Processing course {} for student {}", course.id, email)

            // Get active labs and their problems from the database
            val activeLabs = course.labs
                .filter { lab -> now.isAfter(lab.startDateTime) && now.isBefore(lab.endDateTime) }

            if (activeLabs.isEmpty()) {
                log.warn("No active labs found for course {} (student {})", course.id, email)
                continue
            }

            for (lab in activeLabs) {
                for (problem in lab.problems) {
                    problems.add(
                        LabProblemInfo(
                            courseId = course.id,
                            courseCode = course.code,
                            section = course.section,
                            labNumber = lab.labNumber,
                            slug = problem.name,
                            title = formatTitle(problem.name)
                        )
                    )
                }
            }
        }

        val result = problems.sortedWith(compareBy({ it.section }, { it.labNumber }, { it.title }))
        problemCache[email] = System.currentTimeMillis() to result
        log.info("Cached {} problems for {}", result.size, email)
        return result
    }

    /**
     * Gets HTML and CSS content for a specific problem.
     * Problems are stored in global repo with flat structure: repoPath/problemName/
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

        // Verify the problem exists in the lab
        val lab = course.labs.find { it.labNumber == labNumber }
        if (lab == null || lab.problems.none { it.name == slug }) {
            log.warn("Problem {} not found in lab {} for course {}", slug, labNumber, courseId)
            return null
        }

        val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: return null
        // Global flat structure: repoPath/problemName/
        val basePath = File(repoPath, slug)

        // Check cache
        val cacheKey = basePath.absolutePath
        val cached = contentCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < cacheTtlMs) {
            log.info("Returning cached content for {}", slug)
            return cached.second
        }

        val htmlFile = File(basePath, "index.html")
        val cssFile = File(basePath, "problem.css")

        if (!htmlFile.exists()) {
            log.warn("Problem HTML file not found: {}", htmlFile.absolutePath)
            return null
        }

        val html = htmlFile.readText()
        val css = if (cssFile.exists()) cssFile.readText() else ""

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