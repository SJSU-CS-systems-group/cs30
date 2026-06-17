package backend

import kotlinx.serialization.Serializable

@Serializable
data class RunCodeRequestDto(
    val courseId: String,
    val section: Int,
    val labNumber: Int,
    val problemName: String,
    val studentEmail: String,
    val code: String,
    val language: String? = null,
    val stdin: String? = null,
    val expected: String? = null,
)

@Serializable
data class SubmitCodeRequestDto(
    val courseId: String,
    val section: Int,
    val labNumber: Int,
    val problemName: String,
    val studentEmail: String,
    val code: String,
    val language: String? = null,
)

@Serializable
data class TestcaseResultDto(
    val name: String,
    val status: String? = null,
    val timeS: Double? = null,
    val input: String? = null,
    val expected: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
)

@Serializable
data class RunCodeResponseDto(
    val success: Boolean = false,
    val message: String = "",
    val testcases: List<TestcaseResultDto>? = null,
    val compileOutput: String? = null,
)

@Serializable
data class SubmitCodeResponseDto(
    val success: Boolean = false,
    val message: String = "",
    val status: String? = null,
    val passed: Int? = null,
    val total: Int? = null,
    val maxTimeS: Double? = null,
    val testcases: List<TestcaseResultDto>? = null,
    val compileOutput: String? = null,
    val filePath: String? = null,
)
