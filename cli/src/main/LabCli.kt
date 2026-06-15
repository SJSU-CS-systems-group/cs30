package com.cs30.cli

import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.GitService
import com.cs30.server.service.LabService
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable

/**
 * Add a single problem to the problem pool git repo using problemtools.
 */
@Command(name = "addproblem", description = ["Add a single problem to the problem pool"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddProblem(
    private val gitService: GitService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--problem-dir"], description = ["Path to the problem directory"], required = true)
    var problemDir: String = ""

    @Option(names = ["--git-repo"], description = ["Git repository URL for the problem pool"], required = true)
    var problemGitRepo: String = ""

    override fun call(): Int {
        val dir = java.io.File(problemDir)
        if (!dir.exists() || !dir.isDirectory) {
            cli.err("ERROR: Problem directory not found or is not a directory: $problemDir")
            return 1
        }

        if (problemGitRepo.isBlank()) {
            gitService.initGitRepo(problemGitRepo)
        }

        cli.out("Adding problem '${dir.name}' to ${problemGitRepo}")

        return try {
            gitService.addProblemToRepo(
                problemGitRepo = problemGitRepo,
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
 * Add all problems from a directory to the global problem repository.
 * Expects directory structure: problems_dir/problem_name/
 * Converts each problem to HTML using problemtools and keeps the data folder.
 */
@Command(name = "addproblems", description = ["Add all problems from a directory to the global problem repository"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddProblems(
    private val gitService: GitService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--problems-dir"], description = ["Path to directory containing problem folders"], required = true)
    var problemsDir: String = ""

    @Option(names = ["--git-repo"], description = ["Git repository path for the global problem pool"], required = true)
    var problemGitRepo: String = ""

    override fun call(): Int {
        val dir = java.io.File(problemsDir)
        if (!dir.exists() || !dir.isDirectory) {
            cli.err("ERROR: Problems directory not found or is not a directory: $problemsDir")
            return 1
        }

        if (problemGitRepo.isBlank()) {
            cli.err("ERROR: Git repository path is required")
            return 1
        }

        // Initialize the git repo if needed
        gitService.initGitRepo(problemGitRepo)

        cli.out("Adding problems from: $problemsDir")
        cli.out("Problem repository: $problemGitRepo")
        cli.out("")

        return try {
            gitService.addProblemsToRepo(
                problemGitRepo = problemGitRepo,
                problemsDir = problemsDir
            )

            cli.out("")
            cli.out("All problems added successfully!")
            0
        } catch (e: Exception) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }
}

/**
 * Remove a single problem from the global problem repository.
 */
@Command(name = "removeproblem", description = ["Remove a single problem from the global problem repository"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class RemoveProblem(
    private val gitService: GitService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--git-repo"], description = ["Git repository path for the global problem pool"], required = true)
    var problemGitRepo: String = ""

    @Option(names = ["--problem-name"], description = ["Name of the problem to remove"], required = true)
    var problemName: String = ""

    override fun call(): Int {
        if (problemGitRepo.isBlank()) {
            cli.err("ERROR: Git repository path is required")
            return 1
        }

        cli.out("Removing problem '$problemName' from $problemGitRepo")
        cli.out("")

        return try {
            gitService.removeProblemFromRepo(
                problemGitRepo = problemGitRepo,
                problemName = problemName
            )
            cli.out("Problem removed successfully!")
            0
        } catch (e: Exception) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }
}

/**
 * Update a problem's language in the database.
 */
@Command(name = "updateproblemlanguage", description = ["Update a problem's language in the database"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class UpdateProblemLanguage(
    private val courseRepository: CourseRepository,
    private val labService: LabService
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

    @Option(names = ["--problem-name"], description = ["Name of the problem to update"], required = true)
    var problemName: String = ""

    @Option(names = ["--language"], description = ["New programming language (e.g., python, java, cpp)"], required = true)
    var language: String = ""

    override fun call(): Int {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(courseCode, year, semester, section)
        if (course == null) {
            cli.err("ERROR: Course not found: $courseCode $year $semester Section $section")
            return 1
        }

        cli.out("Updating language for problem '$problemName' in $courseCode Section $section, Lab $labNumber")
        cli.out("")

        return try {
            val result = labService.updateProblemLanguage(
                course = course,
                labNumber = labNumber,
                problemName = problemName,
                newLanguage = language
            )
            cli.out(result)
            0
        } catch (e: Exception) {
            cli.err("ERROR: ${e.message}")
            1
        }
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
            cli.err("ERROR: Course not found: $courseCode $year $semester Section $section")
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