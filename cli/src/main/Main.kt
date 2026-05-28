package com.cs30.cli

import com.cs30.server.models.Student
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.StudentRepository
import java.time.LocalDateTime
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.stereotype.Component
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.IFactory
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable
import kotlin.system.exitProcess

import com.cs30.server.service.CourseService

@SpringBootApplication(scanBasePackages = ["com.cs30.cli", "com.cs30.server.service"])
@EntityScan("com.cs30.server.models")
@EnableJpaRepositories("com.cs30.server.repository")
class CliApplication(
    private val factory: IFactory,
    private val mainCommand: MainCommand
) : CommandLineRunner, ExitCodeGenerator {

    private var exitCode: Int = 0

    override fun run(vararg args: String) {
        println("DEBUG: args = ${args.toList()}")
        // Use class-based CommandLine so picocli creates instances during parsing
        val cmd = CommandLine(MainCommand::class.java, factory)
        exitCode = cmd.execute(*args)
    }

    override fun getExitCode(): Int = exitCode
}

@Command(
    name = "cs30",
    mixinStandardHelpOptions = true,
    version = ["1.0"],
    description = ["CS30 Course Management CLI"],
    subcommands = [
        AddCourse::class,
        DeleteCourse::class,
        FindCourse::class,
        AddStudent::class,
        DeleteStudent::class,
        FindStudent::class,
        EnrollStudent::class
    ]
)
@Component
class MainCommand {

    @Option(names = ["--db-url"], description = ["Database JDBC URL"])
    var dbUrl: String? = null

    @Option(names = ["--db-user"], description = ["Database username"])
    var dbUser: String? = null

    @Option(names = ["--db-pass"], description = ["Database password"])
    var dbPass: String? = null
}

@Command(name = "addcourse", description = ["Add a new course from file"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddCourse(
    private val courseService: CourseService,
    private val courseRepository: CourseRepository
) : Callable<Int> {

    @Option(names = ["--course-file"], description = ["Path to course info file"], required = true)
    var filePath: String = ""

    override fun call(): Int {
        val file = java.io.File(filePath)

        if (!file.exists() || !file.isFile) {
            println("Error: File not found: $filePath")
            return 1
        }

        val lines = file.readLines()

        var courseName = ""
        var courseSection = 0
        var startDate = LocalDateTime.now()
        var endDate = LocalDateTime.now().plusMonths(4)
        var problemsUrl = ""
        var submissionsUrl = ""
        val students = mutableListOf<Pair<String, String>>() // name, email

        var inStudentSection = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Course Name:") ->
                    courseName = trimmed.substringAfter(":").trim()
                trimmed.startsWith("Course Section:") ->
                    courseSection = trimmed.substringAfter(":").trim().toIntOrNull() ?: 0
                trimmed.startsWith("Start Date:") || trimmed.startsWith("Start Data:") ->
                    startDate = parseDate(trimmed.substringAfter(":").trim())
                trimmed.startsWith("End Date:") ->
                    endDate = parseDate(trimmed.substringAfter(":").trim())
                trimmed.startsWith("Github Problems") ->
                    problemsUrl = trimmed.substringAfter(":").trim()
                trimmed.startsWith("Github Submissions") ->
                    submissionsUrl = trimmed.substringAfter(":").trim()
                trimmed.startsWith("Students:") ->
                    inStudentSection = true
                inStudentSection && trimmed.matches(Regex("^\\d+\\..*")) -> {
                    // Parse "1. John Doe, john.doe@sjsu.edu"
                    val content = trimmed.substringAfter(".").trim()
                    val parts = content.split(",").map { it.trim() }
                    if (parts.size >= 2) {
                        students.add(parts[0] to parts[1])
                    }
                }
            }
        }

        if (courseName.isEmpty()) {
            println("Error: Course name not found in file")
            return 1
        }

        val existing = courseRepository.findByNameAndSection(courseName, courseSection)
        if (existing != null) {
            println("Course already exists: $courseName (Section $courseSection)")
            return 1
        } /* TO-DO: Overwrite existing course */

        courseService.createCourseWithStudents(
            courseName, courseSection, startDate, endDate, problemsUrl, submissionsUrl, students
        )
        println("Added course: $courseName (Section $courseSection) with ${students.size} students")
        return 0
    }

    private fun parseDate(dateStr: String): LocalDateTime {
        return try {
            java.time.LocalDate.parse(dateStr).atStartOfDay()
        } catch (_: Exception) {
            LocalDateTime.now()
        }
    }
}

