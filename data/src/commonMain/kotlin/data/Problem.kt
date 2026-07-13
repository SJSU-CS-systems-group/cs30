package data

import kotlinx.serialization.Serializable

@Serializable
data class LabProblemInfo(
    val courseId: String,
    val courseCode: String,
    val section: Int,
    val labNumber: Int,
    val slug: String,
    val title: String,
    val language: String,
    val note: String? = null
)

/** Combined HTML and CSS content for a problem */
@Serializable
data class ProblemContent(
    val html: String,
    val css: String
)

/** Info about a past submission */
@Serializable
data class SubmissionInfo(
    val timestamp: String,    // display format: 2024-01-15 10:30:00
    val passed: Int,
    val total: Int,
    val maxTimeMs: Double?,   // max runtime in milliseconds
    val status: String,       // AC, WA, TLE, etc.
    val filePath: String,
    val code: String = "",    // submitted source code; default empty for older entries
)
