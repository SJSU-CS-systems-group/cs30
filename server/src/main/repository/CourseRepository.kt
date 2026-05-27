package com.cs30.server.repository

import com.cs30.server.models.Student
import com.cs30.server.models.Course
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CourseRepository : JpaRepository<Course, String> {
    fun findByEmail(email: String): Student?
    fun findByName(firstName: String, lastName: String): List<Student>
    fun findAllCourse(name: String): List<Course>
    fun findCourse(name: String, section: int): Course?

    courseRepository.save(course)
}