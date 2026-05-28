package com.cs30.server.repository

import com.cs30.server.models.Course
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CourseRepository : JpaRepository<Course, String> {
    fun findByName(name: String): List<Course>
    fun findByNameAndSection(name: String, section: Int): Course?
}