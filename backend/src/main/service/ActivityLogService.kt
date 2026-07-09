package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.LockdownViolation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate

@Service
class ActivityLogService(
    private val gitService: GitService,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(ActivityLogService::class.java)

    fun recordEvent(
        studentEmail: String,
        token: String,
        problem: String,
        violation: LockdownViolation,
        platform: String,
    ) {
        val course = courseRepository.findByStudentEmail(studentEmail).firstOrNull()
            ?: run {
                log.warn("recordEvent: no course found for {}", studentEmail)
                return
            }
        val date = LocalDate.now().toString()
        val iso = Instant.ofEpochMilli(violation.timestampMs).toString()
        val safeDetail = violation.detail?.replace("\"", "\"\"") ?: ""
        val row = "\"$token\",${violation.timestampMs},$iso,$platform,\"$problem\",${violation.kind.name},\"$safeDetail\""
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
        val course = courseRepository.findByStudentEmail(studentEmail).firstOrNull()
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
