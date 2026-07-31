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
    /**
     * Whether the request was *processed*, NOT whether the student's code is correct.
     *
     * false means the system could not produce a verdict at all — the judge was unreachable or threw,
     * the lab deadline had passed, or the submission could not be saved to git. A graded rejection
     * (WA, TLE, CE, …) is a successful outcome by this field's meaning; read [status] for the verdict.
     *
     * Do not repurpose this as "the code worked". `CodeEditorState.terminalErrorOrNull` renders a
     * generic "Judge Error" panel whenever it is false, so widening the meaning turns ordinary graded
     * failures into infrastructure-error screens.
     */
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
    /**
     * Whether the request was *processed*, NOT whether the student's code compiled or passed.
     *
     * false means the run never happened — the lab deadline had passed, or the judge threw. A compile
     * error leaves this true, because the judge did run and did report a result; check
     * [compileOutput] for that, and [testcases] for per-case verdicts.
     *
     * Unlike [SubmitCodeResponse] there is no `status` field here, so this response has no single
     * field meaning "it worked". A consumer must inspect [compileOutput] and [testcases] — reading
     * `success` alone will report a compile error as a pass, which is exactly what happened to a k6
     * load-test script that asserted on it.
     */
    val success: Boolean,
    /**
     * Human-readable outcome, derived from the result rather than fixed — see
     * `CodeService.runOutcomeMessage`. This reaches students directly: `HttpBackendService.runSummary`
     * displays it verbatim when [testcases] is empty, so it must never claim success unconditionally.
     */
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

