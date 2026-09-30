package com.cs30.server.service

import com.cs30.server.dto.CourseInput
import com.cs30.server.models.Course
import com.cs30.server.models.Problem
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import com.cs30.server.repository.LoginSessionRepository
import com.cs30.server.repository.activeCourses
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class CourseService(
    private val courseRepository: CourseRepository,
    private val loginSessionRepository: LoginSessionRepository,
    private val appTimeZoneService: AppTimeZoneService,
    private val courseYamlSync: CourseYamlSyncService,
) {
    private val log = LoggerFactory.getLogger(CourseService::class.java)

    /** Appended to "course not found" messages so a typo'd code/year/semester/section still gets the caller pointed at valid options. */
    fun currentOrFutureCoursesSuffix(): String {
        val upcoming = courseRepository.activeCourses()
        if (upcoming.isEmpty()) return ""
        return "\nCurrent/future courses:\n" + upcoming.joinToString("\n") { "  - ${it.describe()}" }
    }

    @Transactional
    open fun createCourseWithStudents(
        courseName: String,
        courseSection: Int,
        year: Int,
        semester: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        studentGitRepo: String,
        problemGitRepo: String,
        language: String,
        taEmails: List<String>,
        students: List<String>,
        labs: List<ScheduledLab>
    ) {
        val course = Course(
            code = courseName,
            section = courseSection,
            year = year,
            semester = semester,
            startDate = startDate,
            endDate = endDate,
            language = language,
            studentGitRepo = studentGitRepo,
            problemGitRepo = problemGitRepo,
        )

        course.taEmails.addAll(taEmails)
        for (email in students) {
            course.students.add(email)
        }
        for (lab in labs) {
            course.addLab(lab)
        }
        courseRepository.save(course)
        courseYamlSync.requestSync(course)
    }

    /**
     * Re-applies a course.yml onto an existing course. The roster in the file replaces the roster in
     * the database, so anyone enrolled since the file was written is dropped - that is how three
     * students silently disappeared in issue #252. The sync keeps the file current, so a re-import
     * now has nothing to drop.
     */
    @Transactional
    open fun updateCourseWithStudents(
        courseId: String,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        studentGitRepo: String,
        problemGitRepo: String,
        language: String,
        taEmails: List<String>,
        students: List<String>,
        labs: List<ScheduledLab>
    ): List<String> {
        val course = courseRepository.findById(courseId).orElseThrow()

        course.startDate = startDate
        course.endDate = endDate
        course.studentGitRepo = studentGitRepo
        course.problemGitRepo = problemGitRepo
        course.language = language
        // The file lists the section's TAs in full, so it replaces rather than adds.
        val removedTas = course.taEmails.filter { old -> taEmails.none { it.equals(old, ignoreCase = true) } }
        course.taEmails.clear()
        course.taEmails.addAll(taEmails)

        val oldStudents = course.students.toMutableList()
        course.students.clear()

        for (email in students) {
            if (!oldStudents.contains(email)) {
                log.info("Added student to course: {}", email)
            }
            oldStudents.remove(email)
            course.students.add(email)
        }

        for (email in oldStudents) {
            log.info("Removed student from course: {}", email)
        }

        // Update labs while preserving problems
        val oldLabsMap = course.labs.associateBy { it.labNumber }
        val newLabNumbers = labs.map { it.labNumber }.toSet()

        // Remove labs that are no longer in the new list
        val labsToRemove = course.labs.filter { it.labNumber !in newLabNumbers }.toList()
        for (oldLab in labsToRemove) {
            if (oldLab.problems.isNotEmpty()) {
                log.warn("Lab {} removed (had {} problems)", oldLab.labNumber, oldLab.problems.size)
            }
            course.removeLab(oldLab)
        }

        // Update existing labs or add new ones
        for (newLab in labs) {
            val existingLab = oldLabsMap[newLab.labNumber]
            if (existingLab != null) {
                // Update times on existing lab
                if (existingLab.startDateTime != newLab.startDateTime || existingLab.endDateTime != newLab.endDateTime) {
                    existingLab.startDateTime = newLab.startDateTime
                    existingLab.endDateTime = newLab.endDateTime
                    log.info("Updated Lab {} times", newLab.labNumber)
                }
                // Sync problems: add new, update existing, remove deleted
                val existingProblemsByName = existingLab.problems.associateBy { it.name }
                val newProblemNames = newLab.problems.map { it.name }.toSet()

                // Remove problems no longer in the YAML
                val problemsToRemove = existingLab.problems.filter { it.name !in newProblemNames }.toList()
                for (problem in problemsToRemove) {
                    existingLab.removeProblem(problem)
                    log.info("Removed problem '{}' from Lab {}", problem.name, newLab.labNumber)
                }

                // Add new problems or update language of existing ones
                for (problem in newLab.problems) {
                    val existingProblem = existingProblemsByName[problem.name]
                    if (existingProblem != null) {
                        // Update language if changed
                        if (existingProblem.language != problem.language) {
                            log.info("Updated problem '{}' language: {} -> {}", problem.name, existingProblem.language, problem.language)
                            existingProblem.language = problem.language
                        }
                        // Update note if changed
                        if (existingProblem.note != problem.note) {
                            println("  Updated problem '${problem.name}' note: ${existingProblem.note} -> ${problem.note}")
                            existingProblem.note = problem.note
                        }
                    } else {
                        existingLab.addProblem(problem)
                        log.info("Added problem '{}' to Lab {}", problem.name, newLab.labNumber)
                    }
                }
            } else {
                // Add new lab (with its problems)
                course.addLab(newLab)
                log.info("Added new Lab {} with {} problem(s)", newLab.labNumber, newLab.problems.size)
            }
        }

        courseRepository.save(course)
        courseYamlSync.requestSync(course)

        // Returned rather than only logged: addcourse prints these, so whoever runs an import sees who it
        // took off the roster. oldStudents now holds exactly the students the file no longer lists.
        return oldStudents.sorted().map { "Removed student $it" } + removedTas.sorted().map { "Removed TA $it" }
    }

    @Transactional
    open fun addStudentToCourse(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section, Semester $semester, Year $year)${currentOrFutureCoursesSuffix()}"
        if (course.students.contains(email)) {
            return "Student $email is already enrolled in $code (Section $section, Semester $semester, Year $year)"
        }
        course.students.add(email)
        courseRepository.save(course)
        courseYamlSync.requestSync(course)
        return "Added student $email to course $code (Section $section, Semester $semester, Year $year)"
    }

    @Transactional
    open fun removeStudentFromCourse(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section, Semester $semester, Year $year)${currentOrFutureCoursesSuffix()}"
        if (!course.students.contains(email)) {
            return "Student $email is not enrolled in $code (Section $section, Semester $semester, Year $year)"
        }
        course.students.remove(email)
        courseRepository.save(course)
        courseYamlSync.requestSync(course)
        return "Removed student $email from course $code (Section $section, Semester $semester, Year $year)"
    }

    @Transactional
    open fun removeCourse(code: String, year: Int, semester: String, section: String): List<String> {
        val results = mutableListOf<String>()
        val courses: List<Course> = if (section.equals("all", ignoreCase = true)) {
            courseRepository.findByCodeAndYearAndSemester(code, year, semester)
        } else {
            val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt())
            if (course == null) {
                return listOf("Course not found: $code (Section $section, Semester $semester, Year $year)${currentOrFutureCoursesSuffix()}")
            }
            listOf(course)
        }

        for (course in courses) {
            if (course.endDate.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                results.add("Cannot delete course ${course.code} (Section ${course.section}, Semester $semester, Year $year) because it has not ended yet")
            } else {
                // The path has to be read before the delete: afterwards there is no row to read it from.
                val target = CourseYamlTarget(course)
                loginSessionRepository.deleteByCourseId(course.id)
                courseRepository.delete(course)
                courseYamlSync.requestSync(target)
                log.info("Cleared login sessions for course {}", course.id)
                results.add("Deleted course ${course.code} (Section ${course.section}, Semester $semester, Year $year)")
            }
        }
        return results
    }

    @Transactional
    open fun findCourse(code: String, year: Int, semester: String, section: String): List<String> {
        val results = mutableListOf<String>()
        val courses: List<Course> = if (section.equals("all", ignoreCase = true)) {
            val temp = courseRepository.findByCodeAndYearAndSemester(code, year, semester)
            if (temp.isEmpty()) {
                return listOf("ERROR: No courses found for code: $code, year: $year, semester: $semester${currentOrFutureCoursesSuffix()}")
            }
            temp
        } else {
            val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt())
            if (course == null) {
                return listOf("ERROR: Course not found: $code (Section $section, Semester $semester, Year $year)${currentOrFutureCoursesSuffix()}")
            }
            listOf(course)
        }

        for (course in courses) {
            results.add("Course: ${course.code} (Section ${course.section})")
            results.add("  Year: ${course.year}")
            results.add("  Semester: ${course.semester}")
            results.add("  Start Date: ${course.startDate.toLocalDate()}")
            results.add("  End Date: ${course.endDate.toLocalDate()}")
            results.add("  Problem Git Repository: ${course.problemGitRepo}")
            results.add("  Student Git Repository: ${course.studentGitRepo}")
            results.add("  TAs: ${course.taEmails.sorted().joinToString(", ").ifEmpty { "(none)" }}")
            results.add("  Labs: ${course.labs.size}")
            for (lab in course.labs) {
                results.add("    - Lab ${lab.labNumber}: ${lab.startDateTime} to ${lab.endDateTime}")
            }
            results.add("  Students enrolled: ${course.students.size}")
            for (email in course.students) {
                results.add("    - $email")
            }
        }
        return results
    }

    @Transactional
    open fun findStudent(email: String): List<String> {
        val courses = courseRepository.findByStudentEmail(email)
        if (courses.isEmpty()) {
            return listOf("ERROR: No courses found for student: $email")
        }
        val results = mutableListOf<String>()
        results.add("Student: $email")
        results.add("Enrolled in ${courses.size} course(s):")
        for (course in courses) {
            results.add("  - ${course.code} (Section ${course.section})")
        }
        return results
    }

    /**
     * Applies a whole course.yml - every section, create or update - in one transaction, so the
     * course.yml sync writes and commits once per import instead of once per section.
     */
    @Transactional
    open fun applyCourseFile(input: CourseInput): List<String> {
        val results = mutableListOf<String>()
        val startDate = appTimeZoneService.toUtc(input.startDate.atStartOfDay())
        val endDate = appTimeZoneService.toUtc(input.endDate.atStartOfDay())

        for (sectionInput in input.sections) {
            val labs = sectionInput.labs.map { labInput ->
                val lab = ScheduledLab(
                    labNumber = labInput.number,
                    startDateTime = appTimeZoneService.toUtc(labInput.startDateTime),
                    endDateTime = appTimeZoneService.toUtc(labInput.endDateTime)
                )
                for (problemInput in labInput.problems) {
                    lab.addProblem(
                        Problem(
                            name = problemInput.name,
                            // A problem with no language of its own takes the course default.
                            language = problemInput.language?.takeIf { it.isNotBlank() } ?: input.language,
                            note = problemInput.note
                        )
                    )
                }
                lab
            }

            val existing = courseRepository.findByCodeAndYearAndSemesterAndSection(
                input.code, input.year, input.semester, sectionInput.number
            )
            if (existing != null) {
                val removals = updateCourseWithStudents(
                    existing.id, startDate, endDate, input.studentGitRepo, input.problemGitRepo,
                    input.language, sectionInput.taEmails(), sectionInput.students, labs
                )
                results.add(
                    "Updated course: ${input.code} (Section ${sectionInput.number}) with " +
                        "${sectionInput.students.size} students and ${labs.size} labs"
                )
                removals.forEach { results.add("  $it") }
            } else {
                createCourseWithStudents(
                    input.code, sectionInput.number, input.year, input.semester, startDate, endDate,
                    input.studentGitRepo, input.problemGitRepo, input.language, sectionInput.taEmails(),
                    sectionInput.students, labs
                )
                results.add(
                    "Added course: ${input.code} (Section ${sectionInput.number}) with " +
                        "${sectionInput.students.size} students and ${labs.size} labs"
                )
            }
        }
        return results
    }

    /** Moves a course's end date. One transaction for "all" so the course.yml is written once. */
    @Transactional
    open fun changeEndDate(
        code: String,
        year: Int,
        semester: String,
        section: String,
        newEndDate: LocalDateTime
    ): List<String> {
        val courses: List<Course> = if (section.equals("all", ignoreCase = true)) {
            courseRepository.findByCodeAndYearAndSemester(code, year, semester)
        } else {
            listOfNotNull(courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section.toInt()))
        }
        if (courses.isEmpty()) {
            return listOf("ERROR: Course not found: $code (Section $section)${currentOrFutureCoursesSuffix()}")
        }

        return courses.map { course ->
            course.endDate = newEndDate
            courseRepository.save(course)
            courseYamlSync.requestSync(course)
            "Updated end date for ${course.code} (Section ${course.section}) to " +
                appTimeZoneService.toAppZone(newEndDate).toLocalDate()
        }
    }

    /** Adds one TA, leaving any already assigned in place. A section may have several. */
    @Transactional
    open fun addTA(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section, Semester $semester, Year $year)${currentOrFutureCoursesSuffix()}"
        val where = "$code (Section $section, Semester $semester, Year $year)"
        // Matched case-insensitively so the same person is not added twice under different casing.
        if (course.taEmails.any { it.equals(email, ignoreCase = true) }) {
            return "TA $email is already assigned to $where"
        }
        course.taEmails.add(email)
        courseRepository.save(course)
        courseYamlSync.requestSync(course)
        return "Added TA $email to course $where"
    }

    @Transactional
    open fun removeTA(code: String, year: Int, semester: String, section: Int, email: String): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "Course not found: $code (Section $section, Semester $semester, Year $year)${currentOrFutureCoursesSuffix()}"
        val where = "$code (Section $section, Semester $semester, Year $year)"
        val existing = course.taEmails.firstOrNull { it.equals(email, ignoreCase = true) }
            ?: return "TA $email is not assigned to $where"
        course.taEmails.remove(existing)
        courseRepository.save(course)
        courseYamlSync.requestSync(course)
        return "Removed TA $existing from course $where"
    }

    @Transactional
    open fun addLab(
        code: String,
        year: Int,
        semester: String,
        section: Int,
        lab: ScheduledLab
    ): String {
        val course = courseRepository.findByCodeAndYearAndSemesterAndSection(code, year, semester, section)
            ?: return "ERROR: Course not found: $code (Section $section, Semester $semester, Year $year)${currentOrFutureCoursesSuffix()}"

        // Check if lab with this number already exists
        val existingLab = course.labs.find { it.labNumber == lab.labNumber }
        if (existingLab != null) {
            // Update existing lab
            existingLab.startDateTime = lab.startDateTime
            existingLab.endDateTime = lab.endDateTime

            // Sync problems
            val existingProblemsByName = existingLab.problems.associateBy { it.name }
            val newProblemNames = lab.problems.map { it.name }.toSet()

            // Remove problems no longer in the input
            val problemsToRemove = existingLab.problems.filter { it.name !in newProblemNames }.toList()
            for (problem in problemsToRemove) {
                existingLab.removeProblem(problem)
            }

            // Add new problems or update existing ones
            for (problem in lab.problems) {
                val existingProblem = existingProblemsByName[problem.name]
                if (existingProblem != null) {
                    existingProblem.language = problem.language
                    existingProblem.note = problem.note
                } else {
                    existingLab.addProblem(problem)
                }
            }

            courseRepository.save(course)
            courseYamlSync.requestSync(course)
            return "Updated Lab ${lab.labNumber} in $code (Section $section) with ${lab.problems.size} problem(s)"
        } else {
            // Add new lab
            course.addLab(lab)
            courseRepository.save(course)
            courseYamlSync.requestSync(course)
            return "Added Lab ${lab.labNumber} to $code (Section $section) with ${lab.problems.size} problem(s)"
        }
    }
}