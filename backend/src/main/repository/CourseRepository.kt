package com.cs30.server.repository

import com.cs30.server.models.Course
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface CourseRepository : JpaRepository<Course, String> {
    fun findByCodeAndYearAndSemester(code: String, year: Int, semester: String): List<Course>
    fun findByCodeAndYearAndSemesterAndSection(code: String, year: Int, semester: String, section: Int): Course?

    /** Courses that haven't ended yet (ongoing or not yet started) — suggested alternatives when a course lookup misses. */
    fun findByEndDateAfter(now: LocalDateTime): List<Course>

    @Query("SELECT c FROM Course c JOIN c.students s WHERE s = :email")
    fun findByStudentEmail(email: String): List<Course>

    fun existsByIdAndStudentsContaining(id: String, email: String): Boolean

    @Query("SELECT DISTINCT c FROM Course c LEFT JOIN FETCH c.students WHERE c.taEmail = :email")
    fun findByTaEmail(email: String): List<Course>

    /**
     * Every course, with students eagerly fetched. Same shape as findByTaEmail minus the filter:
     * TA-dashboard callers read course.students outside the persistence session, so a plain
     * findAll() would risk lazy-initialization failures there.
     */
    @Query("SELECT DISTINCT c FROM Course c LEFT JOIN FETCH c.students")
    fun findAllWithStudents(): List<Course>
}