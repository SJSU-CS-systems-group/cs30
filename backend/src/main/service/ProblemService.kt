package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.LabProblemInfo
import data.ProblemContent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

@Service
class ProblemService(
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(ProblemService::class.java)

    /**
     * Lists all problems for a student's currently active labs.
     * Problems are read from the database (Course -> Labs -> Problems).
     */
    fun listProblemsForStudent(email: String): List<LabProblemInfo> {
        val courses = courseRepository.findByStudentEmail(email)
        if (courses.isEmpty()) {
            log.warn("No courses found for student: {}", email)
            return emptyList()
        }

        val problems = mutableListOf<LabProblemInfo>()

        for (course in courses) {
            log.info("Processing course {} for student {}", course.id, email)

            // Get active labs and their problems from the database
            val activeLabs = course.labs.filter { it.isActive }

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
                            title = formatTitle(problem.name),
                            language = problem.language.ifBlank { course.language },
                            note = problem.note
                        )
                    )
                }
            }
        }

        return problems.sortedWith(compareBy({ it.section }, { it.labNumber }, { it.title }))
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

        if (!courseRepository.existsByIdAndStudentsContaining(course.id, email)) {
            log.warn("Student {} not enrolled in course {}", email, courseId)
            return null
        }

        if (course.section != section) {
            log.warn("Section mismatch for course {}", courseId)
            return null
        }

        // Verify the problem exists in the lab and lab is active
        val lab = course.labs.find { it.labNumber == labNumber }
        if (lab == null || lab.problems.none { it.name == slug }) {
            log.warn("Problem {} not found in lab {} for course {}", slug, labNumber, courseId)
            return null
        }
        if (!lab.isActive) {
            log.warn("Lab {} is not active for course {}", labNumber, courseId)
            return null
        }

        val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: return null
        // Global flat structure: repoPath/problemName/
        val basePath = File(repoPath, slug)
        val htmlFile = File(basePath, "index.html")
        val cssFile = File(basePath, "problem.css")

        if (!htmlFile.exists()) {
            log.warn("Problem HTML file not found: {}", htmlFile.absolutePath)
            return null
        }

        val rawHtml = try {
            htmlFile.readText()
        } catch (e: java.io.IOException) {
            log.error("Failed to read HTML file {}: {}", htmlFile.absolutePath, e.message)
            return null
        }
        val css = try {
            cssFile.readText()
        } catch (e: java.io.IOException) {
            log.warn("Failed to read CSS file {}: {}", cssFile.absolutePath, e.message)
            ""
        }

        // Rewrite image src paths to use the asset endpoint
        val assetBaseUrl = "/api/problems/$courseId/section/$section/lab/$labNumber/$slug/assets/"
        val html = rawHtml.replace(Regex("""src=["']([^"']+)["']""")) { match ->
            val originalPath = match.groupValues[1]
            // Only rewrite relative paths (not absolute URLs)
            if (!originalPath.startsWith("http://") && !originalPath.startsWith("https://") && !originalPath.startsWith("/")) {
                """src="$assetBaseUrl$originalPath""""
            } else {
                match.value
            }
        }

        return ProblemContent(html = html, css = css)
    }

    /**
     * Gets an asset file for a specific problem (e.g., images in data/ folder).
     * Returns null if access denied or file doesn't exist.
     */
    fun getProblemAssetFile(
        email: String,
        courseId: String,
        section: Int,
        labNumber: Int,
        slug: String,
        assetPath: String
    ): File? {
        val course = courseRepository.findById(courseId).orElse(null) ?: return null

        if (!courseRepository.existsByIdAndStudentsContaining(course.id, email)) {
            log.warn("Student {} not enrolled in course {}", email, courseId)
            return null
        }

        if (course.section != section) {
            log.warn("Section mismatch for course {}", courseId)
            return null
        }

        val lab = course.labs.find { it.labNumber == labNumber }
        if (lab == null || lab.problems.none { it.name == slug }) {
            log.warn("Problem {} not found in lab {} for course {}", slug, labNumber, courseId)
            return null
        }
        if (!lab.isActive) {
            log.warn("Lab {} is not active for course {}", labNumber, courseId)
            return null
        }

        val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: return null
        val file = File(File(repoPath, slug), assetPath)

        // Security: ensure the resolved path is still within the problem directory
        val problemDir = File(repoPath, slug).canonicalPath
        if (!file.canonicalPath.startsWith(problemDir)) {
            log.warn("Path traversal attempt: {}", assetPath)
            return null
        }

        return if (file.exists() && file.isFile) file else null
    }

    private fun formatTitle(slug: String): String = slug
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .split("-").joinToString(" ") { word ->
            word.split("_").joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercase() }
            }
        }
}