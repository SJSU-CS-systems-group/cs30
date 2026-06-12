package com.cs30.server.service

import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service

@Service
class LabService(
    private val courseRepository: CourseRepository
) {

    /**
     * Add or update a problem in a course's lab. Creates the lab if it doesn't exist.
     * Returns a message describing what was done.
     */
    @Transactional
    open fun addProblemToLab(
        course: Course,
        labNumber: Int,
        problemName: String,
        language: String? = null
    ): String {
        val problemLanguage = language ?: course.language

        // Find or create the lab
        var lab = course.labs.find { it.labNumber == labNumber }
        if (lab == null) {
            lab = ScheduledLab(labNumber = labNumber)
            course.addLab(lab)
        }

        // Update if exists, otherwise add
        val existingProblem = lab.problems.find { it.name == problemName }
        if (existingProblem != null) {
            lab.removeProblem(existingProblem)
            val newProblem = Problem(name = problemName, language = problemLanguage)
            lab.addProblem(newProblem)
            courseRepository.save(course)
            return "Updated problem '$problemName' in Lab $labNumber (language: $problemLanguage)"
        }

        val newProblem = Problem(name = problemName, language = problemLanguage)
        lab.addProblem(newProblem)
        courseRepository.save(course)
        return "Added problem '$problemName' to Lab $labNumber (language: $problemLanguage)"
    }

    /**
     * Add multiple problems from a directory structure to courses.
     * Expected structure: Section_X/Lab_X/problem_name/
     * All problems use the course's language.
     */
    @Transactional
    open fun addProblemsFromDirectory(
        courses: List<Course>,
        labsDir: java.io.File
    ): List<String> {
        val results = mutableListOf<String>()

        val sectionDirs = labsDir.listFiles { f -> f.isDirectory && f.name.startsWith("Section_", ignoreCase = true) } ?: emptyArray()

        for (sectionDir in sectionDirs) {
            val sectionNum = sectionDir.name.substringAfter("_").toIntOrNull() ?: continue

            val sectionCourse = courses.find { it.section == sectionNum }
            if (sectionCourse == null) {
                results.add("Warning: No course found for Section $sectionNum, skipping")
                continue
            }

            val labDirs = sectionDir.listFiles { f -> f.isDirectory && f.name.startsWith("Lab_", ignoreCase = true) } ?: emptyArray()

            for (labDir in labDirs) {
                val labNum = labDir.name.substringAfter("_").toIntOrNull() ?: continue

                var lab = sectionCourse.labs.find { it.labNumber == labNum }
                if (lab == null) {
                    lab = ScheduledLab(labNumber = labNum)
                    sectionCourse.addLab(lab)
                }

                val problemDirs = labDir.listFiles { f -> f.isDirectory } ?: emptyArray()

                for (problemDir in problemDirs) {
                    val problemName = problemDir.name

                    if (lab.problems.none { it.name == problemName }) {
                        val problem = Problem(name = problemName, language = sectionCourse.language)
                        lab.addProblem(problem)
                        results.add("Added problem '$problemName' to Section $sectionNum, Lab $labNum (language: ${sectionCourse.language})")
                    }
                }
            }

            courseRepository.save(sectionCourse)
        }

        return results
    }

    /**
     * Remove a problem from a course's lab.
     * Returns a message describing what was done.
     */
    @Transactional
    open fun removeProblemFromLab(
        course: Course,
        labNumber: Int,
        problemName: String
    ): String {
        val lab = course.labs.find { it.labNumber == labNumber }
            ?: return "Lab $labNumber not found in course"

        val problem = lab.problems.find { it.name == problemName }
            ?: return "Problem '$problemName' not found in Lab $labNumber"

        lab.removeProblem(problem)
        courseRepository.save(course)
        return "Removed problem '$problemName' from Lab $labNumber"
    }

    /**
     * Update a problem's language in a course's lab.
     * Returns a message describing what was done.
     */
    @Transactional
    open fun updateProblemLanguage(
        course: Course,
        labNumber: Int,
        problemName: String,
        newLanguage: String
    ): String {
        val lab = course.labs.find { it.labNumber == labNumber }
            ?: return "Lab $labNumber not found in course"

        val existingProblem = lab.problems.find { it.name == problemName }
            ?: return "Problem '$problemName' not found in Lab $labNumber"

        lab.removeProblem(existingProblem)
        val newProblem = Problem(name = problemName, language = newLanguage)
        lab.addProblem(newProblem)
        courseRepository.save(course)
        return "Updated problem '$problemName' language to '$newLanguage' in Lab $labNumber"
    }

    /**
     * Cancel a lab and move its problems to another lab.
     * Returns a list of messages describing what was done.
     */
    @Transactional
    open fun cancelLab(
        course: Course,
        labNumber: Int,
        moveToLabNumber: Int?
    ): List<String> {
        val results = mutableListOf<String>()

        val labToCancel = course.labs.find { it.labNumber == labNumber }
            ?: return listOf("ERROR: Lab $labNumber not found in course")

        // Determine target lab (specified or next lab)
        val targetLabNumber = moveToLabNumber ?: (labNumber + 1)
        var targetLab = course.labs.find { it.labNumber == targetLabNumber }

        // Create target lab if it doesn't exist
        if (targetLab == null) {
            targetLab = ScheduledLab(
                labNumber = targetLabNumber,
                startDateTime = labToCancel.startDateTime,
                endDateTime = labToCancel.endDateTime
            )
            course.addLab(targetLab)
            results.add("Created Lab $targetLabNumber")
        }

        // Move problems from cancelled lab to target lab
        val problemsMoved = mutableListOf<String>()
        val problemsToMove = labToCancel.problems.toList() // Copy to avoid concurrent modification
        for (problem in problemsToMove) {
            if (targetLab.problems.none { it.name == problem.name }) {
                labToCancel.removeProblem(problem)
                targetLab.addProblem(problem)
                problemsMoved.add(problem.name)
            } else {
                results.add("Warning: Problem '${problem.name}' already exists in Lab $targetLabNumber, skipping")
            }
        }

        if (problemsMoved.isNotEmpty()) {
            results.add("Moved ${problemsMoved.size} problem(s) to Lab $targetLabNumber: ${problemsMoved.joinToString(", ")}")
        }

        // Remove the cancelled lab
        course.removeLab(labToCancel)
        results.add("Removed Lab $labNumber from schedule")

        courseRepository.save(course)
        return results
    }
}