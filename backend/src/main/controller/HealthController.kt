package com.cs30.server.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// Dedicated liveness endpoint for the CI deploy health gate. Returns 200
@RestController
class HealthController {

    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "ok")
}
