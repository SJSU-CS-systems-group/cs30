package com.cs30.server.controller

import jakarta.servlet.http.HttpSession
import labx.data.LockdownViolation
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
class ViolationsController {
    private val logger = LoggerFactory.getLogger(ViolationsController::class.java)

    @PostMapping("/violations")
    fun reportViolation(
        @RequestBody violation: LockdownViolation,
        session: HttpSession
    ): ResponseEntity<Void> {
        val who = session.getAttribute("user_email") as? String ?: "anon"
        logger.warn(
            "LOCKDOWN_VIOLATION user=$who kind=${violation.kind} ts=${violation.timestampMs} detail=${violation.detail}"
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }
}
