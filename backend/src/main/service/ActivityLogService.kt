package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.LockdownViolation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ActivityLogService(
    private val gitService: GitService,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(ActivityLogService::class.java)

    fun recordEvent(
        courseId: String,
        labId: String,
        studentEmail: String,
        sessionId: String,
        problemSlug: String,
        violation: LockdownViolation,
        platform: String,
    ) {
        val course = courseRepository.findById(courseId).orElse(null) ?: run {
            log.warn("recordEvent: course not found courseId={}", courseId)
            return
        }
        if (!course.students.contains(studentEmail)) {
            log.warn("recordEvent: {} not enrolled in {}", studentEmail, courseId)
            return
        }
        val iso = Instant.ofEpochMilli(violation.timestampMs).toString()
        val safeDetail = violation.detail?.replace("\"", "\"\"") ?: ""
        val row = "\"$sessionId\",${violation.timestampMs},$iso,$platform,${violation.kind.name},\"$safeDetail\""
        runCatching {
            gitService.appendActivityLogRow(
                repoPath = course.studentGitRepo,
                section = course.section,
                labId = labId,
                assignmentId = problemSlug,
                studentId = studentEmail,
                sessionId = sessionId,
                csvRow = row,
            )
        }.onFailure { log.error("appendActivityLogRow failed: {}", it.message) }
    }

    fun commitSession(
        courseId: String,
        labId: String,
        studentEmail: String,
        sessionId: String,
        problemSlug: String,
    ) {
        val course = courseRepository.findById(courseId).orElse(null) ?: run {
            log.warn("commitSession: course not found courseId={}", courseId)
            return
        }
        runCatching {
            gitService.commitActivityLog(
                repoPath = course.studentGitRepo,
                section = course.section,
                labId = labId,
                assignmentId = problemSlug,
                studentId = studentEmail,
                sessionId = sessionId,
                authorEmail = studentEmail,
            )
        }.onFailure { log.error("commitActivityLog failed: {}", it.message) }
        log.info("activity committed user={} session={} problem={}", studentEmail, sessionId, problemSlug)
    }
}
