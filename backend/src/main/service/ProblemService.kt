package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.LabProblemInfo
import data.ProblemContent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.io.IOException
import java.util.Base64

@Service
class ProblemService(
    private val courseRepository: CourseRepository,
    private val courseAccess: CourseAccessService,
) {
    private val log = LoggerFactory.getLogger(ProblemService::class.java)

    /**
     * Lists all problems this email may work on right now: the currently active labs for a
     * student, every lab of the course for its TA (see CourseAccessService).
     * Problems are read from the database (Course -> Labs -> Problems).
     */
    fun listProblemsForStudent(email: String): List<LabProblemInfo> {
        val courses = courseAccess.coursesFor(email)
        if (courses.isEmpty()) {
            log.warn("No courses found for student: {}", email)
            return emptyList()
        }

        val problems = mutableListOf<LabProblemInfo>()

        for (course in courses) {
            log.info("Processing course {} for student {}", course.id, email)

            val visibleLabs = courseAccess.visibleLabs(course, email)

            if (visibleLabs.isEmpty()) {
                log.warn("No active labs found for course {} (student {})", course.id, email)
                continue
            }

            for (lab in visibleLabs) {
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

        if (!courseAccess.isMember(course, email)) {
            log.warn("Student {} not enrolled in course {}", email, courseId)
            return null
        }

        if (course.section != section) {
            log.warn("Section mismatch for course {}", courseId)
            return null
        }

        // Verify the problem exists in the lab and the caller may use the lab right now
        val lab = course.labs.find { it.labNumber == labNumber }
        if (lab == null || lab.problems.none { it.name == slug }) {
            log.warn("Problem {} not found in lab {} for course {}", slug, labNumber, courseId)
            return null
        }
        if (!courseAccess.canAccessLab(course, lab, email)) {
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

        return ProblemContent(html = embedImages(rawHtml, basePath, slug), css = css)
    }

    /**
     * Inlines each relative image as a `data:` URI. The description is shown in an iframe whose own
     * requests carry no Bearer token, so a separate image request would be refused; inlined, the image
     * travels with the (already authorised) description. An image that can't be inlined is blanked
     * (`src=""`: no request, the alt text shows) and logged, and the description still loads.
     */
    private fun embedImages(html: String, problemDir: File, slug: String): String =
        SRC_ATTRIBUTE.replace(html) { match ->
            val src = match.groupValues[1]
            if (ABSOLUTE_SRC_PREFIXES.any { src.startsWith(it) }) match.value
            else """src="${imageDataUri(problemDir, slug, src).orEmpty()}""""
        }

    /** The image at [src] (relative to [problemDir]) as a data URI, or null - with a log line - if it can't be. */
    private fun imageDataUri(problemDir: File, slug: String, src: String): String? {
        val reason = try {
            val file = File(problemDir, src)
            val mimeType = IMAGE_MIME_TYPES[file.extension.lowercase()]
            when {
                !file.canonicalPath.startsWith(problemDir.canonicalPath + File.separator) -> "outside the problem directory"
                !file.isFile -> "not found"
                mimeType == null -> "not an image"
                file.length() > MAX_EMBEDDED_IMAGE_BYTES -> "larger than $MAX_EMBEDDED_IMAGE_BYTES bytes"
                else -> return "data:$mimeType;base64," + Base64.getEncoder().encodeToString(file.readBytes())
            }
        } catch (e: IOException) {
            "unreadable: ${e.message}"
        }
        log.warn("[problem-images] {}: not embedding {} ({})", slug, src, reason)
        return null
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

        if (!courseAccess.isMember(course, email)) {
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
        if (!courseAccess.canAccessLab(course, lab, email)) {
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

    companion object {
        private val SRC_ATTRIBUTE = Regex("""src=["']([^"']+)["']""")
        private val ABSOLUTE_SRC_PREFIXES = listOf("http://", "https://", "/", "data:")
        private val IMAGE_MIME_TYPES = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "svg" to "image/svg+xml", "webp" to "image/webp"
        )
        /** Bigger images are not inlined: they would bloat every description response. */
        private const val MAX_EMBEDDED_IMAGE_BYTES = 2_000_000L
    }
}
