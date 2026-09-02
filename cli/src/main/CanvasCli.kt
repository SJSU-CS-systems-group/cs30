package com.cs30.cli

import com.cs30.server.dto.BestSubmission
import com.cs30.server.dto.CanvasLabPlan
import com.cs30.server.dto.CanvasProblemPlan
import com.cs30.server.dto.CourseQuery
import com.cs30.server.dto.CourseRef
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Callable

/**
 * Creates one Canvas assignment per problem in a cs30 lab. Dry run unless --no-dryrun; re-runs match
 * assignments by name and skip them unless --force.
 *
 * Every assignment is worth the same 100 points. Grades are entered by hand, so the test-case counts
 * are reported in the submission comments rather than turned into a score.
 *
 * Reads the lab through the server (Cs30ApiClient) rather than the database, so it runs anywhere
 * the server can be reached - see main(), which starts it without the Spring application.
 */
@Command(
    name = Course2Canvas.NAME,
    description = ["Create Canvas assignments for the problems in a lab"],
)
class Course2Canvas() : BaseCommand(), Callable<Int> {

    /**
     * Set by main() with real clients, or by tests with mocks. picocli builds the command without
     * them for the help listing, and never runs it that way.
     */
    lateinit var cs30: Cs30ApiClient
    lateinit var canvasClient: CanvasClient

