package com.cs30.server.dto

data class SubmitCodeRequest(
    val courseId: String,
    val section: Int,
    val labNumber: Int,
    val problemName: String,
    val studentEmail: String,
    val code: String,
    val language: String? = null  // Override course language if needed
)

data class TestcaseResult(
    val name: String,
    val status: String?,
    val timeS: Double?,
    val input: String?,
    val expected: String?,
    val stdout: String?,
    val stderr: String?
)

data class SubmitCodeResponse(
    val success: Boolean,
    val message: String,
    val status: String? = null,        // AC, WA, TLE, RTE, MLE, CE
    val passed: Int? = null,
    val total: Int? = null,
    val maxTimeS: Double? = null,
    val testcases: List<TestcaseResult>? = null,
    val compileOutput: String? = null,
    val filePath: String? = null
)

data class RunCodeRequest(
    val courseId: String,
    val section: Int,
    val labNumber: Int,
    val problemName: String,
    val studentEmail: String,
    val code: String,
    val language: String? = null,
    val customStdins: List<String> = emptyList()   // custom inputs; one ungraded case each
)

data class RunCodeResponse(
    val success: Boolean,
    val message: String,
    val testcases: List<TestcaseResult>? = null,
    val compileOutput: String? = null
)

// A stateless, system-wide load snapshot from the judge — not a per-request/per-student position.
// Named distinctly from SubmitCodeResponse.status (a graded verdict like AC/WA) to avoid ambiguity.
data class QueueStatusResponse(
    val inFlight: Int,
    val maxQueueSize: Int,
    val maxWorkers: Int
)

