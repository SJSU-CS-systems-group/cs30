package com.cs30.server.service

import com.cs30.server.models.Course
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class CourseService(
    private val courseRepository: CourseRepository,
) {
    @Transactional
    open fun createCourseWithStudents(
        courseName: String,
        courseSection: Int,
        year: Int,
        semester: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        studentGitRepo: String,
        problemGitRepo: String,
        language: String,
        taEmail: String?,
        students: List<String>,
        labs: List<ScheduledLab>
    ) {
        val course = Course(
            code = courseName,
            section = courseSection,
            year = year,
            semester = semester,
            startDate = startDate,
            endDate = endDate,
            language = language,
            studentGitRepo = studentGitRepo,
            problemGitRepo = problemGitRepo,
            taEmail = taEmail
        )

        for (email in students) {
            course.students.add(email)
        }
        for (lab in labs) {
            course.addLab(lab)
        }
        courseRepository.save(course)
    }

    @Transactional
    open fun updateCourseWithStudents(
        courseId: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        studentGitRepo: String,
        problemGitRepo: String,
        language: String,
        taEmail: String?,
        students: List<String>,
        labs: List<ScheduledLab>
    ) {
        val course = courseRepository.findById(courseId).orElseThrow()

        course.startDate = startDate
        course.endDate = endDate
        course.studentGitRepo = studentGitRepo
        course.problemGitRepo = problemGitRepo
        course.language = language
        course.taEmail = taEmail

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

        // Update labs while preserving problems
        val oldLabsMap = course.labs.associateBy { it.labNumber }
        val newLabNumbers = labs.map { it.labNumber }.toSet()

        // Remove labs that are no longer in the new list
        val labsToRemove = course.labs.filter { it.labNumber !in newLabNumbers }.toList()
        for (oldLab in labsToRemove) {
            if (oldLab.problems.isNotEmpty()) {
                println("  Warning: Lab ${oldLab.labNumber} removed (had ${oldLab.problems.size} problems)")
            }
            course.removeLab(oldLab)
        }

        // Update existing labs or add new ones
        for (newLab in labs) {
            val existingLab = oldLabsMap[newLab.labNumber]
            if (existingLab != null) {
                // Update times on existing lab
                if (existingLab.startDateTime != newLab.startDateTime || existingLab.endDateTime != newLab.endDateTime) {
                    existingLab.startDateTime = newLab.startDateTime
                    existingLab.endDateTime = newLab.endDateTime
                    println("  Updated Lab ${newLab.labNumber} times")
                }
                // Sync problems: add new, update existing, remove deleted
                val existingProblemsByName = existingLab.problems.associateBy { it.name }
                val newProblemNames = newLab.problems.map { it.name }.toSet()

                // Remove problems no longer in the YAML
                val problemsToRemove = existingLab.problems.filter { it.name !in newProblemNames }.toList()
                for (problem in problemsToRemove) {
                    existingLab.removeProblem(problem)
                    println("  Removed problem '${problem.name}' from Lab ${newLab.labNumber}")
                }

                // Add new problems or update language of existing ones
                for (problem in newLab.problems) {
                    val existingProblem = existingProblemsByName[problem.name]
                    if (existingProblem != null) {
                        // Update language if changed
                        if (existingProblem.language != problem.language) {
                            println("  Updated problem '${problem.name}' language: ${existingProblem.language} -> ${problem.language}")
                            existingProblem.language = problem.language
                        }
                        // Update note if changed
                        if (existingProblem.note != problem.note) {
                            println("  Updated problem '${problem.name}' note: ${existingProblem.note} -> ${problem.note}")
                            existingProblem.note = problem.note
                        }
                    } else {
                        existingLab.addProblem(problem)
                        println("  Added problem '${problem.name}' to Lab ${newLab.labNumber}")
                    }
                }
            } else {
                // Add new lab (with its problems)
                course.addLab(newLab)
                println("  Added new Lab ${newLab.labNumber} with ${newLab.problems.size} problem(s)")
            }
        }

        courseRepository.save(course)
    }

    @Transactional
    open fun addStudentToCourse(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section, Semester $semester, Year $year)"
        if (course.students.contains(email)) {
            return "Student $email is already enrolled in $code (Section $section, Semester $semester, Year $year)"
        }
        course.students.add(email)
        courseRepository.save(course)
        return "Added student $email to course $code (Section $section, Semester $semester, Year $year)"
    }

    @Transactional
    open fun removeStudentFromCourse(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section, Semester $semester, Year $year)"
        if (!course.students.contains(email)) {
            return "Student $email is not enrolled in $code (Section $section, Semester $semester, Year $year)"
        }
        course.students.remove(email)
        courseRepository.save(course)
        return "Removed student $email from course $code (Section $section, Semester $semester, Year $year)"
    }

    @Transactional
    open fun removeCourse(code: String, year: Int, semester: String, section: String): List<String> {
        val results = mutableListOf<String>()
        val courses: List<Course> = if (section.equals("all", ignoreCase = true)) {
            courseRepository.findByCodeAndYearAndSemester(code, year, semester)
        } else {
            val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt())
            if (course == null) {
                return listOf("Course not found: $code (Section $section, Semester $semester, Year $year)")
            }
            listOf(course)
        }

        for (course in courses) {
            if (course.endDate.isAfter(LocalDateTime.now())) {
                results.add("Cannot delete course ${course.code} (Section ${course.section}, Semester $semester, Year $year) because it has not ended yet")
            } else {
                courseRepository.delete(course)
                results.add("Deleted course ${course.code} (Section ${course.section}, Semester $semester, Year $year)")
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
                return listOf("ERROR: Course not found: $code (Section $section, Semester $semester, Year $year)")
            }
            listOf(course)
        }

        for (course in courses) {
            results.add("Course: ${course.code} (Section ${course.section})")
            results.add("  Year: ${course.year}")
            results.add("  Semester: ${course.semester}")
            results.add("  Start Date: ${course.startDate.toLocalDate()}")
            results.add("  End Date: ${course.endDate.toLocalDate()}")
            results.add("  Problem Git Repository: ${course.problemGitRepo}")
            results.add("  Student Git Repository: ${course.studentGitRepo}")
            results.add("  TA: ${course.taEmail ?: "(none)"}")
            results.add("  Labs: ${course.labs.size}")
            for (lab in course.labs) {
                results.add("    - Lab ${lab.labNumber}: ${lab.startDateTime} to ${lab.endDateTime}")
            }
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

    @Transactional
    open fun setTA(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section, Semester $semester, Year $year)"
        course.taEmail = email
        courseRepository.save(course)
        return "Set TA $email for course $code (Section $section, Semester $semester, Year $year)"
    }

    @Transactional
    open fun removeTA(code: String, year: Int, semester: String, section: Int): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section, Semester $semester, Year $year)"
        if (course.taEmail == null) {
            return "No TA assigned to $code (Section $section, Semester $semester, Year $year)"
        }
        val oldTA = course.taEmail
        course.taEmail = null
        courseRepository.save(course)
        return "Removed TA $oldTA from course $code (Section $section, Semester $semester, Year $year)"
    }

    @Transactional
    open fun addLab(
        code: String,
        year: Int,
        semester: String,
        section: Int,
        lab: ScheduledLab
    ): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "ERROR: Course not found: $code (Section $section, Semester $semester, Year $year)"

        // Check if lab with this number already exists
        val existingLab = course.labs.find { it.labNumber == lab.labNumber }
        if (existingLab != null) {
            // Update existing lab
            existingLab.startDateTime = lab.startDateTime
            existingLab.endDateTime = lab.endDateTime

            // Sync problems
            val existingProblemsByName = existingLab.problems.associateBy { it.name }
            val newProblemNames = lab.problems.map { it.name }.toSet()

            // Remove problems no longer in the input
            val problemsToRemove = existingLab.problems.filter { it.name !in newProblemNames }.toList()
            for (problem in problemsToRemove) {
                existingLab.removeProblem(problem)
            }

            // Add new problems or update existing ones
            for (problem in lab.problems) {
                val existingProblem = existingProblemsByName[problem.name]
                if (existingProblem != null) {
                    existingProblem.language = problem.language
                    existingProblem.note = problem.note
                } else {
                    existingLab.addProblem(problem)
                }
            }

            courseRepository.save(course)
            return "Updated Lab ${lab.labNumber} in $code (Section $section) with ${lab.problems.size} problem(s)"
        } else {
            // Add new lab
            course.addLab(lab)
            courseRepository.save(course)
            return "Added Lab ${lab.labNumber} to $code (Section $section) with ${lab.problems.size} problem(s)"
        }
    }
}