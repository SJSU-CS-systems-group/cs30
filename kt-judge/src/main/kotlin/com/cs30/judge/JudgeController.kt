package com.cs30.judge

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
class JudgeController(
    private val store: JudgeStore,
    private val readiness: JudgeReadiness,
    private val selfTest: JudgeSelfTest,
) {

    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "ok")

    // Readiness: can it actually judge? Verifies Docker is up and the sandbox
    // image is present. 503 when not ready (safe for a load balancer to poll).
    @GetMapping("/ready")
    fun ready(): ResponseEntity<Map<String, String>> {
        val s = readiness.check(System.currentTimeMillis())
        val body = mapOf("status" to if (s.ok) "ready" else "not_ready", "detail" to s.detail)
        val code = if (s.ok) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(code).body(body)
    }

    // Deep self-test: grades a built-in known-good solution end to end and
    // confirms AC. Proves grading actually works (not just that Docker exists).
    // Costs a container run, so use it on deploy / periodically, not for polling.
    @GetMapping("/selftest")
    fun selftest(): ResponseEntity<Map<String, Any>> {
        val r = selfTest.run()
        val body = mapOf<String, Any>(
            "ok" to r.ok, "verdict" to r.verdict,
            "passed" to r.passed, "total" to r.total, "detail" to r.detail,
        )
        val code = if (r.ok) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(code).body(body)
    }

    // A system-wide load snapshot, not a per-job position — no job-ID tracking exists. Used by the
    // backend to size its own client-side timeout to match the judge's dynamic wait budget, and to show
    // students a one-time "N in process" count at submission time.
    @GetMapping("/queue-status")
    fun queueStatus(): QueueStatusResponse = store.queueStatus()

    @PostMapping("/submit")
    fun submit(@RequestBody req: SubmitRequest): SubmitResponse {
        val r = store.submitSync(req)
        return SubmitResponse(
            status = r.status,
            passed = r.passed,
            total = r.total,
            maxTimeS = r.maxTimeS,
            testcases = r.cases.map {
                SubmitTestcase(it.name, it.status, it.timeS, it.input, it.expected, it.stdout, it.stderr)
            },
            compileOutput = r.compileOutput,
        )
    }

    @PostMapping("/run")
    fun run(@RequestBody req: RunRequest): RunResponse {
        val r = store.runSync(req)
        return RunResponse(
            testcases = r.cases.map {
                RunTestcase(it.name, it.status, it.timeS, it.input, it.expected, it.stdout, it.stderr)
            },
            compileOutput = r.compileOutput,
        )
    }
}

// Maps the judge's domain errors to status codes (same contract as the Python
// service). Any other exception falls through to Spring's default 500.
//
// TODO(deferred): "any other exception" currently produces Spring's generic whitelabel body
// ({"timestamp":...,"status":500,"error":"Internal Server Error","path":"/submit"}) — no `detail`
// field at all, unlike the three handlers below. Real causes that fall into this bucket: the
// orchestrator-produced-no-output RuntimeExceptions in JudgeRunner.kt, any Docker-level failure
// (daemon down, OOM-kill — Proc.exit is computed but never checked, see JudgeRunner.kt's invoke()),
// file I/O failures in JudgeStore.stage()/cleanup(), malformed orchestrator JSON. Even with the
// backend's logJudgeFailure() context fix (CodeService.kt), a failure here still only logs this
// useless generic body — real diagnosis still requires SSHing into kt-judge's own journalctl,
// exactly like the PermissionError investigation during load testing. Planned fix, not yet applied:
//   @ExceptionHandler(Exception::class)
//   @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
//   fun internalError(e: Exception) = mapOf("detail" to (e.message ?: e::class.simpleName ?: "internal error"))
// Safe to add: kt-judge binds to 127.0.0.1 only, never internet-reachable, called solely by the
// co-located backend — echoing e.message carries none of the info-disclosure risk it would on a
// public API, and it matches the pattern the three handlers below already use.
@RestControllerAdvice
class JudgeExceptionHandler {

    @ExceptionHandler(JudgeError::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(e: JudgeError) = mapOf("detail" to (e.message ?: "bad request"))

    @ExceptionHandler(QueueFull::class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    fun queueFull(e: QueueFull) = mapOf("detail" to (e.message ?: "judge at capacity"))

    @ExceptionHandler(SyncTimeout::class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    fun syncTimeout(e: SyncTimeout) = mapOf("detail" to (e.message ?: "judge timeout"))
}
