package com.cs30.server.controller

import com.cs30.server.dto.RunCodeRequest
import com.cs30.server.dto.RunCodeResponse
import com.cs30.server.dto.SubmitCodeRequest
import com.cs30.server.dto.SubmitCodeResponse
import com.cs30.server.service.CodeService
import com.cs30.server.service.StudentIdentityService
import data.SubmissionInfo
import jakarta.servlet.http.HttpSession
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/code")
class CodeController(
    private val codeService: CodeService,
    private val identity: StudentIdentityService
) {
    @PostMapping("/submit")
    fun submitCode(
        @RequestBody request: SubmitCodeRequest,
        @RequestHeader("Authorization", required = false) auth: String?,
        session: HttpSession
    ): ResponseEntity<SubmitCodeResponse> {
        // Resolve email from token, ignore frontend-provided studentEmail
        val email = identity.resolve(session, auth)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(SubmitCodeResponse(false, "Unauthorized"))

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
        @RequestHeader("Authorization", required = false) auth: String?,
        session: HttpSession
    ): ResponseEntity<RunCodeResponse> {
        // Resolve email from token, ignore frontend-provided studentEmail
        val email = identity.resolve(session, auth)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(RunCodeResponse(false, "Unauthorized"))

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
        @RequestHeader("Authorization", required = false) auth: String?,
        session: HttpSession
    ): ResponseEntity<List<SubmissionInfo>> {
        // Resolve email from token, ignore frontend-provided studentEmail param
        val email = identity.resolve(session, auth)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val submissions = codeService.listSubmissions(courseId, section, labNumber, problemName, email)
        return ResponseEntity.ok(submissions)
    }
}