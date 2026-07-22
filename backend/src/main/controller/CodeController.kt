package com.cs30.server.controller

import com.cs30.server.dto.QueueStatusResponse
import com.cs30.server.dto.RunCodeRequest
import com.cs30.server.dto.RunCodeResponse
import com.cs30.server.dto.SubmitCodeRequest
import com.cs30.server.dto.SubmitCodeResponse
import com.cs30.server.service.CodeService
import com.cs30.server.service.JudgeService
import com.cs30.server.service.StudentIdentityService
import data.SubmissionInfo
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/code")
class CodeController(
    private val codeService: CodeService,
    private val identityService: StudentIdentityService,
    private val judgeService: JudgeService
) {
    private val log = LoggerFactory.getLogger(CodeController::class.java)

    // A stateless, system-wide load snapshot, not tied to any student/course — a thin passthrough to
    // the judge, so it's called directly rather than routed through CodeService (whose job is
    // student/course business logic like enrollment and deadline checks, none of which applies here).
    // Still requires a valid session, same as every other endpoint here — the data itself isn't
    // sensitive, but an unauthenticated status check would be an inconsistent gap in an otherwise
    // fully-authenticated API, and it hands out the judge's exact capacity thresholds to anyone.
    @GetMapping("/queue-status")
    fun queueStatus(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<QueueStatusResponse> {
        identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val qs = judgeService.queueStatus()
        return ResponseEntity.ok(QueueStatusResponse(qs.inFlight, qs.maxQueueSize, qs.maxWorkers))
    }

    @PostMapping("/submit")
    fun submitCode(
        @RequestBody request: SubmitCodeRequest,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<SubmitCodeResponse> {
        // Resolve email from token, ignore frontend-provided studentEmail
        val email = identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(SubmitCodeResponse(false, "Unauthorized"))
        if (request.studentEmail != email) {
            log.warn("[identity-mismatch] submit claimed=${request.studentEmail} resolved=$email")
        }
        val response = codeService.submitCode(request.copy(studentEmail = email))
        return if (response.success) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.badRequest().body(response)
        }
    }

    @PostMapping("/run")
    fun runCode(
        @RequestBody request: RunCodeRequest,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<RunCodeResponse> {
        // Resolve email from token, ignore frontend-provided studentEmail
        val email = identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(RunCodeResponse(false, "Unauthorized"))
        if (request.studentEmail != email) {
            log.warn("[identity-mismatch] run claimed=${request.studentEmail} resolved=$email")
        }
        val response = codeService.runCode(request.copy(studentEmail = email))
        return if (response.success) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.badRequest().body(response)
        }
    }

    @GetMapping("/submissions")
    fun listSubmissions(
        @RequestParam courseId: String,
        @RequestParam section: Int,
        @RequestParam labNumber: Int,
        @RequestParam problemName: String,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<SubmissionInfo>> {
        // Resolve email from token, ignore frontend-provided studentEmail param
        val email = identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val submissions = codeService.listSubmissions(courseId, section, labNumber, problemName, email)
        return ResponseEntity.ok(submissions)
    }
}
