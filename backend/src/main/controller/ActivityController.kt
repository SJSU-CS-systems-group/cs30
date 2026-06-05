package com.cs30.server.controller

import com.cs30.server.service.ActivityLogService
import com.cs30.server.service.StudentIdentityService
import jakarta.servlet.http.HttpSession
import labx.data.LockdownViolation
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/activity")
class ActivityController(
    private val identity: StudentIdentityService,
    private val activityLogService: ActivityLogService,
    @Value("\${CS30_COURSE_ID:}") private val courseId: String,
    @Value("\${CS30_LAB_ID:lab-01}") private val labId: String,
) {
    private val log = LoggerFactory.getLogger(ActivityController::class.java)

    /** Records one lockdown event. Body reuses LockdownViolation — same type the frontend emits. */
    @PostMapping("/{sessionId}/{problemSlug}/event")
    fun recordEvent(
        @PathVariable sessionId: String,
        @PathVariable problemSlug: String,
        @RequestBody violation: LockdownViolation,
        @RequestHeader("Authorization", required = false) auth: String?,
        session: HttpSession,
    ): ResponseEntity<Void> {
        log.info("📊 [ACTIVITY-EVENT] POST /api/activity/{}/{}/event", sessionId, problemSlug)
        log.info("   kind={}, timestamp={}", violation.kind, violation.timestampMs)

        val email = identity.resolve(session, auth)
        if (email == null) {
            log.warn("❌ [ACTIVITY-EVENT] No authenticated user. Returning 401")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        log.info("✅ [ACTIVITY-EVENT] Authenticated as {}", email)

        if (courseId.isBlank()) {
            log.error("❌ [ACTIVITY-EVENT] CS30_COURSE_ID not configured")
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }

        val platform = identity.platform(session, auth)
        log.info("   platform={}, course={}, lab={}", platform, courseId, labId)
        activityLogService.recordEvent(courseId, labId, email, sessionId, problemSlug, violation, platform)
        log.info("✅ [ACTIVITY-EVENT] Recorded: {} - {}", violation.kind, violation.detail)
        return ResponseEntity.accepted().build()
    }

    /** Commits the session's activity CSV to git when lockdown ends. */
    @PostMapping("/{sessionId}/{problemSlug}/commit")
    fun commitSession(
        @PathVariable sessionId: String,
        @PathVariable problemSlug: String,
        @RequestHeader("Authorization", required = false) auth: String?,
        session: HttpSession,
    ): ResponseEntity<Void> {
        log.info("💾 [ACTIVITY-COMMIT] POST /api/activity/{}/{}/commit", sessionId, problemSlug)

        val email = identity.resolve(session, auth)
        if (email == null) {
            log.warn("❌ [ACTIVITY-COMMIT] No authenticated user. Returning 401")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        log.info("✅ [ACTIVITY-COMMIT] Authenticated as {}", email)

        if (courseId.isBlank()) {
            log.error("❌ [ACTIVITY-COMMIT] CS30_COURSE_ID not configured")
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }

        log.info("   Committing session {} for problem {}", sessionId, problemSlug)
        activityLogService.commitSession(courseId, labId, email, sessionId, problemSlug)
        log.info("✅ [ACTIVITY-COMMIT] Committed: user={}, session={}, problem={}", email, sessionId, problemSlug)
        return ResponseEntity.accepted().build()
    }
}
