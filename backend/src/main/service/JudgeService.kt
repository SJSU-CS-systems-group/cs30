package com.cs30.server.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class JudgeSubmitRequest(
    @JsonProperty("problem_id") val problemId: String,
    @JsonProperty("pool_path") val poolPath: String,
    val language: String,
    val source: String,
    @JsonProperty("wall_timeout") val wallTimeout: Int? = null
)

data class JudgeRunRequest(
    @JsonProperty("problem_id") val problemId: String,
    @JsonProperty("pool_path") val poolPath: String,
    val language: String,
    val source: String,
    @JsonProperty("custom_stdins") val customStdins: List<String> = emptyList(),
    @JsonProperty("wall_timeout") val wallTimeout: Int? = null
)

data class JudgeTestcase(
    val name: String,
    val status: String?,
    @JsonProperty("time_s") val timeS: Double?,
    val input: String?,
    val expected: String?,
    val stdout: String?,
    val stderr: String?
)

data class JudgeSubmitResponse(
    val status: String,
    val passed: Int,
    val total: Int,
    @JsonProperty("max_time_s") val maxTimeS: Double,
    val testcases: List<JudgeTestcase>,
    @JsonProperty("compile_output") val compileOutput: String?
)

data class JudgeRunResponse(
    val testcases: List<JudgeTestcase>,
    @JsonProperty("compile_output") val compileOutput: String?
)

// A stateless, system-wide load snapshot from the judge — not a per-job position. Named distinctly
// from JudgeSubmitResponse.status (a graded verdict like AC/WA) to avoid colliding in meaning.
data class JudgeQueueStatusResponse(
    @JsonProperty("in_flight") val inFlight: Int,
    @JsonProperty("max_queue_size") val maxQueueSize: Int,
    @JsonProperty("max_workers") val maxWorkers: Int
)

@Service
class JudgeService(
    @Value("\${judge.url:http://localhost:8000}") private val judgeUrl: String
) {
    private val log = LoggerFactory.getLogger(JudgeService::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1) // judge (uvicorn) is HTTP/1.1-only
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /**
     * A stateless, system-wide load snapshot — GET {judgeUrl}/queue-status. Used both to size the
     * client-side timeout on submit()/run() (see clientTimeoutSeconds()) and, by the caller, to show
     * students a one-time "N in process" count at submission.
     */
    fun queueStatus(): JudgeQueueStatusResponse {
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$judgeUrl/queue-status"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("Judge queue-status error (${response.statusCode()}): ${response.body()}")
        }
        return objectMapper.readValue(response.body(), JudgeQueueStatusResponse::class.java)
    }

    /**
     * Mirrors JudgeStore.runAndWait's own dynamic-timeout estimate on the kt-judge side, so this
     * client never gives up before the judge's own (queue-depth-aware) wait budget does — a fixed
     * client timeout here would reintroduce the "client aborts before server finishes" problem this
     * was built to avoid. Falls back to the previous fixed wall+30s if the queue-status check itself
     * fails, rather than blocking the real submit/run call on it.
     */
    private fun clientTimeoutSeconds(wallTimeout: Int?): Long {
        val wall = wallTimeout ?: 60
        val qs = try {
            queueStatus()
        } catch (e: Exception) {
            log.warn("queue-status check failed, falling back to fixed client timeout: ${e.message}")
            return wall + 30L
        }
        val aheadRounds = kotlin.math.ceil(qs.inFlight.toDouble() / qs.maxWorkers)
        val estimatedWaitSeconds = (aheadRounds * wall).toLong()
        return wall + estimatedWaitSeconds + 30L
    }

    /**
     * Submit code to the judge for grading against all testcases.
     */
    fun submit(problemId: String, poolPath: String, language: String, source: String, wallTimeout: Int? = null): JudgeSubmitResponse {
        val request = JudgeSubmitRequest(
            problemId = problemId,
            poolPath = poolPath,
            language = mapLanguage(language),
            source = source,
            wallTimeout = wallTimeout
        )

        val jsonBody = objectMapper.writeValueAsString(request)
        log.info("Submitting to judge: problem=$problemId, language=$language")

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$judgeUrl/submit"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(clientTimeoutSeconds(wallTimeout)))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            log.error("Judge returned error: ${response.statusCode()} - ${response.body()}")
            throw RuntimeException("Judge error (${response.statusCode()}): ${response.body()}")
        }

        return objectMapper.readValue(response.body(), JudgeSubmitResponse::class.java)
    }

    /**
     * Run code against sample testcases (+ optional custom inputs, one case each).
     */
    fun run(
        problemId: String,
        poolPath: String,
        language: String,
        source: String,
        customStdins: List<String> = emptyList(),
        wallTimeout: Int? = null
    ): JudgeRunResponse {
        val request = JudgeRunRequest(
            problemId = problemId,
            poolPath = poolPath,
            language = mapLanguage(language),
            source = source,
            customStdins = customStdins,
            wallTimeout = wallTimeout
        )

        val jsonBody = objectMapper.writeValueAsString(request)
        log.info("Running on judge: problem=$problemId, language=$language")

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$judgeUrl/run"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(clientTimeoutSeconds(wallTimeout)))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            log.error("Judge returned error: ${response.statusCode()} - ${response.body()}")
            throw RuntimeException("Judge error (${response.statusCode()}): ${response.body()}")
        }

        return objectMapper.readValue(response.body(), JudgeRunResponse::class.java)
    }

    /**
     * Map course language names to judge language codes.
     */
    private fun mapLanguage(language: String): String {
        return when (language.lowercase()) {
            "java" -> "java"
            "python" -> "python"
            "c" -> "c"
            "c++", "cpp" -> "cpp"
            "javascript", "js" -> "javascript"
            else -> language.lowercase()
        }
    }
}
