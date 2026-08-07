package com.cs30.cli

import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.AppTimeZoneService
import com.cs30.server.service.CourseService
import com.cs30.server.service.LabService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable

/**
 * Add a lab to an existing course from a YAML file.
 * Updates the lab if it already exists (matched by lab number).
 */
@Command(name = "addlab", description = ["Add or update a lab to an existing course from YAML file"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddLab(
    private val courseService: CourseService,
    private val courseRepository: CourseRepository,
    private val appTimeZoneService: AppTimeZoneService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--lab-file"], description = ["Path to YAML lab file"], required = true)
    var filePath: String = ""

    override fun call(): Int {
        val file = java.io.File(filePath)

        if (!file.exists() || !file.isFile) {
            cli.err("ERROR: File not found: $filePath")
            return 1
        }

        val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule().findAndRegisterModules()

        val labFileInput: LabFileInput = try {
            mapper.readValue(file)
        } catch (e: Exception) {
            cli.err("ERROR: Error parsing file: ${e.message}")
            return 1
        }

        // Find the course to get the default language
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(
            labFileInput.code,
            labFileInput.year,
            labFileInput.semester,
            labFileInput.section
        )
        if (course == null) {
            cli.err(
                "ERROR: Course not found: ${labFileInput.code} (Section ${labFileInput.section}, Semester ${labFileInput.semester}, Year ${labFileInput.year})" +
                        courseService.currentOrFutureCoursesSuffix()
            )
            return 1
        }

        val defaultLanguage = course.language

        // Create the ScheduledLab with problems
        val lab = ScheduledLab(
            labNumber = labFileInput.labNumber,
            startDateTime = appTimeZoneService.toUtc(labFileInput.startDateTime),
            endDateTime = appTimeZoneService.toUtc(labFileInput.endDateTime)
        )

        for (problemInput in labFileInput.problems) {
            val problem = Problem(
                name = problemInput.name,
                language = problemInput.language ?: defaultLanguage,
                note = problemInput.note
            )
            lab.addProblem(problem)
        }

        val result = courseService.addLab(
            labFileInput.code,
            labFileInput.year,
            labFileInput.semester,
            labFileInput.section,
            lab
        )

        if (result.startsWith("ERROR:")) {
            cli.err(result)
            return 1
        }

        cli.out(result)
        return 0
    }
}

/**
 * Cancel a lab and delete its problems from the database.
 * Note: This only updates the database. Problems in the global repo are not affected.
 */
@Command(name = "cancellab", description = ["Cancel a lab and delete its problems from the database"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class CancelLab(
    private val courseRepository: CourseRepository,
    private val labService: LabService,
    private val courseService: CourseService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code (e.g., CS30)"], required = true)
    var courseCode: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester (e.g., Fall, Spring)"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Section number"], required = true)
    var section: Int = 0

    @Option(names = ["--lab"], description = ["Lab number to cancel"], required = true)
    var labNumber: Int = 0

    override fun call(): Int {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(courseCode, year, semester, section)
        if (course == null) {
            cli.err("ERROR: Course not found: $courseCode $year $semester Section $section${courseService.currentOrFutureCoursesSuffix()}")
            return 1
        }

        cli.out("Cancelling Lab $labNumber in $courseCode Section $section")
        cli.out("")

        return try {
            val results = labService.cancelLab(
                course = course,
                labNumber = labNumber
            )
            results.forEach { cli.out(it) }

            if (results.any { it.startsWith("ERROR") }) {
                1
            } else {
                cli.out("")
                cli.out("Lab cancelled successfully!")
                0
            }
        } catch (e: Exception) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }
}