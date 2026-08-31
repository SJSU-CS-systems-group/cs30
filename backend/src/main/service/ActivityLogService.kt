package com.cs30.server.service

import data.LockdownViolation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZoneOffset

@Service
class ActivityLogService(
    private val gitService: GitService,
    private val courseAccess: CourseAccessService,
) {
    private val log = LoggerFactory.getLogger(ActivityLogService::class.java)

    /**
     * Timestamps are stamped from the server's own UTC clock, never trusted from the client:
     * violation.timestampMs reflects the student's local machine clock, which isn't tamper-evident.
     */
    fun recordEvent(
        studentEmail: String,
        token: String,
        problem: String,
        violation: LockdownViolation,
        platform: String,
    ) {
        val course = courseAccess.coursesFor(studentEmail).firstOrNull()
            ?: run {
                log.warn("recordEvent: no course found for {}", studentEmail)
                return
            }
        val now = Instant.now()
        val date = now.atZone(ZoneOffset.UTC).toLocalDate().toString()
        val timestampMs = now.toEpochMilli()
        val safeDetail = violation.detail?.replace("\"", "\"\"") ?: ""
        val row = "\"$token\",$timestampMs,$now,$platform,\"$problem\",${violation.kind.name},\"$safeDetail\""
        // Callers decide whether a write failure here should be swallowed (ActivityController,
        // fire-and-forget for the live lockdown UI) or allowed to propagate (the logout hook,
        // which needs this to actually block logout on failure) — this method itself always throws.
        gitService.appendActivityLog(
            repoPath = course.studentGitRepo,
            section = course.section,
            studentEmail = studentEmail,
            date = date,
            csvRow = row,
        )
    }

    fun commitSession(studentEmail: String) {
        val course = courseAccess.coursesFor(studentEmail).firstOrNull()
            ?: run {
                log.warn("commitSession: no course found for {}", studentEmail)
                return
            }
        gitService.commitActivityLog(
            repoPath = course.studentGitRepo,
            section = course.section,
            authorEmail = studentEmail,
        )
        log.info("activity committed user={}", studentEmail)
    }
}
