package labx.data

import kotlinx.serialization.Serializable

@Serializable
data class Student(
    val id: String,
    val name: String,
    val email: String
)

@Serializable
data class TestResult(
    val testCase: Int,
    val input: String,
    val expectedOutput: String,
    val actualOutput: String,
    val passed: Boolean
)

@Serializable
data class TestResultsResponse(
    val status: String,
    val results: List<TestResult>
)

@Serializable
data class RunOutput(
    val status: String,
    val stdout: String,
    val stderr: String,
    val executionTimeMs: Int
)

@Serializable
data class RuntimeError(
    val status: String,
    val stderr: String
)

data class AuthResult(
    val success: Boolean,
    val student: Student?,
    val errorMessage: String? = null
)

enum class ViolationKind {
    FocusLoss,
    FocusGained,
    FullscreenExit,
    TabHidden,
    TabVisible,
    PasteFromOutside,
    CopyFromEditor,
    ContextMenu,
    DevToolsAttempt,
    ClipboardEscape,
    Heartbeat,
    HeartbeatGap,
    SessionSummary,
}

@Serializable
data class LockdownViolation(
    val kind: ViolationKind,
    val timestampMs: Long,
    val detail: String? = null
)
