package com.cs30.cli

import com.cs30.server.service.BestSubmission
import com.cs30.server.service.CanvasClient
import com.cs30.server.service.CanvasException
import com.cs30.server.service.CanvasLabPlan
import com.cs30.server.service.CanvasSubmission
import com.cs30.server.service.CanvasSyncService
import com.cs30.server.service.CanvasUser
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

    // Prefixed cs30- so the course being read from is never confused with the Canvas course
    // being written to, which the --canvas-* options name.
    @Option(names = ["--cs30-course-code"], description = ["cs30 course code (Ex: CS30)"], required = true)
    var code: String = ""

    @Option(names = ["--cs30-year"], description = ["cs30 course year"], required = true)
    var year: Int = 0

    @Option(names = ["--cs30-semester"], description = ["cs30 course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--cs30-section"], description = ["cs30 course section"], required = true)
    var section: Int = 0

    @Option(names = ["--cs30-lab"], description = ["cs30 lab number"], required = true)
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
            val name = canvasAssignmentName(plan.labNumber, problem.name)
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
                        val assignment =
                            canvasClient.createAssignment(course.id, fields + mapOf("name" to name))
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
}

/** Shared so both commands derive the same assignment name from a lab and problem. */
internal fun canvasAssignmentName(labNumber: Int, problemName: String): String = "Lab $labNumber - $problemName"

/**
 * Mirrors each student's best submission into Canvas as a submission comment. Posts no grade: the
 * score is stated in the comment text, matching the previous Kattis workflow.
 *
 * Dry run unless --no-dryrun. A student is skipped when an earlier sync already mirrored a submission
 * at least as new, so re-runs are cheap; --force-comment posts regardless.
 */
