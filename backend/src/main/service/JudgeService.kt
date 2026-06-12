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
    val language: String,
    val source: String,
    @JsonProperty("wall_timeout") val wallTimeout: Int? = null
)

data class JudgeRunRequest(
    @JsonProperty("problem_id") val problemId: String,
    val language: String,
    val source: String,
    val stdin: String? = null,
    val expected: String? = null,
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
     * Submit code to the judge for grading against all testcases.
     */
    fun submit(problemId: String, language: String, source: String, wallTimeout: Int? = null): JudgeSubmitResponse {
        val request = JudgeSubmitRequest(
            problemId = problemId,
            language = mapLanguage(language),
            source = source,
            wallTimeout = wallTimeout
        )

        val jsonBody = objectMapper.writeValueAsString(request)
        log.info("Submitting to judge: problem=$problemId, language=$language")

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$judgeUrl/submit"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds((wallTimeout ?: 60) + 30L))
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
     * Run code against sample testcases (+ optional custom input).
     */
    fun run(
        problemId: String,
        language: String,
        source: String,
        stdin: String? = null,
        expected: String? = null,
        wallTimeout: Int? = null
    ): JudgeRunResponse {
        val request = JudgeRunRequest(
            problemId = problemId,
            language = mapLanguage(language),
            source = source,
            stdin = stdin,
            expected = expected,
            wallTimeout = wallTimeout
        )

        val jsonBody = objectMapper.writeValueAsString(request)
        log.info("Running on judge: problem=$problemId, language=$language")

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$judgeUrl/run"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds((wallTimeout ?: 60) + 30L))
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
            "kotlin" -> "kotlin"
            "python" -> "python"
            "c" -> "c"
            "c++", "cpp" -> "cpp"
            "javascript", "js" -> "javascript"
            else -> language.lowercase()
        }
    }
}
