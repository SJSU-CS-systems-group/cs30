package data

import kotlinx.serialization.Serializable

@Serializable
data class TaUser(
    val email: String,
    val name: String
)

@Serializable
data class TaCourseInfo(
    val courseId: String,
    val code: String,
    val section: Int
)

@Serializable
data class TaSectionInfo(
    val courseId: String,
    val courseCode: String,
    val section: Int,
    val year: Int,
    val semester: String,
    val students: List<TaStudentInfo>
)

enum class TaStudentStatus { Active, Offline }

@Serializable
data class TaStudentInfo(
    val email: String,
    val status: TaStudentStatus,
    val token: String? = null,
    val lastLoginAt: String? = null,
    val lastLogoutAt: String? = null,
    val ipAddress: String? = null,
    val platform: String? = null,
    val violationCount: Int = 0,
    val hasFocus: Boolean = true // whether the student's window currently has focus
)

@Serializable
data class TaSessionInfo(
    val token: String,
    val studentEmail: String,
    val platform: String,
    val ipAddress: String,
    val loggedInAt: String,
    val lastHeartbeatAt: String
)

@Serializable
data class TaDashboardStats(
    val totalStudents: Int,
    val activeStudents: Int,
    val recentViolations: Int
)

@Serializable
data class TaLabInfo(
    val labId: String,
    val labNumber: Int,
    val courseCode: String,
    val section: Int,
    val isActive: Boolean,
    val startDateTime: String,
    val endDateTime: String
)

@Serializable
data class TaCheckSessionResponse(
    val hasActiveSession: Boolean,
    val email: String? = null,
    val courses: List<TaCourseInfo> = emptyList()
)

@Serializable
data class TaActivityLogEntry(
    val timestampMs: Long,
    val timestampIso: String,
    val platform: String,
    val problem: String,
    val eventKind: String,
    val detail: String? = null,
    val severity: String // "ALERT" or "INFO"
)
