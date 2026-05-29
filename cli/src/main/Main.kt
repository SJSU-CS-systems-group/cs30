package com.cs30.cli

import com.cs30.server.models.Student
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.StudentRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.util.concurrent.Callable
import kotlin.system.exitProcess

import com.cs30.server.service.CourseService

data class StudentInput(
    val firstName: String,
    val lastName: String,
    val email: String,
    val section: Int? = null
)

data class SectionInput(
    val number: Int,
    val days: String = "",
    @JsonFormat(pattern = "HH:mm")
    val startTime: LocalTime? = null,
    @JsonFormat(pattern = "HH:mm")
    val endTime: LocalTime? = null
)

data class CourseInput(
    val code: String,
    val section: Int? = null,  // Optional - for single-section courses
    val year: Int? = null,
    val semester: String? = null,
    @JsonFormat(pattern = "yyyy-MM-dd")
    val startDate: LocalDate,
    @JsonFormat(pattern = "yyyy-MM-dd")
    val endDate: LocalDate,
    val days: String = "",  // For single-section courses
    @JsonFormat(pattern = "HH:mm")
    val startTime: LocalTime? = null,
    @JsonFormat(pattern = "HH:mm")
    val endTime: LocalTime? = null,
    val githubProblemsUrl: String = "",
    val githubSubmissionsUrl: String = "",
    val sections: List<SectionInput> = emptyList(),  // For multi-section courses
    val students: List<StudentInput> = emptyList()
)

@SpringBootApplication(scanBasePackages = ["com.cs30.cli", "com.cs30.server.service"])
@EntityScan("com.cs30.server.models")
@EnableJpaRepositories("com.cs30.server.repository")
class CliApplication(
    private val factory: IFactory
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

@Command(name = "addcourse", description = ["Add a new course from YAML file"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddCourse(
    private val courseService: CourseService,
    private val courseRepository: CourseRepository
) : Callable<Int> {

    @Mixin
    lateinit var cli: CliOptions

    @Option(names = ["--course-file"], description = ["Path to YAML course file"], required = true)
    var filePath: String = ""

    override fun call(): Int {
        val file = java.io.File(filePath)

        if (!file.exists() || !file.isFile) {
            cli.err().println("Error: File not found: $filePath")
            return 1
        }

        val isYaml = filePath.endsWith(".yml") || filePath.endsWith(".yaml")
        val mapper = if (isYaml) {
            ObjectMapper(YAMLFactory()).registerKotlinModule().findAndRegisterModules()
        } else {
            ObjectMapper().registerKotlinModule().findAndRegisterModules()
        }

        val courseInput: CourseInput = try {
            mapper.readValue(file)
        } catch (e: Exception) {
            cli.err().println("Error parsing file: ${e.message}")
            return 1
        }

        // Build section schedule info map
        val sectionSchedules = courseInput.sections.associateBy { it.number }

        // Group students by section
        val studentsBySection: Map<Int, List<StudentInput>> =
            if (courseInput.section != null) {
                mapOf(courseInput.section to courseInput.students)
            } else {
                courseInput.students.groupBy { it.section ?: 1 }
            }

        // Determine which sections to create (from sections list or from students)
        val sectionsToCreate = if (courseInput.sections.isNotEmpty()) {
            courseInput.sections.map { it.number }.toSet()
        } else {
            studentsBySection.keys
        }

        for (section in sectionsToCreate.sorted()) {
            val existing = courseRepository.findByCodeAndSection(courseInput.code, section)

            if (existing != null) {
                cli.err().println("Course already exists: ${courseInput.code} (Section $section) - skipping")
                continue
            }

            val sectionStudents = studentsBySection[section] ?: emptyList()
            val students = sectionStudents.map { s ->
                "${s.firstName} ${s.lastName}" to s.email
            }

            // Get schedule from sections list or from course-level defaults
            val sectionInfo = sectionSchedules[section]
            val daysStr = sectionInfo?.days ?: courseInput.days
            val days = parseDays(daysStr)
            val startTime = sectionInfo?.startTime ?: courseInput.startTime
            val endTime = sectionInfo?.endTime ?: courseInput.endTime

            courseService.createCourseWithStudents(
                courseInput.code,
                section,
                courseInput.startDate.atStartOfDay(),
                courseInput.endDate.atStartOfDay(),
                days,
                startTime,
                endTime,
                courseInput.githubProblemsUrl,
                courseInput.githubSubmissionsUrl,
                students
            )

            cli.out().println("Added course: ${courseInput.code} (Section $section) with ${students.size} students")
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

@Command(name = "deletecourse", description = ["Delete a course"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class DeleteCourse(
    private val courseRepository: CourseRepository
) : Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code (Ex: CS30)"], required = true)
    var courseName: String = ""

    @Option(names = ["--section"], description = ["Section number"], required = true)
    var section: Int = 0

    override fun call(): Int {
        val course = courseRepository.findByCodeAndSection(courseName, section)
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
            println("  - ${course.code} (Section ${course.section})")
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
        val course = courseRepository.findByCodeAndSection(courseName, section)
        if (course == null) {
            println("Course not found: $courseName (Section $section)")
            return 1
        }
        println("Course: ${course.code} (Section ${course.section})")
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
        val course = courseRepository.findByCodeAndSection(courseName, section)
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