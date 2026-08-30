package com.cs30.server.service

import com.cs30.server.dto.BestSubmission
import com.cs30.server.dto.CanvasLabPlan
import com.cs30.server.dto.CanvasProblemPlan
import com.cs30.server.dto.CourseQuery
import com.cs30.server.dto.CourseRef
import com.cs30.server.dto.StudentBestSubmission
import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

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
     * The one course [query] refers to, searched among the courses the caller may see: every
     * course for the admin ([taEmail] null), only the sections that TA is assigned to otherwise,
     * so the listings a miss prints reveal nothing about other courses. [matchCourse] has the
     * rules. Throws IllegalArgumentException with a printable message when it fits none or several.
     */
    @Transactional(readOnly = true)
    open fun findCourse(query: CourseQuery, taEmail: String?): CourseRef {
        val candidates =
            if (taEmail == null) courseRepository.findAll() else courseRepository.findByTaEmail(taEmail)
        return matchCourse(candidates, query).let { CourseRef(it.code, it.year, it.semester, it.section) }
    }

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

/**
 * The one course [query] refers to. The code is matched as a case-insensitive substring, with an
 * exact code winning outright so "CS30" still resolves when "CS30A" exists. Year and section, when
 * given, must match exactly; the semester, when given, as a substring. Ambiguity is an error, never
 * a guess, and lists the candidates. Matching nothing lists the courses that have not ended yet,
 * since a sync is almost always for one of those.
 */
internal fun matchCourse(courses: List<Course>, query: CourseQuery): Course {
    val narrowed = courses.filter { course ->
        course.code.contains(query.code, ignoreCase = true) &&
            (query.year == null || course.year == query.year) &&
            (query.semester == null || course.semester.contains(query.semester, ignoreCase = true)) &&
            (query.section == null || course.section == query.section)
    }
    val matches = narrowed.filter { it.code.equals(query.code, ignoreCase = true) }.ifEmpty { narrowed }
    return when (matches.size) {
        1 -> matches.single()
        0 -> {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            throw IllegalArgumentException(
                "no cs30 course matches $query. Active courses:" +
                    courseListing(courses.filter { it.endDate.isAfter(now) })
            )
        }
        else -> throw IllegalArgumentException(
            "multiple cs30 courses match $query:" + courseListing(matches) +
                "\nNarrow it with --cs30-year, --cs30-semester or --cs30-section."
        )
    }
}

/** One course per line in the order the other course listings use, or "(none)". */
private fun courseListing(courses: List<Course>): String =
    if (courses.isEmpty()) " (none)"
    else courses.sortedWith(compareBy({ it.code }, { it.year }, { it.semester }, { it.section }))
        .joinToString("") { "\n  - ${CourseRef(it.code, it.year, it.semester, it.section).describe()}" }
