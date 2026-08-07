package com.cs30.cli

import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseService
import com.cs30.server.service.GitService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.time.LocalDate
import java.util.concurrent.Callable

/**
 * Add a new course from a YAML file. The YAML file should contain course details and a list of students.
 * Updates a course if it already exists (matched by code).
 */
@Command(name = "addcourse", description = ["Add a new course from YAML file"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddCourse(
    private val courseService: CourseService,
    private val courseRepository: CourseRepository,
    private val gitService: GitService,
    private val appTimeZoneService: AppTimeZoneService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-file"], description = ["Path to YAML course file"], required = true)
    var filePath: String = ""

    override fun call(): Int {
        val file = java.io.File(filePath)

        if (!file.exists() || !file.isFile) {
            cli.err("ERROR: File not found: $filePath")
            return 1
        }

        val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule().findAndRegisterModules()

        val courseInput: CourseInput = try {
            mapper.readValue(file)
        } catch (e: Exception) {
            cli.err("ERROR: Error parsing file: ${e.message}")
            return 1
        }

        // Populate problem languages with course default if not specified
        val defaultLanguage = courseInput.language
        for (section in courseInput.sections) {
            for (lab in section.labs) {
                for (problem in lab.problems) {
                    if (problem.language.isNullOrBlank()) {
                        problem.language = defaultLanguage
                    }
                }
            }
        }

        // Initialize git repos (shared across all sections) - skips if already exists
        try {
            if (courseInput.studentGitRepo.isNotBlank()) {
                cli.out("Initializing student git repository: ${courseInput.studentGitRepo}")
                gitService.initGitRepo(courseInput.studentGitRepo)
                cli.out("  ✓ Student repository ready")

                // Save a copy of the course YAML file (with populated languages) to the student repo
                cli.out("Saving course configuration to repository...")
                val tempFile = java.io.File.createTempFile("course", ".yml")
                try {
                    mapper.writeValue(tempFile, courseInput)
                    gitService.saveFileToRepo(courseInput.studentGitRepo, tempFile.absolutePath, "course.yml")
                } finally {
                    tempFile.delete()
                }
                cli.out("  ✓ Course configuration saved")
            }
            if (courseInput.problemGitRepo.isNotBlank()) {
                cli.out("Initializing problem git repository: ${courseInput.problemGitRepo}")
                gitService.initGitRepo(courseInput.problemGitRepo)
                cli.out("  ✓ Problem repository ready")
            }
        } catch (e: Exception) {
            cli.err("ERROR: Failed to initialize git repositories: ${e.message}")
            return 1
        }

        for (sectionInput in courseInput.sections) {
            val section = sectionInput.number
            val existing = courseRepository.findByCodeAndYearAndSemesterAndSection(
                courseInput.code,
                courseInput.year,
                courseInput.semester,
                section
            )

            val studentEmails = sectionInput.students
            val labs = sectionInput.labs.map { labInput ->
                val lab = ScheduledLab(
                    labNumber = labInput.number,
                    startDateTime = appTimeZoneService.toUtc(labInput.startDateTime),
                    endDateTime = appTimeZoneService.toUtc(labInput.endDateTime)
                )
                // Add problems to the lab
                for (problemInput in labInput.problems) {
                    val problem = Problem(
                        name = problemInput.name,
                        language = problemInput.language ?: defaultLanguage,
                        note = problemInput.note
                    )
                    lab.addProblem(problem)
                }
                lab
            }

            if (existing != null) {
                // Update existing course
                courseService.updateCourseWithStudents(
                    existing.id,
                    appTimeZoneService.toUtc(courseInput.startDate.atStartOfDay()),
                    appTimeZoneService.toUtc(courseInput.endDate.atStartOfDay()),
                    courseInput.studentGitRepo,
                    courseInput.problemGitRepo,
                    courseInput.language,
                    sectionInput.ta,
                    studentEmails,
                    labs
                )
                cli.out("Updated course: ${courseInput.code} (Section $section) with ${studentEmails.size} students and ${labs.size} labs")
            } else {
                // Create new course
                courseService.createCourseWithStudents(
                    courseInput.code,
                    section,
                    courseInput.year,
                    courseInput.semester,
                    appTimeZoneService.toUtc(courseInput.startDate.atStartOfDay()),
                    appTimeZoneService.toUtc(courseInput.endDate.atStartOfDay()),
                    courseInput.studentGitRepo,
                    courseInput.problemGitRepo,
                    courseInput.language,
                    sectionInput.ta,
                    studentEmails,
                    labs
                )
                cli.out("Added course: ${courseInput.code} (Section $section) with ${studentEmails.size} students and ${labs.size} labs")
            }
        }
        return 0
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
        if (result.startsWith("Added")) cli.out(result) else cli.err(result)
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
    private val courseRepository: CourseRepository,
    private val appTimeZoneService: AppTimeZoneService,
    private val courseService: CourseService,
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
            appTimeZoneService.toUtc(LocalDate.parse(endDate).atStartOfDay())
        } catch (e: Exception) {
            cli.err("ERROR: Invalid date format: $endDate (expected yyyy-MM-dd)")
            return 1
        }
        val courses: List<Course> = if (section.equals("all", ignoreCase = true)) {
            val temp = courseRepository.findByCodeAndYearAndSemester(code, year, semester)
            if (temp.isEmpty()) {
                cli.err("ERROR: Course not found: $code (Section $section)${courseService.currentOrFutureCoursesSuffix()}")
                return 1
            }
            temp
        } else {
            val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt())
            if (course == null) {
                cli.err("ERROR: Course not found: $code (Section $section)${courseService.currentOrFutureCoursesSuffix()}")
                return 1
            }
            listOf(course)
        }

        courses.forEach { course ->
            val updatedCourse = course.copy(endDate = newEndDate)
            courseRepository.save(updatedCourse)
            cli.out("Updated end date for ${course.code} (Section ${course.section}) to $endDate")
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
        results.forEach {
            if (it.startsWith("Deleted")) cli.out(it) else cli.err(it)
        }
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
        if (result.startsWith("Removed")) cli.out(result) else cli.err(result)
        return if (result.startsWith("Removed")) 0 else 1
    }
}

@Command(name = "findcourse", description = ["Display course attributes and enrolled students"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class FindCourse(
    private val courseService: CourseService
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code"], required = true)
    var code: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Section number, or all"], required = true)
    var section: String = ""

    override fun call(): Int {
        val results = courseService.findCourse(code, year, semester, section)
        results.forEach {
            if (it.startsWith("ERROR:")) cli.err(it) else cli.out(it)
        }
        return if (results.any { it.startsWith("ERROR:") }) 1 else 0
    }
}

/**
 * Find all courses that contain a student email and print the course code and section.
 */
@Command(name = "findstudent", description = ["Find courses containing a student email"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class FindStudent(
    private val courseService: CourseService
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--email"], description = ["Student email"], required = true)
    var email: String = ""

    override fun call(): Int {
        val results = courseService.findStudent(email)
        results.forEach {
            if (it.startsWith("ERROR:")) cli.err(it) else cli.out(it)
        }
        return if (results.any { it.startsWith("ERROR:") }) 1 else 0
    }
}

/**
 * Set or update the TA email for a course section.
 */
@Command(name = "setta", description = ["Set the TA email for a course section"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class SetTA(
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

    @Option(names = ["--email"], description = ["TA email"], required = true)
    var email: String = ""

    override fun call(): Int {
        val result = courseService.setTA(code, year, semester, section, email)
        if (result.startsWith("Set")) cli.out(result) else cli.err(result)
        return if (result.startsWith("Set")) 0 else 1
    }
}

/**
 * Remove the TA from a course section.
 */
@Command(name = "removeta", description = ["Remove the TA from a course section"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class RemoveTA(
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

    override fun call(): Int {
        val result = courseService.removeTA(code, year, semester, section)
        if (result.startsWith("Removed")) cli.out(result) else cli.err(result)
        return if (result.startsWith("Removed")) 0 else 1
    }
}