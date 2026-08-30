package com.cs30.server.dto

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
 * How the CLI names a cs30 course without spelling it out: a fragment of the code, optionally
 * narrowed by year, a fragment of the semester, and section. A null filter is simply not applied.
 * This is the query string of GET /api/admin/canvas/course.
 */
data class CourseQuery(
    val code: String,
    val year: Int? = null,
    val semester: String? = null,
    val section: Int? = null,
) {
    /** How the query reads in a message: "code 'cs30', semester 'fa'". */
    override fun toString(): String = listOfNotNull(
        "code '$code'",
        year?.let { "year $it" },
        semester?.let { "semester '$it'" },
        section?.let { "section $it" },
    ).joinToString(", ")
}

/**
 * The four fields that identify one cs30 course: what GET /api/admin/canvas/course resolves a
 * [CourseQuery] to, and what the lab endpoints then take.
 */
data class CourseRef(
    val code: String,
    val year: Int,
    val semester: String,
    val section: Int,
) {
    /** The course as the other course commands word it, so messages line up. */
    fun describe(): String = "$code (Section $section, Semester $semester, Year $year)"
}
