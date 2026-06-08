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
