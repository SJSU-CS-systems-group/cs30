package com.cs30.judge

import com.fasterxml.jackson.annotation.JsonProperty

data class SubmitRequest(
    @JsonProperty("problem_id") val problemId: String,
    @JsonProperty("pool_path") val poolPath: String,
    val language: String,
    val source: String,
    @JsonProperty("wall_timeout") val wallTimeout: Int? = null,
)

data class RunRequest(
    @JsonProperty("problem_id") val problemId: String,
    @JsonProperty("pool_path") val poolPath: String,
    val language: String,
    val source: String,
    @JsonProperty("custom_stdins") val customStdins: List<String> = emptyList(),
    val stdin: String? = null,
    @JsonProperty("wall_timeout") val wallTimeout: Int? = null,
)

data class SubmitTestcase(
    val name: String,
    val status: String?,
    @JsonProperty("time_s") val timeS: Double?,
    val input: String?,
    val expected: String?,
    val stdout: String?,
    val stderr: String?,
)

data class SubmitResponse(
    val status: String,
    val passed: Int,
    val total: Int,
    @JsonProperty("max_time_s") val maxTimeS: Double,
    val testcases: List<SubmitTestcase>,
    @JsonProperty("compile_output") val compileOutput: String?,
)

data class RunTestcase(
    val name: String,
    val status: String?,
    @JsonProperty("time_s") val timeS: Double?,
    val input: String?,
    val expected: String?,
    val stdout: String?,
    val stderr: String?,
)

data class RunResponse(
    val testcases: List<RunTestcase>,
    @JsonProperty("compile_output") val compileOutput: String?,
)

// A stateless, system-wide snapshot — not a per-job position. inFlight/maxQueueSize come straight from
// the admission semaphore's own live state (JudgeStore.inFlightCount()); no job-ID tracking involved.
data class QueueStatusResponse(
    @JsonProperty("in_flight") val inFlight: Int,
    @JsonProperty("max_queue_size") val maxQueueSize: Int,
    @JsonProperty("max_workers") val maxWorkers: Int,
)
