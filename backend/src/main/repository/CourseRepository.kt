package com.cs30.server.repository

import com.cs30.server.models.Course
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CourseRepository : JpaRepository<Course, String> {
    fun findByCode(code: String): List<Course>
    fun findByCodeAndSection(code: String, section: Int): Course?
}