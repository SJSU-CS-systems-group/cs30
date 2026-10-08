package com.cs30.cli

import com.cs30.server.dto.CourseInput
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseService
import com.cs30.server.service.CourseYamlService
import com.cs30.server.service.GitService
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.io.IOException
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
    private val courseYamlService: CourseYamlService,
    private val gitService: GitService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-file"], description = ["Path to YAML course file"], required = true)
    var filePath: String = ""

    override fun call(): Int {
        val file = java.io.File(filePath)

        if (!file.exists() || !file.isFile) {
            cli.err("ERROR: File not found: $filePath")
            return 1
        }

        // The same mapper that writes course.yml, so an exported file parses back in.
        val courseInput: CourseInput = try {
            courseYamlService.mapper.readValue(file)
        } catch (e: Exception) {
            cli.err("ERROR: Error parsing file: ${e.message}")
            return 1
        }

        // Initialize git repos (shared across all sections) - skips if already exists
        try {
            if (courseInput.studentGitRepo.isNotBlank()) {
                cli.out("Initializing student git repository: ${courseInput.studentGitRepo}")
                gitService.initGitRepo(courseInput.studentGitRepo)
                cli.out("  ✓ Student repository ready")
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

        courseService.applyCourseFile(courseInput).forEach { cli.out(it) }

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

        val results = courseService.changeEndDate(code, year, semester, section, newEndDate)
        results.forEach { if (it.startsWith("ERROR")) cli.err(it) else cli.out(it) }
        return if (results.any { it.startsWith("ERROR") }) 1 else 0
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
 * Add a TA to a course section. A section may have several; this adds one and leaves the rest.
 * "setta" is kept as an alias for the command's earlier name, when a section had a single TA.
 */
@Command(
    name = "addta",
    aliases = ["setta"],
    description = ["Add a TA to a course section"],
)
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddTA(
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
        val result = courseService.addTA(code, year, semester, section, email)
        if (result.startsWith("Added")) cli.out(result) else cli.err(result)
        return if (result.startsWith("Added")) 0 else 1
    }
}

/**
 * Remove one TA from a course section. Which one has to be named: a section may have several.
 */
@Command(name = "removeta", description = ["Remove a TA from a course section"])
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

    @Option(names = ["--email"], description = ["TA email to remove"], required = true)
    var email: String = ""

    override fun call(): Int {
        val result = courseService.removeTA(code, year, semester, section, email)
        if (result.startsWith("Removed")) cli.out(result) else cli.err(result)
        return if (result.startsWith("Removed")) 0 else 1
    }
}

/**
 * Export the current DB state of a course as a course.yml file.
 * Accessible to both admin and TA tokens (read-only).
 */
@Command(name = "exportcourse", description = ["Export current DB state of a course as course.yml"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class ExportCourse(
    private val courseYamlService: CourseYamlService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code"], required = true)
    var code: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--output"], description = ["Output file path (default: print to stdout)"], required = false)
    var outputFile: String = ""

    override fun call(): Int {
        val sections = courseYamlService.findSections(code, year, semester)
        if (sections.isEmpty()) {
            cli.err("No course found for $code $semester $year")
            return 1
        }
        val yaml = courseYamlService.render(sections)
        if (outputFile.isBlank()) {
            cli.out(yaml)
        } else {
            val target = expandHome(outputFile)
            try {
                target.writeText(yaml)
            } catch (e: IOException) {
                cli.err("ERROR: Cannot write $outputFile: ${e.message}")
                return 1
            }
            cli.out("Exported to ${target.path}")
        }
        return 0
    }

    /**
     * Expands a leading `~` the way a shell would. Needed because the shell leaves it alone in
     * `--output=~/file.yml` (only a `~` starting a word is expanded), so the CLI gets it literally.
     */
    private fun expandHome(path: String): File {
        val home = System.getProperty("user.home")
        return when {
            path == "~" -> File(home)
            path.startsWith("~/") -> File(home, path.removePrefix("~/"))
            else -> File(path)
        }
    }
}
