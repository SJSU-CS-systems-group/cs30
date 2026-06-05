package com.cs30.server.controller

import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
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

@RestController
@RequestMapping("/api/labs")
class LabController(
    private val courseRepository: CourseRepository
) {

    /**
     * Get all valid (currently active) labs for a student by email.
     * A lab is valid if the current time is between startDateTime and endDateTime.
     */
    @GetMapping("/student/{email}")
    fun getValidLabsForStudent(@PathVariable email: String): ResponseEntity<List<LabResponse>> {
        val now = LocalDateTime.now()
        val courses = courseRepository.findByStudentEmail(email)

        if (courses.isEmpty()) {
            return ResponseEntity.notFound().build()
        }

        val validLabs = courses.flatMap { course ->
            course.labs
                .filter { lab -> now.isAfter(lab.startDateTime) && now.isBefore(lab.endDateTime) }
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
     * Get all labs (past, current, and future) for a student by email.
     */
    @GetMapping("/student/{email}/all")
    fun getAllLabsForStudent(@PathVariable email: String): ResponseEntity<List<LabResponse>> {
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
}