package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class SaveCodeRequest(
    val courseId: String,
    val section: Int,
    val studentId: String,
    val studentEmail: String,
    val labId: String,
    val assignmentId: String,
    val code: String,
    val saveType: SaveType = SaveType.AUTOSAVE
)

enum class SaveType {
    AUTOSAVE,
    SUBMISSION
}

data class SaveCodeResponse(
    val success: Boolean,
    val message: String,
    val filePath: String? = null
)

@Service
open class CodeService(
    private val courseRepository: CourseRepository,
    private val gitService: GitService
) {

    fun saveCode(request: SaveCodeRequest): SaveCodeResponse {
        // Look up the course
        val course = courseRepository.findById(request.courseId).orElse(null)
            ?: return SaveCodeResponse(false, "Course not found: ${request.courseId}")

        // Verify student is enrolled
        if (!course.students.contains(request.studentEmail)) {
            return SaveCodeResponse(false, "Student ${request.studentEmail} is not enrolled in this course")
        }

        // Get the repo path
        val repoPath = course.studentGitRepo
        if (repoPath.isBlank()) {
            return SaveCodeResponse(false, "Course does not have a Git repository configured")
        }

        // Determine file extension based on course language
        val extension = when (course.language.lowercase()) {
            "java" -> "java"
            "kotlin" -> "kt"
            "python" -> "py"
            "c" -> "c"
            "c++" -> "cpp"
            "javascript" -> "js"
            else -> "txt"
        }

        return try {
            val filePath = gitService.saveAndCommit(
                repoPath = repoPath,
                section = request.section,
                labId = request.labId,
                assignmentId = request.assignmentId,
                studentId = request.studentEmail,
                code = request.code,
                extension = extension,
                saveType = request.saveType
            )
            SaveCodeResponse(true, "Code saved successfully", filePath)
        } catch (e: Exception) {
            SaveCodeResponse(false, "Failed to save code: ${e.message}")
        }
    }
}