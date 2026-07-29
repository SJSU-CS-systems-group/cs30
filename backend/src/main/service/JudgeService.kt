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

    private companion object {
        // kt-judge's own default when a request doesn't pin one (judge.timeouts.run-all-wall-seconds).
        const val DEFAULT_WALL_SECONDS = 60
        // Must exceed kt-judge's JudgeStore.SYNC_MARGIN_SECONDS (10) so the client is always the more
        // patient side; the extra covers network + serialization on top.
        const val CLIENT_MARGIN_SECONDS = 30L
        // Only used when /queue-status can't be reached: kt-judge's documented defaults, biased toward
        // FEWER workers (= a longer, safer wait) since being too impatient is the failure mode here.
        const val FALLBACK_MAX_QUEUE_SIZE = 100
        const val FALLBACK_MAX_WORKERS = 8
    }

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
     * kt-judge's concurrency limits are read once at ITS startup and never change at runtime (see
     * kt-judge/README.md "Configuration"), so cache them after the first successful read. Without
     * this, sizing the timeout below would fire one extra /queue-status call per submit/run — 100 of
     * them at exactly the moment a burst has the judge busiest.
     *
     * Deliberately caches only the static limits, not inFlight: the public queueStatus() above stays
     * live and uncached, because its caller (the student-facing "N in process" count) needs the real
     * current depth.
     */
    @Volatile
    private var cachedJudgeLimits: Pair<Int, Int>? = null   // maxQueueSize to maxWorkers

    private fun judgeLimits(): Pair<Int, Int> {
        cachedJudgeLimits?.let { return it }
        return try {
            val qs = queueStatus()
            (qs.maxQueueSize to qs.maxWorkers).also { cachedJudgeLimits = it }
        } catch (e: Exception) {
            log.warn("queue-status unavailable for timeout sizing, assuming conservative limits: ${e.message}")
            FALLBACK_MAX_QUEUE_SIZE to FALLBACK_MAX_WORKERS
        }
    }

    /**
     * How long this HTTP client waits for a judge verdict. The only requirement is that it must
     * never give up BEFORE kt-judge's own internal wait budget does — otherwise the backend reports
     * a failure for a job the judge went on to grade successfully, and the student's submission
     * record is written as failed (CodeService.kt catches the timeout).
     *
     * Sized from the judge's WORST CASE, not the current queue depth. The previous version read
     * inFlight from /queue-status and scaled by that, but the read happens BEFORE this request is
     * submitted — so in a synchronized burst every caller samples an empty queue at the same instant,
     * computes aheadRounds=0, and budgets the bare minimum, while the judge (which computes its own
     * budget AFTER admission, seeing the real depth) is willing to wait far longer. Measured at 100
     * concurrent with maxWorkers=10: client gave up at 90s, judge would have waited 670s, and 30% of
     * submissions failed purely from that mismatch.
     *
     * maxQueueSize is the most jobs the judge will ever admit, so ceil(maxQueueSize / maxWorkers)
     * rounds of `wall` is the longest it can ever make a caller wait — matching JudgeStore.runAndWait's
     * formula at its ceiling, plus a margin larger than the judge's own (SYNC_MARGIN_SECONDS = 10) so
     * the client is always the more patient of the two.
     */
    private fun clientTimeoutSeconds(wallTimeout: Int?): Long {
        val wall = (wallTimeout ?: DEFAULT_WALL_SECONDS).toLong()
        val (maxQueueSize, maxWorkers) = judgeLimits()
        val worstCaseRounds = kotlin.math.ceil(maxQueueSize.toDouble() / maxWorkers).toLong()
        return wall + worstCaseRounds * wall + CLIENT_MARGIN_SECONDS
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