    constructor(cs30: Cs30ApiClient, canvasClient: CanvasClient) : this() {
        this.cs30 = cs30
        this.canvasClient = canvasClient
    }

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
    var assignmentGroup: String = "Labs"

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
            cs30.labPlan(code, year, semester, section, lab)
        } catch (e: Cs30ApiException) {
            cli.err("ERROR: ${e.message}")
            return 1
        }
        if (plan.problems.isEmpty()) {
            cli.err("ERROR: lab $lab in $code section $section has no problems")
            return 1
        }
        if (reportCollisions(cli, plan.labNumber, plan.problems)) return 1

        return try {
            sync(plan)
        } catch (e: CanvasException) {
            cli.err("ERROR: ${e.message}")
            1
        } catch (e: Cs30ApiException) {
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

        val existing = canvasClient.listAssignments(course.id)
            .associateBy { normalizeAssignmentName(it.name) }
        var created = 0
        var updated = 0
        var skipped = 0
        var attached = 0

        for (problem in plan.problems) {
            val name = canvasAssignmentName(plan.labNumber, problem.note)
            val fields = buildFields(problem.note, groupId, unlockAt, dueAt, sectionId)
            val found = existing[normalizeAssignmentName(name)]

            when {
                found == null -> {
                    if (dryrun) {
                        cli.out("  would create $name (points: $POINTS_POSSIBLE)")
                        created++
                    } else {
                        val assignment =
                            canvasClient.createAssignment(course.id, fields + mapOf("name" to name))
                        cli.out("  created $name (id ${assignment.id}, points: $POINTS_POSSIBLE)")
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
            "points_possible" to POINTS_POSSIBLE,
        )
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

    /**
     * Lab times are stored as UTC wall-clock, so no app-timezone conversion. Formatted via Instant
     * rather than OffsetDateTime.toString(), which drops the seconds when they are zero and yields
     * 10:00Z, a form Canvas rejects as an invalid datetime.
     */
    internal fun isoUtc(dateTime: LocalDateTime): String = dateTime.toInstant(ZoneOffset.UTC).toString()

    internal companion object {
        const val NAME = "course2canvas"

        /** Same scale for every assignment, so the professor grades on a familiar 100 points. */
        const val POINTS_POSSIBLE = 100
    }
}

/** Shared so both commands derive the same assignment name from a lab and problem. */
/**
 * The Canvas assignment a problem belongs to: LAB<n>, with the first word of the problem's note
 * appended when it has one, so a lab's bonus problem lands on LAB<n>-Bonus rather than LAB<n>.
 * Assignments are often created in Canvas by hand, so this has to reproduce that convention exactly.
 */
internal fun canvasAssignmentName(labNumber: Int, note: String?): String {
    val suffix = note?.trim()?.split(Regex("\\s+"))?.firstOrNull()
        ?.trim { !it.isLetterOrDigit() }
        ?.takeIf { it.isNotEmpty() }
    // Two digits, so a lab sorts and reads the same whether it is 1 or 11.
    val lab = "LAB%02d".format(labNumber)
    return if (suffix == null) lab else "$lab-$suffix"
}

/** Canvas assignment names are compared case-insensitively, since they are often typed by hand. */
internal fun normalizeAssignmentName(name: String): String = name.trim().lowercase()

/**
 * Each problem must resolve to its own assignment. Two problems sharing a name would silently sync
 * to the same Canvas assignment, so this reports it instead, naming the problems involved.
 */
internal fun assignmentNameCollisions(
    labNumber: Int,
    problems: List<CanvasProblemPlan>,
): Map<String, List<String>> =
    problems.groupBy { normalizeAssignmentName(canvasAssignmentName(labNumber, it.note)) }
        .filterValues { it.size > 1 }
        .map { (_, colliding) ->
            canvasAssignmentName(labNumber, colliding.first().note) to colliding.map { it.name }
        }
        .toMap()

/**
 * Reports colliding assignment names, returning true when the caller should stop. Only the note
 * distinguishes one problem's assignment from another's, so a collision means notes are missing.
 */
internal fun reportCollisions(cli: CliOptions, labNumber: Int, problems: List<CanvasProblemPlan>): Boolean {
    val collisions = assignmentNameCollisions(labNumber, problems)
    collisions.forEach { (name, problemNames) ->
        cli.err(
            "ERROR: problems ${problemNames.joinToString(", ")} all map to assignment '$name'. " +
                "The first word of a problem's note is what separates them, so give each problem " +
                "in the lab a distinct note (at most one may have none)."
        )
    }
    return collisions.isNotEmpty()
}

/** One item per line, indented under the message, or "(none)" so an empty list is still visible. */
internal fun <T> bulletList(items: List<T>, line: (T) -> String): String =
    if (items.isEmpty()) " (none)" else items.joinToString("") { "\n  - ${line(it)}" }

/**
 * Mirrors each student's best submission into Canvas as a submission comment. Posts no grade: the
 * score is stated in the comment text, matching the previous Kattis workflow.
 *
 * Dry run unless --no-dryrun. A student is skipped when an earlier sync already mirrored a submission
 * at least as new, so re-runs are cheap; --force-comment posts regardless.
 *
 * Students are matched to Canvas users by email, except those with a student override on the
 * server (see AddOverride), which are matched by Canvas student id instead.
 *
 * The submissions come through the server (Cs30ApiClient), one call per problem, so this never
 * needs the student repository - it runs anywhere the server can be reached.
 *
 * Both courses may be named by a fragment: the server matches the cs30 code as a substring, with
 * the other --cs30-* options only narrowing the match, and the Canvas name/code is matched the
 * same way here. A fragment that fits several courses is an error listing them, and one that fits
 * none lists the active courses to pick from.
 */
@Command(
    name = Submissions2Canvas.NAME,
    description = ["Mirror best submissions into Canvas as submission comments"],
)
class Submissions2Canvas() : BaseCommand(), Callable<Int> {

    /** See Course2Canvas: set by main() or by tests, absent only in the help listing. */
    lateinit var cs30: Cs30ApiClient
    lateinit var canvasClient: CanvasClient

    constructor(cs30: Cs30ApiClient, canvasClient: CanvasClient) : this() {
        this.cs30 = cs30
        this.canvasClient = canvasClient
    }

    // Prefixed cs30- so the course being read from is never confused with the Canvas course
    // being written to, which the --canvas-* options name.
    @Option(
        names = ["--cs30-course-code"],
        description = ["cs30 course code, or a fragment of one that matches a single course (Ex: CS30)"],
        required = true,
    )
    var code: String = ""

    // Nullable so an omitted filter is not applied, rather than filtering on year 0 or section 0.
    @Option(names = ["--cs30-year"], description = ["cs30 course year; narrows the match"])
    var year: Int? = null

    @Option(
        names = ["--cs30-semester"],
        description = ["cs30 course semester, or a fragment of one; narrows the match"],
    )
    var semester: String? = null

    @Option(names = ["--cs30-section"], description = ["cs30 course section; narrows the match"])
    var section: Int? = null

    @Option(names = ["--cs30-lab"], description = ["cs30 lab number"], required = true)
    var lab: Int = 0

    @Option(
        names = ["--canvas-course"],
        description = ["Canvas course id, or a fragment of the name/code that matches a single course"],
        required = true,
    )
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
        val (course, plan) = try {
            val course = resolveCourse() ?: return 1
            // Said up front, so the user sees which course a fragment picked.
            cli.out("cs30 course: ${course.describe()}")
            course to cs30.labPlan(course.code, course.year, course.semester, course.section, lab)
        } catch (e: Cs30ApiException) {
            cli.err("ERROR: ${e.message}")
            return 1
        }
        if (plan.problems.isEmpty()) {
            cli.err("ERROR: lab $lab in ${course.describe()} has no problems")
            return 1
        }
        if (reportCollisions(cli, plan.labNumber, plan.problems)) return 1
        if (plan.studentEmails.isEmpty()) {
            cli.err("ERROR: ${course.describe()} has no enrolled students")
            return 1
        }

        return try {
            mirror(course, plan)
        } catch (e: CanvasException) {
            cli.err("ERROR: ${e.message}")
            1
        } catch (e: Cs30ApiException) {
            cli.err("ERROR: ${e.message}")
            1
        }
    }

    /**
     * The one cs30 course the --cs30-* options name, or null after saying why there is not exactly
     * one: several fits are listed with how to narrow them, none lists the active courses to pick
     * from. The server does the matching, over the courses this token may see.
     */
    private fun resolveCourse(): CourseRef? {
        val query = CourseQuery(code, year, semester, section)
        val matches = cs30.findCourses(query)
        when (matches.size) {
            1 -> return matches.single()
            0 -> cli.err(
                "ERROR: no cs30 course matches $query. Active courses:" +
                    bulletList(cs30.findCourses(CourseQuery(active = true))) { it.describe() }
            )
            else -> cli.err(
                "ERROR: multiple cs30 courses match $query:" + bulletList(matches) { it.describe() } +
                    "\nNarrow it with --cs30-year, --cs30-semester or --cs30-section."
            )
        }
        return null
    }

    private fun mirror(cs30Course: CourseRef, plan: CanvasLabPlan): Int {
        val course = canvasClient.findCourse(canvasCourse)
        cli.out("Canvas course: ${course.id} ${course.name}")
        if (dryrun) cli.out("DRY RUN: no comments will be posted (pass --no-dryrun to apply)")

        val roster = canvasClient.listStudents(course.id)
        val usersByEmail = roster
            .flatMap { user -> identifiersOf(user).map { it to user } }
            .toMap()
        // Student ids are how an override names a Canvas user; either id field may carry one.
        val usersByStudentId = roster
            .flatMap { user -> listOfNotNull(user.loginId, user.sisUserId).map { it.lowercase() to user } }
            .toMap()
        cli.out("Canvas roster: ${usersByEmail.size} identifier(s) for matching")

        val overrides = cs30.studentOverrides().associate { it.email.lowercase() to it.studentId }
        if (overrides.isNotEmpty()) cli.out("Student overrides: ${overrides.size} mapping(s) from the server")

        val allAssignments = canvasClient.listAssignments(course.id)
        val assignments = allAssignments.associateBy { normalizeAssignmentName(it.name) }
        var posted = 0
        var upToDate = 0
        var noSubmission = 0
        var noCanvasUser = 0

        for (problem in plan.problems) {
            val name = canvasAssignmentName(plan.labNumber, problem.note)
            val assignment = assignments[normalizeAssignmentName(name)]
            if (assignment == null) {
                cli.err(
                    "  WARNING: no Canvas assignment named '$name' for problem '${problem.name}'. " +
                        "Assignments in this course: " +
                        allAssignments.joinToString(", ") { it.name }.ifEmpty { "(none)" }
                )
                continue
            }
            cli.out("$name (assignment ${assignment.id})")

            // One call per assignment gives every student's last mirrored timestamp.
            val lastSyncedByUser = canvasClient.listSubmissions(course.id, assignment.id)
                .associate { it.userId to lastSyncedTimestamp(it) }

            // And one call per problem gives every student's best submission on the cs30 side.
            val bestByEmail = cs30.bestSubmissions(
                cs30Course.code, cs30Course.year, cs30Course.semester, cs30Course.section,
                plan.labNumber, problem.name,
            ).associateBy { it.email }

            for (email in plan.studentEmails) {
                val submission = bestByEmail[email]?.submission
                if (submission == null) {
                    noSubmission++
                    continue
                }
                // An overridden email is matched only through its student id: falling back to the
                // email would silently hide an override gone stale.
                val overrideId = overrides[email.lowercase()]
                val user = if (overrideId != null) {
                    usersByStudentId[overrideId.lowercase()]
                } else {
                    usersByEmail[email.lowercase()]
                }
                if (user == null) {
                    if (overrideId != null) {
                        cli.err("  WARNING: no Canvas user with student id '$overrideId' (override for $email); skipping")
                    } else {
                        cli.err("  WARNING: no Canvas user for $email; skipping")
                    }
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
     * The newest submission timestamp this tool already mirrored, read back out of the comment.
     * Comparing our recorded timestamps avoids weighing Canvas' comment clock against file times.
     */
    internal fun lastSyncedTimestamp(submission: CanvasSubmission): String? =
        submission.submissionComments.orEmpty()
            .mapNotNull { comment -> SUBMITTED_AT_RE.find(comment.comment ?: "")?.groupValues?.get(1) }
            .maxOrNull()

    internal fun commentFor(problemName: String, submission: BestSubmission): String {
        val header = "Best submission for $problemName: " +
            "${submission.highestPassed}/${submission.total} test cases passed, " +
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

    internal companion object {
        const val NAME = "submissions2canvas"

        private const val MAX_INLINE_BYTES = 8 * 1024

        // How a re-run recognises a submission it already mirrored. The comment states the
        // submission time in this exact wording, so no separate marker is needed.
        private val SUBMITTED_AT_RE = Regex("""submitted (\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}) UTC""")
    }
}

/**
 * The override commands manage the server's student overrides: enrollment email → Canvas student
 * id, which submissions2canvas uses when the email cannot match the Canvas account (typically a
 * student whose Canvas account is under a personal address). They live on the server so they
 * survive addcourse re-importing the rosters, and are managed remotely like the other Canvas
 * commands. Adding and removing need the admin token; listing works with a TA token too.
 */
@Command(
    name = AddOverride.NAME,
    description = ["Map a student's email to their Canvas student id for submissions2canvas"],
)
class AddOverride() : BaseCommand(), Callable<Int> {

    /** See Course2Canvas: set by main() or by tests, absent only in the help listing. */
    lateinit var cs30: Cs30ApiClient

    constructor(cs30: Cs30ApiClient) : this() {
        this.cs30 = cs30
    }

    @Option(names = ["--email"], description = ["cs30 enrollment email"], required = true)
    var email: String = ""

    @Option(names = ["--student-id"], description = ["Canvas student id (login or SIS id)"], required = true)
    var studentId: String = ""

    override fun call(): Int = try {
        cli.out(cs30.addStudentOverride(email, studentId))
        0
    } catch (e: Cs30ApiException) {
        cli.err("ERROR: ${e.message}")
        1
    }

    internal companion object {
        const val NAME = "addoverride"
    }
}

@Command(name = RemoveOverride.NAME, description = ["Remove a student override"])
class RemoveOverride() : BaseCommand(), Callable<Int> {

    lateinit var cs30: Cs30ApiClient

    constructor(cs30: Cs30ApiClient) : this() {
        this.cs30 = cs30
    }

    @Option(names = ["--email"], description = ["cs30 enrollment email of the override"], required = true)
    var email: String = ""

    override fun call(): Int = try {
        cli.out(cs30.removeStudentOverride(email))
        0
    } catch (e: Cs30ApiException) {
        cli.err("ERROR: ${e.message}")
        1
    }

    internal companion object {
        const val NAME = "removeoverride"
    }
}

@Command(name = ListOverrides.NAME, description = ["List the student overrides"])
class ListOverrides() : BaseCommand(), Callable<Int> {

    lateinit var cs30: Cs30ApiClient

    constructor(cs30: Cs30ApiClient) : this() {
        this.cs30 = cs30
    }

    override fun call(): Int = try {
        val overrides = cs30.studentOverrides()
        if (overrides.isEmpty()) {
            cli.out("No student overrides")
        } else {
            overrides.forEach { cli.out("${it.email} -> ${it.studentId}") }
            cli.out("${overrides.size} override(s)")
        }
        0
    } catch (e: Cs30ApiException) {
        cli.err("ERROR: ${e.message}")
        1
    }

    internal companion object {
        const val NAME = "listoverrides"
    }
}
