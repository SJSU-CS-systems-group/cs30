package com.cs30.server.dto

enum class SaveType {
    AUTOSAVE,
    SUBMISSION
}

data class SaveCodeRequest(
    val courseId: String,
    val section: Int,
    val labNumber: Int,
    val problemName: String,
    val studentEmail: String,
    val code: String,
    val saveType: SaveType = SaveType.AUTOSAVE
)

data class SaveCodeResponse(
    val success: Boolean,
    val message: String,
    val filePath: String? = null
)

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
    val stdin: String? = null,      // Custom input (optional)
    val expected: String? = null    // Custom expected output (optional)
)

data class RunCodeResponse(
    val success: Boolean,
    val message: String,
    val testcases: List<TestcaseResult>? = null,
    val compileOutput: String? = null
)