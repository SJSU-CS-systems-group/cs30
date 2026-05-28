package labx.data

import kotlinx.serialization.Serializable

@Serializable
data class ProblemSummary(
    val slug: String,
    val title: String,
    val difficulty: String? = null
)
