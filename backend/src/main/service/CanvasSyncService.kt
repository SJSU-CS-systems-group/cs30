package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.time.LocalDateTime

/** One problem in a lab, flattened for Canvas. */
data class CanvasProblemPlan(
    val name: String,
    val note: String?,
)

/**
 * One student's best submission for a problem. `submittedAt` is the timestamp from the submission
 * filename, kept as the stored yyyy-MM-dd'T'HH-mm-ss text so it compares correctly as a string.
 */
data class BestSubmission(
    val highestPassed: Int,
    val total: Int,
    val fileName: String,
    val code: String,
    val submittedAt: String,
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
        val problems = lab.problems.distinctBy { it.name }.sortedBy { it.name }
            .map { CanvasProblemPlan(it.name, it.note) }

        return CanvasLabPlan(
            courseCode = course.code,
            section = section,
            labNumber = labNumber,
            startDateTime = lab.startDateTime,
            endDateTime = lab.endDateTime,
            problems = problems,
            studentEmails = students,
            studentGitRepo = course.studentGitRepo,
        )
    }

    /**
     * One student's best submission, or null when they have not submitted or the recorded file is
     * gone. Not transactional: this only touches the filesystem.
     */
    fun bestSubmission(
        studentGitRepo: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        email: String,
    ): BestSubmission? {
        if (studentGitRepo.isBlank()) return null
        val metadataFile = File(
            studentGitRepo,
            "section_$section/lab_$labNumber/$problemName/$email/submissions/bestsubmission.json",
        )
        if (!metadataFile.isFile) return null
        val metadata = try {
            jacksonMapper.readValue(metadataFile, SubmissionMetadata::class.java)
        } catch (e: Exception) {
            log.warn("Could not read {}: {}", metadataFile.path, e.message)
            return null
        }
        // bestSubmissionPath is recorded relative to the repo root.
        val codeFile = File(studentGitRepo, metadata.bestSubmissionPath)
        if (!codeFile.isFile) {
            log.warn("bestsubmission.json points at a missing file: {}", codeFile.path)
            return null
        }
        val code = try {
            codeFile.readText()
        } catch (e: Exception) {
            log.warn("Could not read {}: {}", codeFile.path, e.message)
            return null
        }
        return BestSubmission(
            highestPassed = metadata.highestPassed,
            total = metadata.total,
            fileName = codeFile.name,
            code = code,
            submittedAt = submittedAtOf(codeFile),
        )
    }

    /**
     * Submissions are saved as submission-<yyyy-MM-dd'T'HH-mm-ss>.<ext>, so the name carries the
     * time. Falls back to the file's modified time in the same format if the name does not match.
     */
    private fun submittedAtOf(codeFile: File): String {
        val fromName = codeFile.nameWithoutExtension.removePrefix("submission-")
        if (fromName.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}"""))) return fromName
        return java.time.Instant.ofEpochMilli(codeFile.lastModified())
            .atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
    }

    private companion object {
        val jacksonMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
    }
}
