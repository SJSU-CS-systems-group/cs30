package com.cs30.server.service

import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import data.LabProblemInfo
import data.ProblemContent
import data.TaProblemDetail
import data.TaTestCase
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

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
        val (rawHtml, css) = readProblemFiles(File(repoPath, slug)) ?: return null

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
     * Gets a problem's description and every test case (sample and secret) for the TA dashboard.
     * Performs no access checks: the caller must already have verified the TA owns a lab containing
     * this problem. Returns null if the problem repo or description is missing.
     */
    fun getProblemDetailForTa(course: Course, slug: String): TaProblemDetail? {
        val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: return null
        val problemDir = File(repoPath, slug)
        val (html, css) = readProblemFiles(problemDir) ?: return null
        return TaProblemDetail(slug = slug, html = html, css = css, testCases = readTestCases(problemDir))
    }

    /**
     * Total size of a problem's sample and secret test files, from file sizes alone (nothing is
     * read), so the TA dashboard can warn before loading a large problem. 0 if the repo is unset.
     */
    fun testDataBytes(course: Course, slug: String): Long {
        val repoPath = course.problemGitRepo.takeIf { it.isNotBlank() } ?: return 0
        val problemDir = File(repoPath, slug)
        return testFiles(problemDir).sumOf { it.length() }
    }

    /** Reads the problem2html output (index.html + problem.css) from a problem directory. */
    private fun readProblemFiles(problemDir: File): ProblemContent? {
        val htmlFile = File(problemDir, "index.html")
        val cssFile = File(problemDir, "problem.css")

        if (!htmlFile.exists()) {
            log.warn("Problem HTML file not found: {}", htmlFile.absolutePath)
            return null
        }

        val html = try {
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
        return ProblemContent(html = html, css = css)
    }

    /**
     * Pairs every data/sample and data/secret `.in` file with its sibling `.ans` (from [testFiles],
     * so files resolving outside the problem directory are skipped). Names match the judge's case
     * names (e.g. "secret/pascalmagic-01"); sorted, which puts sample before secret.
     */
    private fun readTestCases(problemDir: File): List<TaTestCase> {
        val dataDir = File(problemDir, "data")
        val files = testFiles(problemDir).toList()
        val ansByPath = files.filter { it.extension == "ans" }.associateBy { it.path }
        return files.filter { it.extension == "in" }
            .map { inFile ->
                val name = inFile.relativeTo(dataDir).invariantSeparatorsPath.removeSuffix(".in")
                TaTestCase(
                    name = name,
                    hidden = name.startsWith("secret/"),
                    input = inFile.readText(),
                    expected = ansByPath[inFile.path.removeSuffix(".in") + ".ans"]?.readText().orEmpty()
                )
            }
            .sortedBy { it.name }
    }

    /** Every `.in`/`.ans` file under data/sample and data/secret that stays inside the problem directory. */
    private fun testFiles(problemDir: File): Sequence<File> {
        val root = problemDir.canonicalPath + File.separator
        return TEST_GROUPS.asSequence().flatMap { group ->
            File(problemDir, "data/$group").walkTopDown()
                .filter { it.isFile && it.extension in TEST_FILE_EXTENSIONS && it.canonicalPath.startsWith(root) }
        }
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
        /** Test data folders under a problem's data/ directory: sample is public, secret is hidden. */
        private val TEST_GROUPS = listOf("sample", "secret")
        private val TEST_FILE_EXTENSIONS = setOf("in", "ans")
    }
}
