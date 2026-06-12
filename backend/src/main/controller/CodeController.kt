package com.cs30.server.controller

import com.cs30.server.dto.*
import com.cs30.server.service.CodeService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/code")
class CodeController(
    private val codeService: CodeService
) {
    @PostMapping("/save")
    fun saveCode(@RequestBody request: SaveCodeRequest): ResponseEntity<SaveCodeResponse> {
        val response = codeService.saveCode(request)
        return if (response.success) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.badRequest().body(response)
        }
    }

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
}