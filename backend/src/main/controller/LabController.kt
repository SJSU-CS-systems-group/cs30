package com.cs30.server.controller

import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseAccessService
import com.cs30.server.service.StudentIdentityService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.time.ZoneOffset

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

/** `remainingMs` is null when there is no countdown to show — the course's TA is not held to the lab window. */
data class LabRemainingResponse(val remainingMs: Long?)

@RestController
@RequestMapping("/api/labs")
class LabController(
    private val courseRepository: CourseRepository,
    private val identityService: StudentIdentityService,
    private val appTimeZoneService: AppTimeZoneService,
    private val courseAccess: CourseAccessService,
) {
    /**
     * Get all valid (currently active) labs for the authenticated student — or every lab of the
     * course for its TA, who may do any lab at any time.
     * A lab is valid if the current time is between startDateTime and endDateTime.
     */
    @GetMapping("/student")
    fun getValidLabsForStudent(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<LabResponse>> {
        val email = identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val courses = courseAccess.coursesFor(email)

        if (courses.isEmpty()) {
            return ResponseEntity.notFound().build()
        }

        val validLabs = courses.flatMap { course ->
            courseAccess.visibleLabs(course, email)
                .map { lab ->
                    LabResponse(
                        courseCode = course.code,
                        courseId = course.id,
                        section = course.section,
                        year = course.year,
                        semester = course.semester,
                        labNumber = lab.labNumber,
                        startDateTime = appTimeZoneService.toAppZone(lab.startDateTime),
                        endDateTime = appTimeZoneService.toAppZone(lab.endDateTime),
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
        val courses = courseAccess.coursesFor(email)

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
        val email = identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        // A missing course/lab is a genuinely different situation from "the lab legitimately
        // ended" — a 200 with remainingMs=0 made the two indistinguishable to the caller (a bad
        // courseId, or a lab deleted mid-session, would read to the student as "time's up" rather
        // than "something's wrong"). getJson (frontend) already throws on non-2xx, and its only
        // caller (LabTimeService) already treats any exception as "no countdown to show" —
        // returning a real 404 here doesn't change frontend behavior, just makes it accurate.
        val course = courseRepository.findById(courseId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val lab = course.labs.find { it.labNumber == labNumber }
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        // The TA is not held to the window, so there is no countdown to show them — a past lab
        // would otherwise read as "Time's up" in the editor while Run/Submit keep working.
        if (courseAccess.isTa(course, email)) {
            return ResponseEntity.ok(LabRemainingResponse(remainingMs = null))
        }
        val remaining = java.time.Duration.between(LocalDateTime.now(ZoneOffset.UTC), lab.endDateTime).toMillis()
        return ResponseEntity.ok(LabRemainingResponse(remainingMs = remaining.coerceAtLeast(0L)))
    }
}
