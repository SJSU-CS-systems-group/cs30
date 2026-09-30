package com.cs30.server.repository

import com.cs30.server.dto.CourseRef
import com.cs30.server.dto.toRef
import com.cs30.server.models.Course
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
interface CourseRepository : JpaRepository<Course, String> {
    fun findByCodeAndYearAndSemester(code: String, year: Int, semester: String): List<Course>
    fun findByCodeAndYearAndSemesterAndSection(code: String, year: Int, semester: String, section: Int): Course?

    /** Courses that haven't ended yet (ongoing or not yet started) — suggested alternatives when a course lookup misses. */
    fun findByEndDateAfter(now: LocalDateTime): List<Course>

    @Query("SELECT c FROM Course c JOIN c.students s WHERE s = :email")
    fun findByStudentEmail(email: String): List<Course>

    fun existsByIdAndStudentsContaining(id: String, email: String): Boolean

    /**
     * Courses this email is a TA of. The TA match is a subquery, not a second JOIN FETCH: fetching
     * two collections in one query multiplies the rows against c.students. Compared lowercased, so
     * it agrees with CourseAccessService.isTa; a stored "TA@x" must match a login of "ta@x".
     */
    @Query(
        "SELECT DISTINCT c FROM Course c LEFT JOIN FETCH c.students " +
            "WHERE EXISTS (SELECT t FROM c.taEmails t WHERE LOWER(t) = LOWER(:email))"
    )
    fun findByTaEmail(email: String): List<Course>

    /**
     * Every course, with students eagerly fetched. Same shape as findByTaEmail minus the filter:
     * TA-dashboard callers read course.students outside the persistence session, so a plain
     * findAll() would risk lazy-initialization failures there.
     */
    @Query("SELECT DISTINCT c FROM Course c LEFT JOIN FETCH c.students")
    fun findAllWithStudents(): List<Course>
}

/**
 * The courses that have not ended yet, in the order every listing uses. Refs rather than text, so
 * each caller words its own message: CourseService suggests them when a lookup misses, and the
 * Canvas endpoints hand them to the CLI for the same purpose.
 */
fun CourseRepository.activeCourses(): List<CourseRef> =
    findByEndDateAfter(LocalDateTime.now(ZoneOffset.UTC)).map { it.toRef() }.sorted()
