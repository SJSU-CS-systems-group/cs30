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

@Serializable
data class TaStudentInfo(
    val email: String,
    val hasActiveSession: Boolean,
    val platform: String? = null,
    val lastHeartbeatAt: String? = null
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
data class TaCheckSessionResponse(
    val hasActiveSession: Boolean,
    val email: String? = null,
    val courses: List<TaCourseInfo> = emptyList()
)