@Command(
    name = "submissions2canvas",
    description = ["Mirror best submissions into Canvas as submission comments"],
)
@Component
@Scope("prototype")
class Submissions2Canvas(
    private val canvasSyncService: CanvasSyncService,
    private val canvasClient: CanvasClient,
) : BaseCommand(), Callable<Int> {

    // Prefixed cs30- so the course being read from is never confused with the Canvas course
    // being written to, which the --canvas-* options name.
    @Option(names = ["--cs30-course-code"], description = ["cs30 course code (Ex: CS30)"], required = true)
    var code: String = ""

    @Option(names = ["--cs30-year"], description = ["cs30 course year"], required = true)
    var year: Int = 0

    @Option(names = ["--cs30-semester"], description = ["cs30 course semester"], required = true)
    var semester: String = ""

    @Option(names = ["--cs30-section"], description = ["cs30 course section"], required = true)
    var section: Int = 0

    @Option(names = ["--cs30-lab"], description = ["cs30 lab number"], required = true)
    var lab: Int = 0

    @Option(names = ["--canvas-course"], description = ["Canvas course id, or a name/code to match"], required = true)
    var canvasCourse: String = ""

    @Option(names = ["--dryrun"], description = ["Print planned comments without changing Canvas (the default)"])
    var dryrunRequested: Boolean = false

    @Option(names = ["--no-dryrun"], description = ["Post the comments to Canvas"])
    var applyRequested: Boolean = false

    val dryrun: Boolean get() = !applyRequested

    @Option(
        names = ["--force-comment"],
        negatable = true,
        description = ["Post even when an equally new submission was already mirrored (default: \${DEFAULT-VALUE})"],
    )
    var forceComment: Boolean = false

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
        if (plan.studentEmails.isEmpty()) {
            cli.err("ERROR: $code section $section has no enrolled students")
            return 1
        }

        return try {
            mirror(plan)
        } catch (e: CanvasException) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }

    private fun mirror(plan: CanvasLabPlan): Int {
        val course = canvasClient.findCourse(canvasCourse)
        cli.out("Canvas course: ${course.id} ${course.name}")
        if (dryrun) cli.out("DRY RUN: no comments will be posted (pass --no-dryrun to apply)")

        val usersByEmail = canvasClient.listStudents(course.id)
            .flatMap { user -> identifiersOf(user).map { it to user } }
            .toMap()
        cli.out("Canvas roster: ${usersByEmail.size} identifier(s) for matching")

        val assignments = canvasClient.listAssignments(course.id).associateBy { it.name }
        var posted = 0
        var upToDate = 0
        var noSubmission = 0
        var noCanvasUser = 0

        for (problem in plan.problems) {
            val name = canvasAssignmentName(plan.labNumber, problem.name)
            val assignment = assignments[name]
            if (assignment == null) {
                cli.err("  WARNING: no Canvas assignment named '$name'; run course2canvas first")
                continue
            }
            cli.out("$name (assignment ${assignment.id})")

            // One call per assignment gives every student's last mirrored timestamp.
            val lastSyncedByUser = canvasClient.listSubmissions(course.id, assignment.id)
                .associate { it.userId to lastSyncedTimestamp(it) }

            for (email in plan.studentEmails) {
                val submission = canvasSyncService.bestSubmission(
                    plan.studentGitRepo, plan.section, plan.labNumber, problem.name, email,
                )
                if (submission == null) {
                    noSubmission++
                    continue
                }
                val user = usersByEmail[email.lowercase()]
                if (user == null) {
                    cli.err("  WARNING: no Canvas user for $email; skipping")
                    noCanvasUser++
                    continue
                }
                val lastSynced = lastSyncedByUser[user.id]
                if (!forceComment && lastSynced != null && lastSynced >= submission.submittedAt) {
                    upToDate++
                    continue
                }

                val text = commentFor(problem.name, submission)
                if (dryrun) {
                    cli.out("  would comment for $email (${submission.highestPassed}/${submission.total})")
                    posted++
                } else {
                    canvasClient.postSubmissionComment(course.id, assignment.id, user.id, text)
                    cli.out("  commented for $email (${submission.highestPassed}/${submission.total})")
                    posted++
                }
            }
        }

        val verb = if (dryrun) "would post" else "posted"
        cli.out(
            "Done. $verb $posted comment(s), $upToDate up to date, " +
                "$noSubmission without a submission, $noCanvasUser without a Canvas user"
        )
        if (dryrun) cli.out("Re-run with --no-dryrun to apply.")
        return 0
    }

    /** Match on email and login id, lowercased, since either can carry the sjsu address. */
    private fun identifiersOf(user: CanvasUser): List<String> =
        listOfNotNull(user.email, user.loginId).map { it.lowercase() }

    /**
     * The newest submission timestamp this tool already mirrored, read back out of its own marker.
     * Comparing our recorded timestamps avoids weighing Canvas' comment clock against file times.
     */
    internal fun lastSyncedTimestamp(submission: CanvasSubmission): String? =
        submission.submissionComments.orEmpty()
            .mapNotNull { comment -> MARKER_PATTERN.find(comment.comment ?: "")?.groupValues?.get(1) }
            .maxOrNull()

    internal fun commentFor(problemName: String, submission: BestSubmission): String {
        val percent = if (submission.total > 0) submission.highestPassed * 100 / submission.total else 0
        val header = "[$MARKER ${submission.submittedAt}] Best submission for $problemName: " +
            "${submission.highestPassed}/${submission.total} test cases passed ($percent%), " +
            "submitted ${submission.submittedAt} UTC."
        val bytes = submission.code.toByteArray().size
        return if (bytes <= MAX_INLINE_BYTES) {
            header + "\n<br/>\n<strong>${escapeHtml(submission.fileName)}</strong>\n" +
                "<pre>${escapeHtml(submission.code)}</pre>"
        } else {
            "$header\n<br/>\nSource omitted: ${escapeHtml(submission.fileName)} is $bytes bytes " +
                "(over the $MAX_INLINE_BYTES byte inline limit)."
        }
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private companion object {
        const val MARKER = "cs30-sync"
        const val MAX_INLINE_BYTES = 8 * 1024
        val MARKER_PATTERN = Regex("""\[cs30-sync ([0-9T:-]+)]""")
    }
}
