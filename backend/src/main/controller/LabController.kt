package com.cs30.server.controller

import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.StudentIdentityService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

data class LabResponse(
    val courseCode: String,
    val courseId: String,
    val section: Int,
    val year: Int,
    val semester: String,
    val labNumber: Int,
    val startDateTime: LocalDateTime,
    val endDateTime: LocalDateTime,
    val problemGitRepo: String
)

data class LabRemainingResponse(val remainingMs: Long)

@RestController
@RequestMapping("/api/labs")
class LabController(
    private val courseRepository: CourseRepository,
    private val identityService: StudentIdentityService
) {
    /**
     * Get all valid (currently active) labs for the authenticated student.
     * A lab is valid if the current time is between startDateTime and endDateTime.
     */
    @GetMapping("/student")
    fun getValidLabsForStudent(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<LabResponse>> {
        val email = identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val courses = courseRepository.findByStudentEmail(email)

        if (courses.isEmpty()) {
            return ResponseEntity.notFound().build()
        }

        val validLabs = courses.flatMap { course ->
            course.labs
                .filter { lab -> lab.isActive }
                .map { lab ->
                    LabResponse(
                        courseCode = course.code,
                        courseId = course.id,
                        section = course.section,
                        year = course.year,
                        semester = course.semester,
                        labNumber = lab.labNumber,
                        startDateTime = lab.startDateTime,
                        endDateTime = lab.endDateTime,
                        problemGitRepo = course.problemGitRepo
                    )
                }
        }

        return ResponseEntity.ok(validLabs)
    }

    /**
     * Get all labs (past, current, and future) for the authenticated student.
     */
    @GetMapping("/student/all")
    fun getAllLabsForStudent(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<LabResponse>> {
        val email = identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val courses = courseRepository.findByStudentEmail(email)

        if (courses.isEmpty()) {
            return ResponseEntity.notFound().build()
        }

        val allLabs = courses.flatMap { course ->
            course.labs.map { lab ->
                LabResponse(
                    courseCode = course.code,
                    courseId = course.id,
                    section = course.section,
                    year = course.year,
                    semester = course.semester,
                    labNumber = lab.labNumber,
                    startDateTime = lab.startDateTime,
                    endDateTime = lab.endDateTime,
                    problemGitRepo = course.problemGitRepo
                )
            }
        }

        return ResponseEntity.ok(allLabs)
    }

    @GetMapping("/{courseId}/lab/{labNumber}/remaining")
    fun getRemainingForLab(
        @PathVariable courseId: String,
        @PathVariable labNumber: Int,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<LabRemainingResponse> {
        identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val course = courseRepository.findById(courseId).orElse(null)
            ?: return ResponseEntity.ok(LabRemainingResponse(remainingMs = 0L))
        val lab = course.labs.find { it.labNumber == labNumber }
            ?: return ResponseEntity.ok(LabRemainingResponse(remainingMs = 0L))
        val remaining = java.time.Duration.between(LocalDateTime.now(), lab.endDateTime).toMillis()
        return ResponseEntity.ok(LabRemainingResponse(remainingMs = remaining.coerceAtLeast(0L)))
    }
}
