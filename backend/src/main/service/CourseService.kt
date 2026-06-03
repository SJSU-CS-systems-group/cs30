package com.cs30.server.service

import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

@Service
class CourseService(
    private val courseRepository: CourseRepository,
    private val gitService: GitService,
) {
    @Transactional
    open fun createCourseWithStudents(
        courseName: String,
        courseSection: Int,
        year: Int,
        semester: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        days: Set<DayOfWeek>,
        startTime: LocalTime?,
        endTime: LocalTime?,
        problemsUrl: String,
        language: String,
        students: List<String>
    ): String {
        val repoPath = gitService.initRepository(courseName, year, semester)

        val course = Course(
            code = courseName,
            section = courseSection,
            year = year,
            semester = semester,
            startDate = startDate,
            endDate = endDate,
            days = days.toMutableSet(),
            startTime = startTime,
            endTime = endTime,
            githubProblemsUrl = problemsUrl,
            language = language,
            studentGitRepo = repoPath
        )

        for (email in students) {
            course.students.add(email)
        }
        courseRepository.save(course)
        return repoPath
    }

    @Transactional
    open fun updateCourseWithStudents(
        courseId: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        days: Set<DayOfWeek>,
        startTime: LocalTime?,
        endTime: LocalTime?,
        problemsUrl: String,
        language: String,
        students: List<String>,
    ) {
        val course = courseRepository.findById(courseId).orElseThrow()

        course.startDate = startDate
        course.endDate = endDate
        course.startTime = startTime
        course.endTime = endTime
        course.githubProblemsUrl = problemsUrl
        course.language = language
        course.days.clear()
        course.days.addAll(days)

        val oldStudents = course.students.toMutableList()
        course.students.clear()

        for (email in students) {
            if (!oldStudents.contains(email)) {
                println("  Added student to course: $email")
            }
            oldStudents.remove(email)
            course.students.add(email)
        }

        for (email in oldStudents) {
            println("  Removed student from course: $email")
        }
        courseRepository.save(course)
    }

    @Transactional
    open fun addStudentToCourse(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section)"
        if (course.students.contains(email)) {
            return "Student $email is already enrolled in $code (Section $section)"
        }
        course.students.add(email)
        courseRepository.save(course)
        return "Added student $email to course $code (Section $section)"
    }

    @Transactional
    open fun removeStudentFromCourse(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section)"
        if (!course.students.contains(email)) {
            return "Student $email is not enrolled in $code (Section $section)"
        }
        course.students.remove(email)
        courseRepository.save(course)
        return "Removed student $email from course $code (Section $section)"
    }

    @Transactional
    open fun removeCourse(code: String, year: Int, semester: String, section: String): List<String> {
        val results = mutableListOf<String>()
        val courses: List<Course> = if (section.equals("all", ignoreCase = true)) {
            courseRepository.findByCodeAndYearAndSemester(code, year, semester)
        } else {
            val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt())
            if (course == null) {
                return listOf("Course not found: $code (Section $section)")
            }
            listOf(course)
        }

        for (course in courses) {
            if (course.endDate.isAfter(LocalDateTime.now())) {
                results.add("Cannot delete course ${course.code} (Section ${course.section}) because it has not ended yet")
            } else {
                courseRepository.delete(course)
                results.add("Deleted course ${course.code} (Section ${course.section})")
            }
        }
        return results
    }

    @Transactional
    open fun findCourse(code: String, year: Int, semester: String, section: String): List<String> {
        val results = mutableListOf<String>()
        val courses: List<Course> = if (section.equals("all", ignoreCase = true)) {
            val temp = courseRepository.findByCodeAndYearAndSemester(code, year, semester)
            if (temp.isEmpty()) {
                return listOf("ERROR: No courses found for code: $code, year: $year, semester: $semester")
            }
            temp
        } else {
            val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt())
            if (course == null) {
                return listOf("ERROR: Course not found: $code (Section $section)")
            }
            listOf(course)
        }

        for (course in courses) {
            results.add("Course: ${course.code} (Section ${course.section})")
            results.add("  Year: ${course.year}")
            results.add("  Semester: ${course.semester}")
            results.add("  Start Date: ${course.startDate.toLocalDate()}")
            results.add("  End Date: ${course.endDate.toLocalDate()}")
            results.add("  GitHub Problems URL: ${course.githubProblemsUrl}")
            results.add("  Student Git Repository: ${course.studentGitRepo}")
            results.add("  Students enrolled: ${course.students.size}")
            for (email in course.students) {
                results.add("    - $email")
            }
        }
        return results
    }

    @Transactional
    open fun findStudent(email: String): List<String> {
        val courses = courseRepository.findByStudentEmail(email)
        if (courses.isEmpty()) {
            return listOf("ERROR: No courses found for student: $email")
        }
        val results = mutableListOf<String>()
        results.add("Student: $email")
        results.add("Enrolled in ${courses.size} course(s):")
        for (course in courses) {
            results.add("  - ${course.code} (Section ${course.section})")
        }
        return results
    }
}