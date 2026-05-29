package com.cs30.cli

import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CourseService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.transaction.Transactional
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.Callable
import kotlin.collections.forEach

/**
 * Add a new course from a YAML file. The YAML file should contain course details and a list of students.
 * Updates a course if it already exists (matched by code).
 */
@Command(name = "addcourse", description = ["Add a new course from YAML file"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddCourse(
    private val courseService: CourseService,
    private val courseRepository: CourseRepository
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-file"], description = ["Path to YAML course file"], required = true)
    var filePath: String = ""

    override fun call(): Int {
        val file = java.io.File(filePath)

        if (!file.exists() || !file.isFile) {
            cli.err().println("Error: File not found: $filePath")
            return 1
        }

        val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule().findAndRegisterModules()

        val courseInput: CourseInput = try {
            mapper.readValue(file)
        } catch (e: Exception) {
            cli.err().println("Error parsing file: ${e.message}")
            return 1
        }

        for (sectionInput in courseInput.sections) {
            val section = sectionInput.number
            val existing = courseRepository.findByCodeAndYearAndSemesterAndSection(courseInput.code,
                courseInput.year, courseInput.semester, section)

            val studentEmails = sectionInput.students
            val days = parseDays(sectionInput.days)
            val startTime = sectionInput.startTime
            val endTime = sectionInput.endTime

            if (existing != null) {
                // Update existing course
                courseService.updateCourseWithStudents(
                    existing.id,
                    courseInput.startDate.atStartOfDay(),
                    courseInput.endDate.atStartOfDay(),
                    days,
                    startTime,
                    endTime,
                    courseInput.githubProblemsUrl,
                    courseInput.githubSubmissionsUrl,
                    courseInput.language,
                    studentEmails
                )
                cli.out().println("Updated course: ${courseInput.code} (Section $section) with ${studentEmails.size} students")
            } else {
                // Create new course
                courseService.createCourseWithStudents(
                    courseInput.code,
                    section,
                    courseInput.year,
                    courseInput.semester,
                    courseInput.startDate.atStartOfDay(),
                    courseInput.endDate.atStartOfDay(),
                    days,
                    startTime,
                    endTime,
                    courseInput.githubProblemsUrl,
                    courseInput.githubSubmissionsUrl,
                    courseInput.language,
                    studentEmails
                )
                cli.out().println("Added course: ${courseInput.code} (Section $section) with ${studentEmails.size} students")
            }
        }
        return 0
    }

    private fun parseDays(daysStr: String): Set<DayOfWeek> {
        val days = mutableSetOf<DayOfWeek>()
        var i = 0
        while (i < daysStr.length) {
            when {
                daysStr.startsWith("Th", i, ignoreCase = true) -> {
                    days.add(DayOfWeek.THURSDAY)
                    i += 2
                }
                daysStr[i].uppercaseChar() == 'M' -> {
                    days.add(DayOfWeek.MONDAY)
                    i++
                }
                daysStr[i].uppercaseChar() == 'T' -> {
                    days.add(DayOfWeek.TUESDAY)
                    i++
                }
                daysStr[i].uppercaseChar() == 'W' -> {
                    days.add(DayOfWeek.WEDNESDAY)
                    i++
                }
                daysStr[i].uppercaseChar() == 'F' -> {
                    days.add(DayOfWeek.FRIDAY)
                    i++
                }
                daysStr[i].uppercaseChar() == 'S' && i + 1 < daysStr.length && daysStr[i + 1].uppercaseChar() == 'A' -> {
                    days.add(DayOfWeek.SATURDAY)
                    i += 2
                }
                daysStr[i].uppercaseChar() == 'S' && i + 1 < daysStr.length && daysStr[i + 1].uppercaseChar() == 'U' -> {
                    days.add(DayOfWeek.SUNDAY)
                    i += 2
                }
                else -> i++
            }
        }
        return days
    }
}

/**
 * Add a new student to an existing course.
 * If the course does not exist, prints an error message.
 */
@Command(name = "addstudent", description = ["Add a student email to a course"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddStudent(
    private val courseService: CourseService
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code"], required = true)
    var code: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Course section"], required = true)
    var section: Int = 0

    @Option(names = ["--email"], description = ["Student email"], required = true)
    var email: String = ""

    override fun call(): Int {
        val result = courseService.addStudentToCourse(code, year, semester, section, email)
        cli.out().println(result)
        return if (result.startsWith("Added")) 0 else 1
    }
}

/**
 * Changes the end date of an existing section or all sections of a course.
 */
@Command(name = "changeenddate", description = ["Changes end date of a course"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class ChangeEndDate(
    private val courseRepository: CourseRepository
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code (Ex: CS30)"], required = true)
    var code: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Section number, or all"], required = true)
    var section: String = ""

    @Option(names = ["--end-date"], description = ["New end date (yyyy-MM-dd)"], required = true)
    var endDate: String = ""

    override fun call(): Int {
        val newEndDate = try {
            LocalDate.parse(endDate).atStartOfDay()
        } catch (e: Exception) {
            cli.err().println("Invalid date format: $endDate (expected yyyy-MM-dd)")
            return 1
        }

        val courses: List<Course> = if (section.equals("all", ignoreCase = true)) {
            courseRepository.findByCodeAndYearAndSemester(code, year, semester)
        } else {
            val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt())
            if (course == null) {
                cli.err().println("Course not found: $code (Section $section)")
                return 1
            }
            listOf(course)
        }

        courses.forEach { course ->
            val updatedCourse = course.copy(endDate = newEndDate)
            courseRepository.save(updatedCourse)
            cli.out().println("Updated end date for ${course.code} (Section ${course.section}) to $endDate")
        }
        return 0
    }
}

/**
 * Removes a course by code and section only if past the end date.
 */
@Command(name = "removecourse", description = ["Removes a course"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class RemoveCourse(
    private val courseService: CourseService
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code (Ex: CS30)"], required = true)
    var code: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Section number, or all"], required = true)
    var section: String = ""

    override fun call(): Int {
        val results = courseService.removeCourse(code, year, semester, section)
        results.forEach { cli.out().println(it) }
        return if (results.any { it.startsWith("Deleted") }) 0 else 1
    }
}

/**
 * Remove a student email from an existing course.
 */
@Command(name = "removestudent", description = ["Remove a student from a course"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class RemoveStudent(
    private val courseService: CourseService
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code"], required = true)
    var code: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Course section"], required = true)
    var section: Int = 0

    @Option(names = ["--email"], description = ["Student email"], required = true)
    var email: String = ""

    override fun call(): Int {
        val result = courseService.removeStudentFromCourse(code, year, semester, section, email)
        cli.out().println(result)
        return if (result.startsWith("Removed")) 0 else 1
    }
}

@Command(name = "findcourse", description = ["Find a course and its students"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class FindCourse(
    private val courseRepository: CourseRepository
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code"], required = true)
    var code: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Course section"], required = true)
    var section: String = ""

    override fun call(): Int {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt())
        if (course == null) {
            println("Course not found: $code (Section $section)")
            return 1
        }
        println("Course: ${code} (Section ${course.section})")
        println("Students enrolled: ${course.students.size}")
        course.students.forEach { email ->
            println("  - $email")
        }
        return 0
    }
}

/**
 * Find all courses that contain a student email and print the course code and section.
 */
@Command(name = "findstudent", description = ["Find courses containing a student email"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class FindStudent(
    private val courseRepository: CourseRepository
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--email"], description = ["Student email"], required = true)
    var email: String = ""

    override fun call(): Int {
        val courses = courseRepository.findByStudentEmail(email)
        if (courses.isEmpty()) {
            println("No courses found for student: $email")
            return 1
        }
        println("Student: $email")
        println("Enrolled in ${courses.size} course(s):")
        courses.forEach { course ->
            println("  - ${course.code} (Section ${course.section})")
        }
        return 0
    }
}