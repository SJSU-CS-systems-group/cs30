package com.cs30.cli

import com.cs30.server.service.CanvasClient
import com.cs30.server.service.CanvasException
import com.cs30.server.service.CanvasLabPlan
import com.cs30.server.service.CanvasSyncService
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Callable

/**
 * Creates one Canvas assignment per problem in a cs30 lab. Dry run unless --no-dryrun; re-runs match
 * assignments by name and skip them unless --force. Must run as the user that can read the repos,
 * since points_possible comes from the test-case count on disk.
 */
@Command(
    name = "course2canvas",
    description = ["Create Canvas assignments for the problems in a lab"],
)
@Component
@Scope("prototype")
class Course2Canvas(
    private val canvasSyncService: CanvasSyncService,
    private val canvasClient: CanvasClient,
) : BaseCommand(), Callable<Int> {

    @Option(names = ["--course-code"], description = ["Course code (Ex: CS30)"], required = true)
    var code: String = ""

    @Option(names = ["--year"], description = ["Course year"], required = true)
    var year: Int = 0

    @Option(names = ["--semester"], description = ["Course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--section"], description = ["Course section"], required = true)
    var section: Int = 0

    @Option(names = ["--lab"], description = ["Lab number"], required = true)
    var lab: Int = 0

    @Option(names = ["--canvas-course"], description = ["Canvas course id, or a name/code to match"], required = true)
    var canvasCourse: String = ""

    @Option(
        names = ["--canvas-section"],
        description = ["Canvas section name; scopes the assignment dates to that section only"],
    )
    var canvasSection: String? = null

    @Option(names = ["--assignment-group"], description = ["Canvas assignment group (default: \${DEFAULT-VALUE})"])
    var assignmentGroup: String = "cs30"

    @Option(names = ["--rubric"], description = ["Title of an existing Canvas rubric to attach to each assignment"])
    var rubric: String? = null

    // Two plain flags rather than one negatable option. picocli sets a matched flag to the opposite
    // of its default, so a negatable option defaulting to true would treat --no-dryrun as a second
    // negation and leave the dry run on. Being explicit keeps "no flags means no changes" true.
    @Option(names = ["--dryrun"], description = ["Print planned actions without changing Canvas (the default)"])
    var dryrunRequested: Boolean = false

    @Option(names = ["--no-dryrun"], description = ["Apply the planned changes to Canvas"])
    var applyRequested: Boolean = false

    /** Anything short of an explicit --no-dryrun is a dry run. */
    val dryrun: Boolean get() = !applyRequested

    // Defaults to false, so the negatable form behaves as expected here.
    @Option(
        names = ["--force"],
        negatable = true,
        description = ["Update assignments that already exist (default: \${DEFAULT-VALUE})"],
    )
    var force: Boolean = false

    override fun call(): Int {
        if (dryrunRequested && applyRequested) {
            cli.err("ERROR: --dryrun and --no-dryrun are mutually exclusive")
            return 1
        }
        val plan = try {
            canvasSyncService.labPlan(code, year, semester, section, lab)
        } catch (e: IllegalArgumentException) {
            cli.err("ERROR: ${e.message}")
            return 1
        }
        if (plan.problems.isEmpty()) {
            cli.err("ERROR: lab $lab in $code section $section has no problems")
            return 1
        }

        return try {
            sync(plan)
        } catch (e: CanvasException) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }

    private fun sync(plan: CanvasLabPlan): Int {
        val course = canvasClient.findCourse(canvasCourse)
        cli.out("Canvas course: ${course.id} ${course.name}")
        if (dryrun) cli.out("DRY RUN: no changes will be made (pass --no-dryrun to apply)")

        // Section overrides let one Canvas course carry different lab windows per cs30 section.
        val sectionId = canvasSection?.let {
            val resolved = canvasClient.findSection(course.id, it)
            cli.out("Canvas section: ${resolved.id} ${resolved.name} (dates scoped to this section)")
            resolved.id
        }

        val groupId = resolveAssignmentGroup(course.id)
        val rubricId = rubric?.let {
            val resolved = canvasClient.findRubric(course.id, it)
            cli.out("Rubric: ${resolved.id} '${resolved.title}'")
            resolved.id
        }

        val unlockAt = isoUtc(plan.startDateTime)
        val dueAt = isoUtc(plan.endDateTime)
        cli.out("Lab $lab window: $unlockAt .. $dueAt")

        val existing = canvasClient.listAssignments(course.id).associateBy { it.name }
        var created = 0
        var updated = 0
        var skipped = 0
        var attached = 0

        for (problem in plan.problems) {
            val name = assignmentName(plan.labNumber, problem.name)
            if (problem.pointsPossible == null) {
                cli.err(
                    "  WARNING: could not determine test-case count for $name " +
                        "(no submissions and no readable package); points_possible left unset"
                )
            } else if (problem.pointsSource == "package") {
                cli.out("  note: $name points from package test-case count (no submissions yet)")
            }

            val fields = buildFields(problem.pointsPossible, problem.note, groupId, unlockAt, dueAt, sectionId)
            val found = existing[name]

            when {
                found == null -> {
                    if (dryrun) {
                        cli.out("  would create $name (points: ${problem.pointsPossible ?: "unset"})")
                        created++
                    } else {
                        val assignment = canvasClient.createAssignment(course.id, fields + mapOf("name" to name))
                        cli.out("  created $name (id ${assignment.id}, points: ${problem.pointsPossible ?: "unset"})")
                        created++
                        if (rubricId != null) {
                            canvasClient.attachRubric(course.id, rubricId, assignment.id)
                            cli.out("    attached rubric to $name")
                            attached++
                        }
                    }
                }
                force -> {
                    if (dryrun) {
                        cli.out("  would update $name (id ${found.id})")
                        updated++
                    } else {
                        canvasClient.updateAssignment(course.id, found.id, fields)
                        cli.out("  updated $name (id ${found.id})")
                        updated++
                        if (rubricId != null) {
                            canvasClient.attachRubric(course.id, rubricId, found.id)
                            cli.out("    attached rubric to $name")
                            attached++
                        }
                    }
                }
                else -> {
                    cli.out("  exists, skipping $name (id ${found.id}); use --force to update")
                    skipped++
                }
            }
        }

        val prefix = if (dryrun) "Would create" else "Created"
        cli.out(
            "Done. ${plan.problems.size} problem(s): $prefix $created, updated $updated, skipped $skipped" +
                if (rubric != null) ", rubric attachments $attached" else ""
        )
        if (dryrun) cli.out("Re-run with --no-dryrun to apply.")
        return 0
    }

    /** Find the assignment group by name, creating it only for a real run. */
    private fun resolveAssignmentGroup(courseId: Long): Long? {
        canvasClient.findAssignmentGroup(courseId, assignmentGroup)?.let {
            cli.out("Assignment group: ${it.id} '${it.name}'")
            return it.id
        }
        if (dryrun) {
            cli.out("  would create assignment group '$assignmentGroup'")
            return null
        }
        val group = canvasClient.createAssignmentGroup(courseId, assignmentGroup)
        cli.out("Assignment group: ${group.id} '${group.name}' (created)")
        return group.id
    }

    /** With --canvas-section the dates go into a section override instead of onto the assignment. */
    private fun buildFields(
        points: Int?,
        note: String?,
        groupId: Long?,
        unlockAt: String,
        dueAt: String,
        sectionId: Long?,
    ): Map<String, Any?> {
        // submission_types is deliberately not set: Canvas defaults a new assignment to "none",
        // which still accepts the submission comments that submissions2canvas posts, and leaving it
        // out means --force cannot clobber the type of an assignment someone configured by hand.
        val fields = mutableMapOf<String, Any?>(
            "published" to true,
        )
        if (points != null) fields["points_possible"] = points
        if (!note.isNullOrBlank()) fields["description"] = note
        if (groupId != null) fields["assignment_group_id"] = groupId

        if (sectionId != null) {
            fields["only_visible_to_overrides"] = true
            fields["assignment_overrides"] = listOf(
                mapOf(
                    "course_section_id" to sectionId,
                    "unlock_at" to unlockAt,
                    "due_at" to dueAt,
                    "lock_at" to dueAt,
                )
            )
        } else {
            fields["unlock_at"] = unlockAt
            fields["due_at"] = dueAt
            fields["lock_at"] = dueAt
        }
        return fields
    }

    /** Lab times are stored as UTC wall-clock, so no app-timezone conversion. */
    private fun isoUtc(dateTime: LocalDateTime): String = dateTime.atOffset(ZoneOffset.UTC).toString()

    private fun assignmentName(labNumber: Int, problemName: String): String = "Lab $labNumber - $problemName"
}
