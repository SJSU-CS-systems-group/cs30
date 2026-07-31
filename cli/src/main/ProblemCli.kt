package com.cs30.cli

import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CourseService
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

    @Option(names = ["--lab"], description = ["Lab number"], required = true)
    var labNumber: Int = 0

    @Option(names = ["--problem-name"], description = ["Name of the problem to update"], required = true)
    var problemName: String = ""

    @Option(names = ["--language"], description = ["New programming language (e.g., python, java, cpp)"], required = true)
    var language: String = ""

    override fun call(): Int {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(courseCode, year, semester, section)
        if (course == null) {
            cli.err("ERROR: Course not found: $courseCode $year $semester Section $section${courseService.currentOrFutureCoursesSuffix()}")
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
 * Validate that all problems in a course exist in the git repo.
 * Outputs the problems that are missing from the git repo.
 */
@Command(name = "validatecourse", description = ["Validate that all course problems exist in the git repo"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class ValidateCourse(
    private val courseRepository: CourseRepository,
    private val gitService: GitService,
    private val courseService: CourseService,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code (e.g., CS30)"], required = true)
    var courseCode: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester (e.g., Fall, Spring)"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Section number, or 'all'"], required = true)
    var section: String = ""

    override fun call(): Int {
        val courses = if (section.equals("all", ignoreCase = true)) {
            courseRepository.findByCodeAndYearAndSemester(courseCode, year, semester)
        } else {
            val sectionNum = section.toIntOrNull()
            if (sectionNum == null) {
                cli.err("ERROR: Invalid section number: $section")
                return 1
            }
            val course = courseRepository.findByCodeAndYearAndSemesterAndSection(courseCode, year, semester, sectionNum)
            if (course != null) listOf(course) else emptyList()
        }

        if (courses.isEmpty()) {
            cli.err("ERROR: Course not found: $courseCode $year $semester Section $section${courseService.currentOrFutureCoursesSuffix()}")
            return 1
        }

        val problemGitRepo = courses.first().problemGitRepo
        if (problemGitRepo.isBlank()) {
            cli.err("ERROR: Course does not have a problem git repository configured")
            return 1
        }

        cli.out("Validating problems for $courseCode $year $semester")
        cli.out("Problem repository: $problemGitRepo")
        cli.out("")

        // Collect all unique problems from all sections
        val allProblems = mutableSetOf<String>()
        for (course in courses) {
            for (lab in course.labs) {
                for (problem in lab.problems) {
                    allProblems.add(problem.name)
                }
            }
        }

        if (allProblems.isEmpty()) {
            cli.out("No problems found in course.")
            return 0
        }

        cli.out("Checking ${allProblems.size} problem(s)...")
        cli.out("")

        val missingProblems = mutableListOf<String>()
        for (problemName in allProblems.sorted()) {
            val exists = gitService.problemExistsInRepo(problemGitRepo, problemName)
            if (exists) {
                cli.out("  ✓ $problemName")
            } else {
                cli.out("  ✗ $problemName (MISSING)")
                missingProblems.add(problemName)
            }
        }

        cli.out("")
        return if (missingProblems.isEmpty()) {
            cli.out("All problems exist in the git repo.")
            0
        } else {
            cli.err("Missing problems (${missingProblems.size}):")
            missingProblems.forEach { cli.err("  - $it") }
            1
        }
    }
}
