package com.cs30.server.service

import com.cs30.server.models.Course
import com.cs30.server.models.Student
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.StudentRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

@Service
open class CourseService(
    private val courseRepository: CourseRepository,
    private val studentRepository: StudentRepository
) {
    @Transactional
    open fun createCourseWithStudents(
        courseName: String,
        courseSection: Int,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        days: Set<DayOfWeek>,
        startTime: LocalTime?,
        endTime: LocalTime?,
        problemsUrl: String,
        submissionsUrl: String,
        students: List<Pair<String, String>>
    ): Course {
        val course = Course(
            code = courseName,
            section = courseSection,
            startDate = startDate,
            endDate = endDate,
            days = days.toMutableSet(),
            startTime = startTime,
            endTime = endTime,
            githubProblemsUrl = problemsUrl,
            githubSubmissionsUrl = submissionsUrl
        )

        for ((fullName, email) in students) {
            val nameParts = fullName.split(" ", limit = 2)
            val firstName = nameParts.getOrElse(0) { "" }
            val lastName = nameParts.getOrElse(1) { "" }

            var student = studentRepository.findByEmail(email)
            if (student == null) {
                student = Student(email = email, firstName = firstName, lastName = lastName)
                student = studentRepository.saveAndFlush(student)
                println("  Created student: $firstName $lastName ($email)")
            }
            course.students.add(student)
        }

        return courseRepository.save(course)
    }
}