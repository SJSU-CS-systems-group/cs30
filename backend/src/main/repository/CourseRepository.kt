package com.cs30.server.repository

import com.cs30.server.models.Course
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface CourseRepository : JpaRepository<Course, String> {
    fun findByCodeAndYearAndSemester(code: String, year: Int, semester: String): List<Course>
    fun findByCodeAndYearAndSemesterAndSection(code: String, year: Int, semester: String, section: Int): Course?

    @Query("SELECT c FROM Course c JOIN c.students s WHERE s = :email")
    fun findByStudentEmail(email: String): List<Course>
}