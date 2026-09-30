package com.cs30.server.service

import com.cs30.server.dto.CourseInput
import com.cs30.server.dto.LabInput
import com.cs30.server.dto.ProblemInput
import com.cs30.server.dto.SectionInput
import com.cs30.server.models.Course
import com.cs30.server.repository.CourseRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Renders a course's `course.yml` from the database, and owns the mapper that reads one back.
 *
 * The database is the source of truth; this is how it is written down. `addcourse` parses with
 * [mapper] and CourseYamlSyncService emits with [render], so export and import cannot drift apart:
 * re-importing a file this class produced is a no-op rather than a roster wipe (issue #252).
 *
 * Two conversions are lossy in the first pass only, and deliberately so:
 *  - `startDate`/`endDate` are dates, so a re-import re-derives midnight. Every write path today
 *    already stores app-zone midnight, so this is a fixed point in practice.
 *  - A lab whose wall-clock time lands in the DST fall-back repeated hour re-imports at the earlier
 *    offset, because that is how [AppTimeZoneService.toUtc] resolves the ambiguity.
 */
@Service
open class CourseYamlService(
    private val courseRepository: CourseRepository,
    private val appTimeZoneService: AppTimeZoneService,
) {
    private val log = LoggerFactory.getLogger(CourseYamlService::class.java)

    /**
     * The one mapper for both directions. Modules are registered explicitly rather than through
     * findAndRegisterModules(): that is a classpath scan, and the backend and the CLI run with
     * different classpaths, so a scan would let the same course render differently in each process.
     */
    val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .registerModule(JavaTimeModule())

    /**
     * Every section of a course, in render order, with everything the renderer needs initialized.
     *
     * The only transactional entry point here on purpose: a convenience wrapper that called this
     * from inside the class would bypass the proxy and get no transaction at all.
     *
     * REQUIRES_NEW because the sync reads here from a transaction's after-completion phase: the
     * caller's EntityManager is still bound to the thread but its transaction is finished, so
     * joining it would read through a dead one. Everything the renderer touches is initialized
     * before this returns, so the entities are safe to read once detached.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    open fun findSections(code: String, year: Int, semester: String): List<Course> {
        val sections = courseRepository.findByCodeAndYearAndSemester(code, year, semester)
            .sortedBy { it.section }
        // students and taEmails are lazy @ElementCollections - touch them before the transaction
        // closes, since the sync renders after commit, with open-in-view off.
        sections.forEach { it.students.size; it.taEmails.size }
        return sections
    }

    fun render(sections: List<Course>): String = mapper.writeValueAsString(toInput(sections))

    /**
     * Maps entities to the yml model.
     *
     * Every ordering here is total, ending in a unique tiebreaker, because the sync commits only
     * when the rendered bytes change: a comparator that leaves ties would let Hibernate's fetch
     * order churn the file (and its git history) with no actual change. `lab.problems` has no
     * @OrderColumn, so its fetch order is not deterministic to begin with.
     */
    fun toInput(sections: List<Course>): CourseInput {
        require(sections.isNotEmpty()) { "cannot render course.yml for zero sections" }
        val ordered = sections.sortedBy { it.section }
        val first = ordered.first()

        // The course-level fields are per-section columns in the database but single values in the
        // file, so a divergence would be silently flattened on the next re-import. Say so.
        ordered.filter {
            it.language != first.language || it.startDate != first.startDate ||
                it.endDate != first.endDate || it.studentGitRepo != first.studentGitRepo ||
                it.problemGitRepo != first.problemGitRepo
        }.forEach {
            log.warn(
                "course.yml: section {} of {} {} {} disagrees with section {} on course-level " +
                    "settings (dates/repos/language); the file keeps section {}'s values",
                it.section, first.code, first.semester, first.year, first.section, first.section
            )
        }

        return CourseInput(
            code = first.code,
            year = first.year,
            semester = first.semester,
            startDate = appTimeZoneService.toAppZone(first.startDate).toLocalDate(),
            endDate = appTimeZoneService.toAppZone(first.endDate).toLocalDate(),
            studentGitRepo = first.studentGitRepo,
            problemGitRepo = first.problemGitRepo,
            language = first.language,
            sections = ordered.map { course ->
                SectionInput(
                    number = course.section,
                    tas = course.taEmails.sorted(),
                    students = course.students.sorted(),
                    labs = course.labs
                        .sortedWith(compareBy({ it.labNumber }, { it.startDateTime }, { it.id }))
                        .map { lab ->
                            LabInput(
                                number = lab.labNumber,
                                startDateTime = appTimeZoneService.toAppZone(lab.startDateTime),
                                endDateTime = appTimeZoneService.toAppZone(lab.endDateTime),
                                problems = lab.problems
                                    .sortedWith(compareBy({ it.name }, { it.id }))
                                    .map { ProblemInput(it.name, it.language, it.note?.takeIf { n -> n.isNotBlank() }) }
                            )
                        }
                )
            }
        )
    }
}
