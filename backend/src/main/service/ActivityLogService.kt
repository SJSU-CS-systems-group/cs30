package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.LockdownViolation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDateTime

@Service
class ActivityLogService(
    private val gitService: GitService,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(ActivityLogService::class.java)

    fun recordEvent(
        studentEmail: String,
        sessionId: String,
        problemSlug: String,
        violation: LockdownViolation,
        platform: String,
    ) {
        val now = LocalDateTime.now()
        val (course, activeLab) = courseRepository.findByStudentEmail(studentEmail)
            .flatMap { c -> c.labs.map { lab -> c to lab } }
            .firstOrNull { (_, lab) -> now.isAfter(lab.startDateTime) && now.isBefore(lab.endDateTime) }
            ?: run {
                log.warn("recordEvent: {} has no active lab right now", studentEmail)
                return
            }
        val labId = "lab-${activeLab.labNumber}"
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
        studentEmail: String,
        sessionId: String,
        problemSlug: String,
    ) {
        val now = LocalDateTime.now()
        val (course, activeLab) = courseRepository.findByStudentEmail(studentEmail)
            .flatMap { c -> c.labs.map { lab -> c to lab } }
            .firstOrNull { (_, lab) -> now.isAfter(lab.startDateTime) && now.isBefore(lab.endDateTime) }
            ?: run {
                log.warn("commitSession: {} has no active lab right now", studentEmail)
                return
            }
        val labId = "lab-${activeLab.labNumber}"
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
