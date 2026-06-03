package com.cs30.server.controller

import com.cs30.server.service.CodeService
import com.cs30.server.service.SaveCodeRequest
import com.cs30.server.service.SaveCodeResponse
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
}