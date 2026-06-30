package com.cs30.judge

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
class JudgeController(private val store: JudgeStore) {

    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "ok")

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
