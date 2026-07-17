package com.cs30.server.controller

import com.cs30.server.dto.*
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.LoginSessionRepository
import com.cs30.server.service.ApiTokenStore
import com.cs30.server.service.GitService
import com.cs30.server.service.TaIdentityService
import data.TaStudentStatus
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * API endpoints for the TA Dashboard.
 * All endpoints require a valid TA token (platform starts with "ta-").
 */
@RestController
@RequestMapping("/api/ta")
class TaController(
    private val taIdentityService: TaIdentityService,
    private val courseRepository: CourseRepository,
    private val loginSessionRepository: LoginSessionRepository,
    private val tokenStore: ApiTokenStore,
    private val gitService: GitService,
) {
    private val log = LoggerFactory.getLogger(TaController::class.java)

    /**
     * Get all sections where this TA is assigned, with student lists and session status.
     */
    @GetMapping("/sections")
    fun getSections(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<TaSectionInfo>> {
        val taEmail = taIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val courses = taIdentityService.getCoursesForTa(taEmail)
        if (courses.isEmpty()) {
            return ResponseEntity.ok(emptyList())
        }

        // Get all students from TA's courses
        val allStudents = courses.flatMap { it.students }.toSet()

        // Get all sessions for these students (most recent first)
        val allSessions = if (allStudents.isNotEmpty()) {
            loginSessionRepository.findByStudentEmailInOrderByLoggedInAtDesc(allStudents)
        } else {
            emptyList()
        }

        // Group sessions by email
        val sessionsByEmail = allSessions.groupBy { it.studentEmail }

        // Most recent session per student (for login time, IP, platform)
        val mostRecentSessions = sessionsByEmail.mapValues { it.value.first() }

        // Get active sessions separately
        val activeSessions = allSessions.filter { it.loggedOutAt == null }
            .associateBy { it.studentEmail }

        // Most recent COMPLETED session per student (for last logout time from previous session)
        val lastCompletedSessions = sessionsByEmail.mapValues { (_, sessions) ->
            sessions.firstOrNull { it.loggedOutAt != null }
        }

        val sections = courses.map { course ->
            // Get violation counts and focus status for this section
            val violationCounts: Map<String, Int>
            val focusStatusByEmail: Map<String, Boolean>
            if (course.studentGitRepo.isNotBlank()) {
                violationCounts = gitService.countViolationsForSection(course.studentGitRepo, course.section)
                focusStatusByEmail = gitService.getFocusStatusForSection(course.studentGitRepo, course.section)
            } else {
                violationCounts = emptyMap()
                focusStatusByEmail = emptyMap()
            }

            TaSectionInfo(
                courseId = course.id,
                courseCode = course.code,
                section = course.section,
                year = course.year,
                semester = course.semester,
                students = course.students.map { studentEmail ->
                    val activeSession = activeSessions[studentEmail]
                    val mostRecent = mostRecentSessions[studentEmail]
                    val lastCompleted = lastCompletedSessions[studentEmail]
                    TaStudentInfo(
                        email = studentEmail,
                        status = if (activeSession != null) TaStudentStatus.Active else TaStudentStatus.Offline,
                        token = activeSession?.token,
                        lastLoginAt = mostRecent?.loggedInAt,
                        lastLogoutAt = lastCompleted?.loggedOutAt, // Show logout from previous completed session
                        ipAddress = activeSession?.ipAddress ?: mostRecent?.ipAddress,
                        platform = activeSession?.platform ?: mostRecent?.platform,
                        violationCount = violationCounts[studentEmail] ?: 0,
                        // Not logged in -> no focus to hold. Logged in with no focus event yet -> assume focused.
                        hasFocus = if (activeSession != null) focusStatusByEmail[studentEmail] ?: true else false
                    )
                }.sortedWith(compareBy({ -(it.violationCount) }, { it.status != TaStudentStatus.Active }, { it.email })) // Violations desc, then active first, then by email
            )
        }

        return ResponseEntity.ok(sections)
    }

    /**
     * Get all active student sessions in TA's sections.
     */
    @GetMapping("/sessions")
    fun getActiveSessions(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<TaSessionInfo>> {
        val taEmail = taIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val courses = taIdentityService.getCoursesForTa(taEmail)
        val allStudents = courses.flatMap { it.students }.toSet()

        if (allStudents.isEmpty()) {
            return ResponseEntity.ok(emptyList())
        }

        val sessions = loginSessionRepository.findByStudentEmailInAndLoggedOutAtIsNull(allStudents)
            .filter { !it.platform.startsWith("ta-") } // Exclude TA sessions
            .map { session ->
                TaSessionInfo(
                    token = session.token,
                    studentEmail = session.studentEmail,
                    platform = session.platform,
                    ipAddress = session.ipAddress,
                    loggedInAt = session.loggedInAt,
                    lastHeartbeatAt = session.lastHeartbeatAt
                )
            }
            .sortedByDescending { it.lastHeartbeatAt }

        return ResponseEntity.ok(sessions)
    }

    /**
     * Kick a student by revoking their session token.
     * Only allows kicking students in TA's sections.
     */
    @DeleteMapping("/sessions/{token}")
    fun kickStudent(
        @PathVariable token: String,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<Map<String, Any>> {
        val taEmail = taIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        // Verify the session exists and belongs to a student in TA's sections
        val session = loginSessionRepository.findById(token).orElse(null)
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Session not found"))
        }

        // Check if student is in TA's sections
        val courses = taIdentityService.getCoursesForTa(taEmail)
        val allStudents = courses.flatMap { it.students }.toSet()

        if (session.studentEmail !in allStudents) {
            log.warn("[ta-kick] TA $taEmail attempted to kick ${session.studentEmail} who is not in their sections")
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Student not in your sections"))
        }

        // Don't allow kicking TA sessions
        if (session.platform.startsWith("ta-")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "Cannot kick TA sessions"))
        }

        log.info("[ta-kick] TA $taEmail kicking student ${session.studentEmail}")
        tokenStore.revokeByToken(token)

        return ResponseEntity.ok(mapOf(
            "success" to true,
            "message" to "Session revoked for ${session.studentEmail}"
        ))
    }

    /**
     * Get dashboard statistics for TA's sections.
     */
    @GetMapping("/stats")
    fun getStats(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<TaDashboardStats> {
        val taEmail = taIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val courses = taIdentityService.getCoursesForTa(taEmail)
        val allStudents = courses.flatMap { it.students }.toSet()

        val activeSessions = if (allStudents.isNotEmpty()) {
            loginSessionRepository.findByStudentEmailInAndLoggedOutAtIsNull(allStudents)
                .filter { !it.platform.startsWith("ta-") }
        } else {
            emptyList()
        }

        return ResponseEntity.ok(TaDashboardStats(
            totalStudents = allStudents.size,
            activeStudents = activeSessions.size,
            recentViolations = 0 // TODO: Implement when activity log reading is added
        ))
    }

    /**
     * Get all labs for TA's courses with their active status.
     */
    @GetMapping("/labs")
    fun getLabs(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<TaLabInfo>> {
        val taEmail = taIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val courses = taIdentityService.getCoursesForTa(taEmail)
        val labs = courses.flatMap { course ->
            course.labs.map { lab ->
                TaLabInfo(
                    labId = lab.id,
                    labNumber = lab.labNumber,
                    courseCode = course.code,
                    section = course.section,
                    isActive = lab.isActive,
                    startDateTime = lab.startDateTime.toString(),
                    endDateTime = lab.endDateTime.toString()
                )
            }
        }.sortedWith(compareBy({ !it.isActive }, { it.labNumber })) // Active labs first, then by number

        return ResponseEntity.ok(labs)
    }

    /**
     * Get active student sessions for a specific lab's course.
     * Returns students enrolled in the course with their session info.
     */
    @GetMapping("/labs/{labId}/students")
    fun getLabStudents(
        @PathVariable labId: String,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<TaSessionInfo>> {
        val taEmail = taIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val courses = taIdentityService.getCoursesForTa(taEmail)
        val lab = courses.flatMap { it.labs }.find { it.id == labId }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()

        val course = lab.course ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val students = course.students

        if (students.isEmpty()) {
            return ResponseEntity.ok(emptyList())
        }

        val sessions = loginSessionRepository.findByStudentEmailInAndLoggedOutAtIsNull(students)
            .filter { !it.platform.startsWith("ta-") }
            .map { session ->
                TaSessionInfo(
                    token = session.token,
                    studentEmail = session.studentEmail,
                    platform = session.platform,
                    ipAddress = session.ipAddress,
                    loggedInAt = session.loggedInAt,
                    lastHeartbeatAt = session.lastHeartbeatAt
                )
            }
            .sortedBy { it.studentEmail }

        return ResponseEntity.ok(sessions)
    }

    /**
     * Get activity log for a specific student (today's entries).
     * Only allows viewing logs for students in TA's sections.
     */
    @GetMapping("/activity/{courseId}/{studentEmail}")
    fun getStudentActivityLog(
        @PathVariable courseId: String,
        @PathVariable studentEmail: String,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<TaActivityLogEntry>> {
        val taEmail = taIdentityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        // Verify the course belongs to this TA
        val courses = taIdentityService.getCoursesForTa(taEmail)
        val course = courses.find { it.id == courseId }
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        // Verify the student is enrolled in this course
        if (studentEmail !in course.students) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        if (course.studentGitRepo.isBlank()) {
            return ResponseEntity.ok(emptyList())
        }

        val entries = gitService.getActivityLogForStudent(course.studentGitRepo, course.section, studentEmail)
            .map { entry ->
                TaActivityLogEntry(
                    timestampMs = entry.timestampMs,
                    timestampIso = entry.timestampIso,
                    platform = entry.platform,
                    problem = entry.problem,
                    eventKind = entry.eventKind,
                    detail = entry.detail,
                    severity = entry.severity
                )
            }

        return ResponseEntity.ok(entries)
    }
}
