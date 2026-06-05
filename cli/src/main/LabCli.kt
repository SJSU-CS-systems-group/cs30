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