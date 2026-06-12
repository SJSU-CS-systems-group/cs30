package com.cs30.cli

import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.GitService
import com.cs30.server.service.LabService
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable

/**
 * Add a single problem to an existing course's problem repository and database.
 * Converts the problem to HTML and saves it to: section/lab/problemTitle
 */
@Command(name = "addproblem", description = ["Add a single problem to a course's problem repository and database"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class AddProblem(
    private val gitService: GitService,
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

    @Option(names = ["--problem-dir"], description = ["Path to the problem directory"], required = true)
    var problemDir: String = ""

    @Option(names = ["--language"], description = ["Programming language for this problem (e.g., python, java, cpp)"], required = false)
    var language: String? = null

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

            // Add problem to database
            val result = labService.addProblemToLab(
                course = course,
                labNumber = labNumber,
                problemName = dir.name,
                language = language
            )
            cli.out(result)
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
    private val courseRepository: CourseRepository,
    private val labService: LabService
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

            // Add problems to database
            val results = labService.addProblemsFromDirectory(courses, dir)
            results.forEach { cli.out(it) }

            cli.out("")
            cli.out("All labs added successfully!")
            0
        } catch (e: Exception) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }
}

/**
 * Remove a single problem from an existing course's problem repository and database.
 */
@Command(name = "removeproblem", description = ["Remove a single problem from a course's problem repository and database"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class RemoveProblem(
    private val gitService: GitService,
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

    @Option(names = ["--problem-name"], description = ["Name of the problem to remove"], required = true)
    var problemName: String = ""

    override fun call(): Int {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(courseCode, year, semester, section)
        if (course == null) {
            cli.err("ERROR: Course not found: $courseCode $year $semester Section $section")
            return 1
        }

        if (course.problemGitRepo.isBlank()) {
            cli.err("ERROR: Course does not have a problem git repository configured")
            return 1
        }

        cli.out("Removing problem '$problemName' from $courseCode Section $section, Lab $labNumber")
        cli.out("Problem repository: ${course.problemGitRepo}")
        cli.out("")

        return try {
            // Remove from git repo
            gitService.removeProblemFromRepo(
                problemGitRepo = course.problemGitRepo,
                section = section,
                labNumber = labNumber,
                problemName = problemName
            )

            // Remove from database
            val result = labService.removeProblemFromLab(
                course = course,
                labNumber = labNumber,
                problemName = problemName
            )
            cli.out(result)
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
 * Cancel a lab and move its problems to another lab.
 */
@Command(name = "cancellab", description = ["Cancel a lab and move its problems to another lab"])
@Component
@org.springframework.context.annotation.Scope("prototype")
class CancelLab(
    private val courseRepository: CourseRepository,
    private val labService: LabService,
    private val gitService: GitService
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

    @Option(names = ["--move-to-lab"], description = ["Lab number to move problems to (defaults to next lab)"], required = false)
    var moveToLab: Int? = null

    override fun call(): Int {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(courseCode, year, semester, section)
        if (course == null) {
            cli.err("ERROR: Course not found: $courseCode $year $semester Section $section")
            return 1
        }

        if (course.problemGitRepo.isBlank()) {
            cli.err("ERROR: Course does not have a problem git repository configured")
            return 1
        }

        val targetLab = moveToLab ?: (labNumber + 1)
        cli.out("Cancelling Lab $labNumber in $courseCode Section $section")
        cli.out("Moving problems to Lab $targetLab")
        cli.out("")

        return try {
            // First, move problems in git repo
            cli.out("Moving problem folders in git repository...")
            gitService.moveProblemsToLab(
                problemGitRepo = course.problemGitRepo,
                section = section,
                fromLabNumber = labNumber,
                toLabNumber = targetLab
            )
            cli.out("")

            // Then update the database
            cli.out("Updating database...")
            val results = labService.cancelLab(
                course = course,
                labNumber = labNumber,
                moveToLabNumber = moveToLab
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