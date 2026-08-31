package com.cs30.server.service

import com.cs30.server.dto.BestSubmission
import com.cs30.server.dto.CanvasLabPlan
import com.cs30.server.dto.CanvasProblemPlan
import com.cs30.server.dto.StudentBestSubmission
import com.cs30.server.repository.CourseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File

/**
 * Reads the cs30 side of a Canvas sync, on behalf of CanvasSyncController. Entities and the
 * student repo path never leave this class: the CLI that consumes the result runs with no
 * Hibernate session and, since it can run on another machine, no access to the repo either.
 */
@Service
open class CanvasSyncService(
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(CanvasSyncService::class.java)

    /** A lab plan together with where its submissions live. The path stays on the server. */
    private data class ResolvedLab(val plan: CanvasLabPlan, val studentGitRepo: String)

    /** Throws IllegalArgumentException with a printable message when the course or lab is missing. */
    @Transactional(readOnly = true)
    open fun labPlan(code: String, year: Int, semester: String, section: Int, labNumber: Int): CanvasLabPlan =
        resolve(code, year, semester, section, labNumber).plan

    /**
     * Every enrolled student's best submission for one problem of the lab, leaving out students who
     * have none. Throws IllegalArgumentException with a printable message when the course, lab, or
     * problem is missing. The problem is checked against the lab before any file is touched: the
     * name ends up in a path, and over HTTP it comes from the caller.
     */
    @Transactional(readOnly = true)
    open fun bestSubmissions(
        code: String,
        year: Int,
        semester: String,
        section: Int,
        labNumber: Int,
        problemName: String,
    ): List<StudentBestSubmission> {
        val resolved = resolve(code, year, semester, section, labNumber)
        require(resolved.plan.problems.any { it.name == problemName }) {
            "Problem '$problemName' is not in lab $labNumber of $code section $section. Problems: " +
                resolved.plan.problems.joinToString(", ") { it.name }.ifEmpty { "(none)" }
        }
        return resolved.plan.studentEmails.mapNotNull { email ->
            bestSubmission(resolved.studentGitRepo, section, labNumber, problemName, email)
                ?.let { StudentBestSubmission(email, it) }
        }
    }

    /** Runs inside the caller's read-only transaction, which is what lets the lazy collections load. */
    private fun resolve(code: String, year: Int, semester: String, section: Int, labNumber: Int): ResolvedLab {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: throw IllegalArgumentException("Course not found: $code (Section $section, Semester $semester, Year $year)")
        val lab = course.labs.find { it.labNumber == labNumber }
            ?: throw IllegalArgumentException(
                "Lab $labNumber not found in $code section $section. Labs: " +
                    course.labs.map { it.labNumber }.sorted().joinToString(", ").ifEmpty { "(none)" }
            )

        // The TA may do labs in the student app, and may even be on the roster; their work is never graded.
        val students = course.students.filter { !it.equals(course.taEmail, ignoreCase = true) }.sorted()
        val problems = lab.problems.distinctBy { it.name }.sortedBy { it.name }
            .map { CanvasProblemPlan(it.name, it.note) }

        return ResolvedLab(
            plan = CanvasLabPlan(
                courseCode = course.code,
                section = section,
                labNumber = labNumber,
                startDateTime = lab.startDateTime,
                endDateTime = lab.endDateTime,
                problems = problems,
                studentEmails = students,
            ),
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
