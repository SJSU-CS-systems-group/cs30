package data

import kotlinx.serialization.Serializable

@Serializable
data class LabProblemInfo(
    val courseId: String,
    val courseCode: String,
    val section: Int,
    val labNumber: Int,
    val slug: String,
    val title: String
)

/** Combined HTML and CSS content for a problem */
@Serializable
data class ProblemContent(
    val html: String,
    val css: String
)
