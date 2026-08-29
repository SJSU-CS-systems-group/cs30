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
