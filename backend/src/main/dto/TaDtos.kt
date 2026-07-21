package com.cs30.server.dto

import data.TaStudentStatus
import java.time.LocalDateTime

data class TaSectionInfo(
    val courseId: String,
    val courseCode: String,
    val section: Int,
    val year: Int,
    val semester: String,
    val students: List<TaStudentInfo>
)

data class TaStudentInfo(
    val email: String,
    val status: TaStudentStatus,
    val token: String?, // for kicking active sessions
    val lastLoginAt: LocalDateTime?,
    val lastLogoutAt: LocalDateTime?,
    val ipAddress: String?,
    val platform: String?,
    val violationCount: Int = 0,
    val hasFocus: Boolean = true // whether the student's window currently has focus
)

data class TaSessionInfo(
    val token: String,
    val studentEmail: String,
    val platform: String,
    val ipAddress: String,
    val loggedInAt: LocalDateTime,
    val lastHeartbeatAt: LocalDateTime
)

data class TaActivityEntry(
    val studentEmail: String,
    val timestamp: LocalDateTime,
    val kind: String,
    val problem: String?,
    val detail: String?
)

data class TaDashboardStats(
    val totalStudents: Int,
    val activeStudents: Int,
    val recentViolations: Int
)

data class TaLabInfo(
    val labId: String,
    val courseId: String,
    val labNumber: Int,
    val courseCode: String,
    val section: Int,
    val isActive: Boolean,
    val isPast: Boolean,
    val startDateTime: String,
    val endDateTime: String
)

data class TaCourseInfo(
    val courseId: String,
    val code: String,
    val section: Int
)

data class TaCheckSessionResponse(
    val hasActiveSession: Boolean,
    val email: String?,
    val courses: List<TaCourseInfo>
)

data class TaActivityLogEntry(
    val timestampMs: Long,
    val timestampIso: String,
    val platform: String,
    val problem: String,
    val eventKind: String,
    val detail: String?,
    val severity: String // "ALERT" or "INFO"
)
