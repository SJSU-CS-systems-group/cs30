package editor

import backend.getJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface LabTimeService {
    suspend fun fetchRemainingMs(courseId: String, labNumber: Int): Long?
}

class HttpLabTimeService(
    private val baseUrl: String,
    private val getAuthHeader: () -> String?,
) : LabTimeService {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchRemainingMs(courseId: String, labNumber: Int): Long? = try {
        val response = getJson(baseUrl, "/api/labs/$courseId/lab/$labNumber/remaining", getAuthHeader())
        json.decodeFromString<LabRemainingDto>(response).remainingMs
    } catch (e: Exception) {
        println("[HttpLabTimeService] fetchRemainingMs failed: ${e.message}")
        null
    }
}

// remainingMs is null for the course TA, who is not held to the lab window: no countdown to show.
@Serializable
private data class LabRemainingDto(val remainingMs: Long? = null)
