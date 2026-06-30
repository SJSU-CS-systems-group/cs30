package com.cs30.server.controller

import com.cs30.server.dto.RunCodeRequest
import com.cs30.server.dto.RunCodeResponse
import com.cs30.server.dto.SubmitCodeRequest
import com.cs30.server.dto.SubmitCodeResponse
import com.cs30.server.service.CodeService
import data.SubmissionInfo
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/code")
class CodeController(
    private val codeService: CodeService
) {
    @PostMapping("/submit")
    fun submitCode(@RequestBody request: SubmitCodeRequest): ResponseEntity<SubmitCodeResponse> {
        val response = codeService.submitCode(request)
        return if (response.success) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.badRequest().body(response)
        }
    }

    @PostMapping("/run")
    fun runCode(@RequestBody request: RunCodeRequest): ResponseEntity<RunCodeResponse> {
        val response = codeService.runCode(request)
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
        @RequestParam studentEmail: String
    ): ResponseEntity<List<SubmissionInfo>> {
        val submissions = codeService.listSubmissions(courseId, section, labNumber, problemName, studentEmail)
        return ResponseEntity.ok(submissions)
    }
}