@Command(name = "deletecourse", description = ["Delete a course"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class DeleteCourse : Callable<Int> {

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Parameters(index = "0", description = ["Course name"])
    lateinit var courseName: String

    @Parameters(index = "1", description = ["Section number"])
    var section: Int = 1

    override fun call(): Int {
        val course = courseRepository.findByNameAndSection(courseName, section)
        if (course == null) {
            println("Course not found: $courseName (Section $section)")
            return 1
        }
        courseRepository.delete(course)
        println("Deleted course: $courseName (Section $section)")
        return 0
    }
}

@Command(name = "addstudent", description = ["Add a new student"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddStudent : Callable<Int> {

    @Autowired
    lateinit var studentRepository: StudentRepository

    @Parameters(index = "0", description = ["Student email"])
    lateinit var email: String

    @Parameters(index = "1", description = ["First name"])
    lateinit var firstName: String

    @Parameters(index = "2", description = ["Last name"])
    lateinit var lastName: String

    override fun call(): Int {
        val existing = studentRepository.findByEmail(email)
        if (existing != null) {
            println("Student already exists: $email")
            return 1
        }
        val student = Student(
            email = email,
            firstName = firstName,
            lastName = lastName
        )
        studentRepository.save(student)
        println("Added student: $firstName $lastName ($email)")
        return 0
    }
}

@Command(name = "deletestudent", description = ["Delete a student"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class DeleteStudent : Callable<Int> {

    @Autowired
    lateinit var studentRepository: StudentRepository

    @Parameters(index = "0", description = ["Student email"])
    lateinit var email: String

    override fun call(): Int {
        val student = studentRepository.findByEmail(email)
        if (student == null) {
            println("Student not found: $email")
            return 1
        }
        studentRepository.delete(student)
        println("Deleted student: $email")
        return 0
    }
}

@Command(name = "findstudent", description = ["Find a student and their courses"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class FindStudent : Callable<Int> {

    @Autowired
    lateinit var studentRepository: StudentRepository

    @Parameters(index = "0", description = ["Student email"])
    lateinit var email: String

    @Transactional
    override fun call(): Int {
        val student = studentRepository.findByEmail(email)
        if (student == null) {
            println("Student not found: $email")
            return 1
        }
        println("Student: ${student.firstName} ${student.lastName} (${student.email})")
        println("Enrolled in ${student.courses.size} course(s):")
        student.courses.forEach { course ->
            println("  - ${course.name} (Section ${course.section})")
        }
        return 0
    }
}

@Command(name = "findcourse", description = ["Find a course and its students"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class FindCourse : Callable<Int> {

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Parameters(index = "0", description = ["Course name"])
    lateinit var courseName: String

    @Parameters(index = "1", description = ["Section number"])
    var section: Int = 1

    @Transactional
    override fun call(): Int {
        val course = courseRepository.findByNameAndSection(courseName, section)
        if (course == null) {
            println("Course not found: $courseName (Section $section)")
            return 1
        }
        println("Course: ${course.name} (Section ${course.section})")
        println("Students enrolled: ${course.students.size}")
        course.students.forEach { student ->
            println("  - ${student.firstName} ${student.lastName} (${student.email})")
        }
        return 0
    }
}

@Command(name = "enroll", description = ["Enroll a student in a course"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class EnrollStudent : Callable<Int> {

    @Autowired
    lateinit var studentRepository: StudentRepository

    @Autowired
    lateinit var courseRepository: CourseRepository

    @Parameters(index = "0", description = ["Student email"])
    lateinit var email: String

    @Parameters(index = "1", description = ["Course name"])
    lateinit var courseName: String

    @Parameters(index = "2", description = ["Section number"])
    var section: Int = 1

    @Transactional
    override fun call(): Int {
        val student = studentRepository.findByEmail(email)
        if (student == null) {
            println("Student not found: $email")
            return 1
        }
        val course = courseRepository.findByNameAndSection(courseName, section)
        if (course == null) {
            println("Course not found: $courseName (Section $section)")
            return 1
        }
        if (course.students.contains(student)) {
            println("Student already enrolled in this course")
            return 1
        }
        course.students.add(student)
        courseRepository.save(course)
        println("Enrolled ${student.firstName} ${student.lastName} in $courseName (Section $section)")
        return 0
    }
}

fun main(args: Array<String>) {
    // Allow passing DB config via command line
    val appArgs = mutableListOf<String>()
    val cliArgs = mutableListOf<String>()

    var i = 0
    while (i < args.size) {
        when {
            args[i] == "--db-url" && i + 1 < args.size -> {
                appArgs.add("--spring.datasource.url=${args[i + 1]}")
                i += 2
            }
            args[i].startsWith("--db-url=") -> {
                appArgs.add("--spring.datasource.url=${args[i].substringAfter("=")}")
                i++
            }
            args[i] == "--db-user" && i + 1 < args.size -> {
                appArgs.add("--spring.datasource.username=${args[i + 1]}")
                i += 2
            }
            args[i].startsWith("--db-user=") -> {
                appArgs.add("--spring.datasource.username=${args[i].substringAfter("=")}")
                i++
            }
            args[i] == "--db-pass" && i + 1 < args.size -> {
                appArgs.add("--spring.datasource.password=${args[i + 1]}")
                i += 2
            }
            args[i].startsWith("--db-pass=") -> {
                appArgs.add("--spring.datasource.password=${args[i].substringAfter("=")}")
                i++
            }
            else -> {
                cliArgs.add(args[i])
                i++
            }
        }
    }

    // Combine Spring args with CLI args
    val allArgs = (appArgs + cliArgs).toTypedArray()
    exitProcess(SpringApplication.exit(SpringApplication.run(CliApplication::class.java, *allArgs)))
}