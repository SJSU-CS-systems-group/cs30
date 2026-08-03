package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.time.LocalDateTime

/**
 * One problem in a lab, flattened for Canvas. `pointsPossible` is the judge's test-case count, or
 * null when it could not be determined (no submissions yet and no readable package).
 */
data class CanvasProblemPlan(
    val name: String,
    val note: String?,
    val pointsPossible: Int?,
    val pointsSource: String,
)

/**
 * Everything the Canvas commands need about one lab, read out of the DB in a single transaction and
 * returned as plain data. The CLI runs with no open Hibernate session, so entities must not escape.
 */
data class CanvasLabPlan(
    val courseCode: String,
    val section: Int,
    val labNumber: Int,
    val startDateTime: LocalDateTime,
    val endDateTime: LocalDateTime,
    val problems: List<CanvasProblemPlan>,
    val studentEmails: List<String>,
    val studentGitRepo: String,
    val problemGitRepo: String,
)

/**
 * Reads the cs30 side of a Canvas sync. Entities never escape: the CLI has no Open-Session-In-View,
 * so touching course.students or lab.problems after the transaction would fail to initialize.
 */
@Service
open class CanvasSyncService(
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(CanvasSyncService::class.java)

    /** Throws IllegalArgumentException with a printable message when the course or lab is missing. */
    @Transactional(readOnly = true)
    open fun labPlan(code: String, year: Int, semester: String, section: Int, labNumber: Int): CanvasLabPlan {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: throw IllegalArgumentException("Course not found: $code (Section $section, Semester $semester, Year $year)")
        val lab = course.labs.find { it.labNumber == labNumber }
            ?: throw IllegalArgumentException(
                "Lab $labNumber not found in $code section $section. Labs: " +
                    course.labs.map { it.labNumber }.sorted().joinToString(", ").ifEmpty { "(none)" }
            )

        val students = course.students.sorted()
        val problems = lab.problems.distinctBy { it.name }.sortedBy { it.name }.map { problem ->
            val (points, source) = resolvePoints(
                studentGitRepo = course.studentGitRepo,
                problemGitRepo = course.problemGitRepo,
                section = section,
                labNumber = labNumber,
                problemName = problem.name,
                studentEmails = students,
            )
            CanvasProblemPlan(problem.name, problem.note, points, source)
        }

        return CanvasLabPlan(
            courseCode = course.code,
            section = section,
            labNumber = labNumber,
            startDateTime = lab.startDateTime,
            endDateTime = lab.endDateTime,
            problems = problems,
            studentEmails = students,
            studentGitRepo = course.studentGitRepo,
            problemGitRepo = course.problemGitRepo,
        )
    }

    /**
     * The judge owns the test-case total but only records it per submission, so read the first
     * readable one and fall back to counting the package's cases when nobody has submitted yet.
     */
    private fun resolvePoints(
        studentGitRepo: String,
        problemGitRepo: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        studentEmails: List<String>,
    ): Pair<Int?, String> {
        if (studentGitRepo.isNotBlank()) {
            for (email in studentEmails) {
                val total = readSubmissionTotal(studentGitRepo, section, labNumber, problemName, email)
                if (total != null && total > 0) return total to "submission"
            }
        }
        val counted = countTestCases(problemGitRepo, problemName)
        if (counted != null && counted > 0) return counted to "package"
        return null to "unknown"
    }

    /** The `total` field of one student's bestsubmission.json, or null if absent/unreadable. */
    private fun readSubmissionTotal(
        studentGitRepo: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        email: String,
    ): Int? {
        val file = File(
            studentGitRepo,
            "section_$section/lab_$labNumber/$problemName/$email/submissions/bestsubmission.json",
        )
        if (!file.isFile) return null
        return try {
            jacksonMapper.readValue(file, SubmissionMetadata::class.java).total
        } catch (e: Exception) {
            log.warn("Could not read {}: {}", file.path, e.message)
            null
        }
    }

    /** Count the .in files under the package's data/sample and data/secret, or null if not readable. */
    private fun countTestCases(problemGitRepo: String, problemName: String): Int? {
        if (problemGitRepo.isBlank()) return null
        val data = File(File(problemGitRepo, problemName), "data")
        if (!data.isDirectory) return null
        val count = listOf("sample", "secret").sumOf { sub ->
            File(data, sub).takeIf { it.isDirectory }
                ?.walkTopDown()
                ?.count { it.isFile && it.extension == "in" }
                ?: 0
        }
        return count.takeIf { it > 0 }
    }

    private companion object {
        val jacksonMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    }
}
