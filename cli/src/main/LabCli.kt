package com.cs30.cli

import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.GitService
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable

/**
 * Add a single problem to an existing course's problem repository.
 * Converts the problem to HTML and saves it to: section/lab/problemTitle
 */
@Command(name = "addproblem", description = ["Add a single problem to a course's problem repository"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddProblem(
    private val gitService: GitService,
    private val courseRepository: CourseRepository
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code (e.g., CS30)"], required = true)
    var courseCode: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester (e.g., Fall, Spring)"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Section number"], required = true)
    var section: Int = 0

    @Option(names = ["--lab"], description = ["Lab number"], required = true)
    var labNumber: Int = 0

    @Option(names = ["--problem-dir"], description = ["Path to the problem directory"], required = true)
    var problemDir: String = ""

    override fun call(): Int {
        // Look up the course to get the problemGitRepo
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(courseCode, year, semester, section)
        if (course == null) {
            cli.err("ERROR: Course not found: $courseCode $year $semester Section $section")
            return 1
        }

        if (course.problemGitRepo.isBlank()) {
            cli.err("ERROR: Course does not have a problem git repository configured")
            return 1
        }

        val dir = java.io.File(problemDir)
        if (!dir.exists() || !dir.isDirectory) {
            cli.err("ERROR: Problem directory not found or is not a directory: $problemDir")
            return 1
        }

        cli.out("Adding problem '${dir.name}' to $courseCode Section $section, Lab $labNumber")
        cli.out("Problem repository: ${course.problemGitRepo}")
        cli.out("")

        return try {
            gitService.addProblemToRepo(
                problemGitRepo = course.problemGitRepo,
                section = section,
                labNumber = labNumber,
                problemPath = problemDir
            )
            cli.out("Problem added successfully!")
            0
        } catch (e: Exception) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }

}

/**
 * Add all labs and problems from a directory structure to a course's problem repository.
 * Expects directory structure: Section_X/Lab_X/problem_name/
 * Converts each problem to HTML and mirrors the structure in the repo.
 */
@Command(name = "addlabs", description = ["Add all labs from a directory structure to a course's problem repository"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddLabs(
    private val gitService: GitService,
    private val courseRepository: CourseRepository
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code (e.g., CS30)"], required = true)
    var courseCode: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester (e.g., Fall, Spring)"], required = true)
    var semester: String = ""

    @Option(names = ["--labs-dir"], description = ["Path to directory containing Section_X/Lab_X/problem folders"], required = true)
    var labsDir: String = ""

    override fun call(): Int {
        // Look up any section of the course to get the problemGitRepo (shared across sections)
        val courses = courseRepository.findByCodeAndYearAndSemester(courseCode, year, semester)
        if (courses.isEmpty()) {
            cli.err("ERROR: Course not found: $courseCode $year $semester")
            return 1
        }
        val course = courses.first()

        if (course.problemGitRepo.isBlank()) {
            cli.err("ERROR: Course does not have a problem git repository configured")
            return 1
        }

        val dir = java.io.File(labsDir)
        if (!dir.exists() || !dir.isDirectory) {
            cli.err("ERROR: Labs directory not found or is not a directory: $labsDir")
            return 1
        }

        cli.out("Adding labs from: $labsDir")
        cli.out("Problem repository: ${course.problemGitRepo}")
        cli.out("")

        return try {
            gitService.addLabsToRepo(
                problemGitRepo = course.problemGitRepo,
                labsDir = labsDir
            )
            cli.out("All labs added successfully!")
            0
        } catch (e: Exception) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }
}