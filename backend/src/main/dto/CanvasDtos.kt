package com.cs30.server.dto

import com.cs30.server.models.Course
import java.time.LocalDateTime

/** One problem in a lab, flattened for Canvas. */
data class CanvasProblemPlan(
    val name: String,
    val note: String?,
)

/**
 * Everything the Canvas commands need about one lab, as plain data. This is what
 * GET /api/admin/canvas/lab returns to the CLI, which may run on another machine - so the server's
 * student repo path is deliberately not part of it; submissions come through
 * GET /api/admin/canvas/lab/submissions instead of off the disk.
 */
data class CanvasLabPlan(
    val courseCode: String,
    val section: Int,
    val labNumber: Int,
    val startDateTime: LocalDateTime,
    val endDateTime: LocalDateTime,
    val problems: List<CanvasProblemPlan>,
    val studentEmails: List<String>,
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

/** A best submission with whose it is - one element of the per-problem batch the CLI fetches. */
data class StudentBestSubmission(
    val email: String,
    val submission: BestSubmission,
)

/**
 * Which cs30 courses the CLI is asking about: a fragment of the code, a year, a fragment of the
 * semester, a section, and whether only courses that have not ended count. A filter left null is
 * not applied, so an empty query is every course. The query string of GET /api/admin/canvas/courses.
 */
data class CourseQuery(
    val code: String? = null,
    val year: Int? = null,
    val semester: String? = null,
    val section: Int? = null,
    val active: Boolean = false,
) {
    /** How the query reads in a message: "code 'cs30', semester 'fa'". */
    override fun toString(): String = listOfNotNull(
        code?.let { "code '$it'" },
        year?.let { "year $it" },
        semester?.let { "semester '$it'" },
        section?.let { "section $it" },
        "active".takeIf { active },
    ).joinToString(", ").ifEmpty { "any course" }
}

/**
 * The four fields that identify one cs30 course: what GET /api/admin/canvas/courses returns and
 * what the lab endpoints take. Sorts in the order every course listing uses.
 */
data class CourseRef(
    val code: String,
    val year: Int,
    val semester: String,
    val section: Int,
) : Comparable<CourseRef> {
    /** The course as the other course commands word it, so messages line up. */
    fun describe(): String = "$code (Section $section, Semester $semester, Year $year)"

    override fun compareTo(other: CourseRef): Int =
        compareValuesBy(this, other, { it.code }, { it.year }, { it.semester }, { it.section })
}

fun Course.toRef(): CourseRef = CourseRef(code, year, semester, section)
