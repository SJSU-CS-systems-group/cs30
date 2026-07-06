package com.cs30.server.controller

import com.cs30.server.service.ActivityLogService
import com.cs30.server.service.StudentIdentityService
import data.LockdownViolation
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/activity")
class ActivityController(
    private val identity: StudentIdentityService,
    private val activityLogService: ActivityLogService,
) {
    private val logger = LoggerFactory.getLogger(ActivityController::class.java)

    /** Records one lockdown event. Body reuses LockdownViolation — same type the frontend emits. */
    @PostMapping("/event")
    fun recordEvent(
        @RequestParam("problem", required = false) problem: String?,
        @RequestBody violation: LockdownViolation,
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ResponseEntity<Void> {
        val problemLabel = problem?.takeIf { it.isNotBlank() } ?: "-"
        logger.info("[ACTIVITY-EVENT] POST /api/activity/event problem={}", problemLabel)
        logger.info("   kind={}, timestamp={}", violation.kind, violation.timestampMs)

        val email = identity.resolve(auth)
        if (email == null) {
            logger.warn("[ACTIVITY-EVENT] No authenticated user. Returning 401")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        logger.info("[ACTIVITY-EVENT] Authenticated as {}", email)

        val platform = identity.platform(auth)
        val token = identity.token(auth)
        logger.info("   platform={}, problem={}", platform, problemLabel)
        activityLogService.recordEvent(email, token, problem.orEmpty(), violation, platform)
        logger.info("[ACTIVITY-EVENT] Recorded: {} - {} (problem={})", violation.kind, violation.detail, problemLabel)
        return ResponseEntity.accepted().build()
    }

    /** Commits the student's activity CSV to git when lockdown ends. */
    @PostMapping("/commit")
    fun commitSession(
        @RequestHeader("Authorization", required = false) auth: String?,
    ): ResponseEntity<Void> {
        logger.info("[ACTIVITY-COMMIT] POST /api/activity/commit")

        val email = identity.resolve(auth)
        if (email == null) {
            logger.warn("[ACTIVITY-COMMIT] No authenticated user. Returning 401")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        logger.info("[ACTIVITY-COMMIT] Authenticated as {}", email)

        activityLogService.commitSession(email)
        logger.info("[ACTIVITY-COMMIT] Committed: user={}", email)
        return ResponseEntity.accepted().build()
    }
}
