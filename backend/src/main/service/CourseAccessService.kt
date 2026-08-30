package com.cs30.server.service

import com.cs30.server.models.Course
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Who may use the student app for a course, and when they may touch a given lab.
 *
 * Two kinds of member: an enrolled student (in `course_students`) and the course's TA
 * (`Course.taEmail`). A student is held to the lab window; the TA is not — they may open, run,
 * submit and autosave against any lab of their course at any time, before it opens or after it
 * closes, so they can try a lab as a student would (issue #137). Role is derived per request from
 * the Course row, the same way every other role check in the app works — nothing is stored on the
 * session.
 *
 * TA work is judged and saved under the TA's own email exactly like a student's, but never
 * reaches Canvas: [CanvasSyncService] builds its roster from `course.students` minus `taEmail`.
 */
@Service
class CourseAccessService(private val courseRepository: CourseRepository) {

    /** Courses this email may use the student app for: enrolled as a student, or assigned as the TA. */
    fun coursesFor(email: String): List<Course> =
        // Course.equals is by id, so distinct() dedups a TA who is also on the roster.
        (courseRepository.findByStudentEmail(email) + courseRepository.findByTaEmail(email)).distinct()

    fun isTa(course: Course, email: String): Boolean =
        course.taEmail?.equals(email, ignoreCase = true) == true

    /** Enrolled student or the course's TA. The in-memory check goes first so the TA costs no DB round-trip. */
    fun isMember(course: Course, email: String): Boolean =
        isTa(course, email) || courseRepository.existsByIdAndStudentsContaining(course.id, email)

    /** Students only inside the lab window; the TA any time (past, current, future). */
    fun canAccessLab(course: Course, lab: ScheduledLab, email: String): Boolean =
        lab.isActive || isTa(course, email)

    /** Labs whose problems this email may see now: active labs for a student, every lab for the TA. */
    fun visibleLabs(course: Course, email: String): List<ScheduledLab> =
        if (isTa(course, email)) course.labs else course.labs.filter { it.isActive }

    /**
     * Why this email may not run/submit/autosave against this lab right now, or null if they may.
     * The messages are shown to the student verbatim.
     */
    fun labDenialReason(course: Course, labNumber: Int, email: String): String? {
        val lab = course.labs.find { it.labNumber == labNumber }
            ?: return "Lab $labNumber not found"
        if (canAccessLab(course, lab, email)) return null
        val now = LocalDateTime.now(ZoneOffset.UTC)
        return if (now.isBefore(lab.startDateTime)) "Lab has not started yet" else "Lab deadline has passed"
    }
}
