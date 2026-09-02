package com.cs30.cli

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A Canvas enrollment term. Only the name is shown, to tell same-named courses apart in a listing. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasTerm(val id: Long = 0, val name: String? = null)

/**
 * A Canvas course. `name`/`courseCode` are what a fragment is matched against; `workflowState`,
 * `concluded` and `term` (the latter two only present with include[]=concluded,term) feed the
 * listings that a miss or an ambiguous match prints.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasCourse(
    val id: Long = 0,
    val name: String = "",
    @JsonProperty("course_code") val courseCode: String? = null,
    @JsonProperty("workflow_state") val workflowState: String? = null,
    val concluded: Boolean? = null,
    val term: CanvasTerm? = null,
) {
    /**
     * Still running: not concluded by its term ending, not concluded by hand (workflow_state
     * "completed") and not deleted. Unpublished courses count, since a course is set up before it
     * is published.
     */
    val active: Boolean
        get() = concluded != true && workflowState != "completed" && workflowState != "deleted"

    /** One listing line: id and name, plus the code when it adds something, the term, and any notable state. */
    fun describe(): String {
        val details = mutableListOf<String>()
        if (!courseCode.isNullOrBlank() && !courseCode.equals(name, ignoreCase = true)) details += courseCode
        term?.name?.takeIf { it.isNotBlank() }?.let { details += it }
        if (workflowState == "unpublished") details += "unpublished"
        if (!active) details += "concluded"
        return "$id: $name" + if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasAssignmentGroup(val id: Long = 0, val name: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasAssignment(
    val id: Long = 0,
    val name: String = "",
    @JsonProperty("points_possible") val pointsPossible: Double? = null,
    val published: Boolean? = null,
)

/** Canvas calls a rubric's name `title`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasRubric(val id: Long = 0, val title: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasSection(val id: Long = 0, val name: String = "")

/**
 * `email` needs include[]=email on the request; `loginId` is the fallback when it is withheld.
 * At SJSU `loginId` and `sisUserId` both carry the 9-digit student id, which is how a student
 * override (see Submissions2Canvas) names a Canvas user.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasUser(
    val id: Long = 0,
    val name: String = "",
    val email: String? = null,
    @JsonProperty("login_id") val loginId: String? = null,
    @JsonProperty("sis_user_id") val sisUserId: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasSubmissionComment(
    val id: Long = 0,
    val comment: String? = null,
    @JsonProperty("created_at") val createdAt: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CanvasSubmission(
    val id: Long = 0,
    @JsonProperty("user_id") val userId: Long = 0,
    @JsonProperty("submission_comments") val submissionComments: List<CanvasSubmissionComment>? = null,
)

/** Raised for any Canvas API problem, so callers can report it without unwrapping HTTP details. */
class CanvasException(message: String) : RuntimeException(message)

/**
 * Minimal Canvas REST client on the JDK HttpClient, covering only the calls the sync needs. Lives
 * with the CLI because only the Canvas commands talk to Canvas; the server never does. Config is
 * validated on first use, not in the constructor, so a command can print its help with nothing
 * configured.
 */
class CanvasClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val log = LoggerFactory.getLogger(CanvasClient::class.java)
    private val mapper = jacksonObjectMapper()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private companion object {
        const val MAX_ATTEMPTS = 4
        const val BACKOFF_BASE_MS = 1_000L
        const val PER_PAGE = 100
        const val MAX_PAGES = 200
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
    }

    /** True when both URL and token are configured, so commands can fail with a clear message. */
    val configured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    private fun requireConfig() {
        if (baseUrl.isBlank()) throw CanvasException("canvas.url is not configured")
        if (token.isBlank()) throw CanvasException("canvas.token is not configured (set CANVAS_TOKEN)")
    }

    private fun apiUrl(path: String): String {
        requireConfig()
        val root = baseUrl.trimEnd('/')
        val p = path.removePrefix("/")
        return "$root/api/v1/$p"
    }

    private data class HttpResult(val body: String, val linkHeader: String?)

    // Canvas signals throttling as 403 with a "Rate Limit Exceeded" body, not 429, so only that
    // combination is retried; an ordinary 403 (bad token) fails immediately.
    private fun sendRaw(request: HttpRequest, describe: String): HttpResult {
        var lastError: String? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                lastError = "network error: ${e.message}"
                if (attempt == MAX_ATTEMPTS) break
                sleepBackoff(attempt, describe, lastError)
                continue
            }
            val code = response.statusCode()
            val body = response.body() ?: ""
            if (code in 200..299) {
                return HttpResult(body, response.headers().firstValue("Link").orElse(null))
            }

            val throttled = code == 403 && body.contains("Rate Limit Exceeded", ignoreCase = true)
            lastError = "HTTP $code: ${body.take(300)}"
            if (!throttled && code < 500) {
                throw CanvasException("$describe failed: $lastError")
            }
            if (attempt == MAX_ATTEMPTS) break
            sleepBackoff(attempt, describe, if (throttled) "rate limited" else lastError)
        }
        throw CanvasException("$describe failed after $MAX_ATTEMPTS attempts: $lastError")
    }

    private fun send(request: HttpRequest, describe: String): String = sendRaw(request, describe).body

    private fun sleepBackoff(attempt: Int, describe: String, why: String?) {
        val waitMs = BACKOFF_BASE_MS shl (attempt - 1)
        log.warn("Canvas {} retry {}/{} in {}ms ({})", describe, attempt, MAX_ATTEMPTS, waitMs, why)
        try {
            Thread.sleep(waitMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CanvasException("$describe interrupted")
        }
    }

    private fun builder(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)

    private fun getRaw(url: String, describe: String): String =
        send(builder(url).GET().build(), describe)

    private fun postJson(path: String, body: Any, describe: String): String =
        send(
            builder(apiUrl(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(),
            describe,
        )

    private fun putJson(path: String, body: Any, describe: String): String =
        send(
            builder(apiUrl(path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build(),
            describe,
        )

    /** GET every page, following the Link header's rel="next" rather than counting pages. */
    private inline fun <reified T> getAll(path: String, describe: String): List<T> {
        val separator = if (path.contains('?')) "&" else "?"
        var url: String? = apiUrl(path) + separator + "per_page=" + PER_PAGE
        val all = mutableListOf<T>()
        var pages = 0
        while (url != null) {
            val result = sendRaw(builder(url).GET().build(), describe)
            all.addAll(mapper.readValue<List<T>>(result.body.ifBlank { "[]" }))
            url = nextLink(result.linkHeader)
            if (++pages > MAX_PAGES) throw CanvasException("$describe returned more than $MAX_PAGES pages")
        }
        return all
    }

    /** Extract the rel="next" URL from a Link header, or null when this was the last page. */
    private fun nextLink(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        return linkHeader.split(',')
            .map { it.trim() }
            .firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter('<')
            ?.substringBefore('>')
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Resolve a course from a numeric id, or from a name/code fragment that must match exactly one
     * visible course; [selectCanvasCourse] has the matching rules.
     */
    fun findCourse(courseIdOrName: String): CanvasCourse {
        val trimmed = courseIdOrName.trim()
        if (trimmed.isEmpty()) throw CanvasException("no Canvas course given")
        if (trimmed.all { it.isDigit() }) {
            val body = getRaw(apiUrl("courses/$trimmed"), "get course $trimmed")
            return mapper.readValue(body)
        }
        return selectCanvasCourse(listCourses(), trimmed)
    }

    /** Every course the token can see, with the term and concluded flag that the listings show. */
    fun listCourses(): List<CanvasCourse> =
        getAll("courses?include[]=term&include[]=concluded", "list courses")

    fun listSections(courseId: Long): List<CanvasSection> =
        getAll("courses/$courseId/sections", "list sections")

    /** Resolve a section by exact name first, then unique case-insensitive substring. */
    fun findSection(courseId: Long, name: String): CanvasSection {
        val sections = listSections(courseId)
        sections.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val partial = sections.filter { it.name.contains(name, ignoreCase = true) }
        if (partial.size == 1) return partial.single()
        throw CanvasException(
            if (partial.isEmpty())
                "no Canvas section named '$name'. Sections: " + sections.joinToString(", ") { it.name }
            else
                "multiple Canvas sections match '$name': " + partial.joinToString(", ") { it.name }
        )
    }

    fun findAssignmentGroup(courseId: Long, name: String): CanvasAssignmentGroup? =
        getAll<CanvasAssignmentGroup>("courses/$courseId/assignment_groups", "list assignment groups")
            .firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun createAssignmentGroup(courseId: Long, name: String): CanvasAssignmentGroup =
        mapper.readValue(
            postJson("courses/$courseId/assignment_groups", mapOf("name" to name), "create assignment group '$name'")
        )

    fun listAssignments(courseId: Long): List<CanvasAssignment> =
        getAll("courses/$courseId/assignments", "list assignments")

    fun createAssignment(courseId: Long, fields: Map<String, Any?>): CanvasAssignment =
        mapper.readValue(
            postJson("courses/$courseId/assignments", mapOf("assignment" to fields), "create assignment")
        )

    fun updateAssignment(courseId: Long, assignmentId: Long, fields: Map<String, Any?>): CanvasAssignment =
        mapper.readValue(
            putJson(
                "courses/$courseId/assignments/$assignmentId",
                mapOf("assignment" to fields),
                "update assignment $assignmentId",
            )
        )

    fun listRubrics(courseId: Long): List<CanvasRubric> =
        getAll("courses/$courseId/rubrics", "list rubrics")

    /** Resolve an existing rubric by title. Never creates one, so a typo fails and lists what exists. */
    fun findRubric(courseId: Long, title: String): CanvasRubric {
        val rubrics = listRubrics(courseId)
        rubrics.firstOrNull { it.title.equals(title, ignoreCase = true) }?.let { return it }
        val partial = rubrics.filter { it.title.contains(title, ignoreCase = true) }
        if (partial.size == 1) return partial.single()
        throw CanvasException(
            if (partial.isEmpty())
                "no rubric matching '$title'. Available rubrics: " +
                    rubrics.joinToString(", ") { it.title }.ifEmpty { "(none)" }
            else
                "multiple rubrics match '$title': " + partial.joinToString(", ") { it.title }
        )
    }

    /** Students on the Canvas roster, with email included so submissions can be matched by it. */
    fun listStudents(courseId: Long): List<CanvasUser> =
        getAll("courses/$courseId/users?enrollment_type[]=student&include[]=email", "list students")

    /**
     * Every student's submission record for one assignment, with comments, in one paginated call
     * rather than a request per student.
     */
    fun listSubmissions(courseId: Long, assignmentId: Long): List<CanvasSubmission> =
        getAll(
            "courses/$courseId/assignments/$assignmentId/submissions?include[]=submission_comments",
            "list submissions for assignment $assignmentId",
        )

    /** Post a submission comment. Deliberately does not touch posted_grade. */
    fun postSubmissionComment(courseId: Long, assignmentId: Long, userId: Long, text: String) {
        putJson(
            "courses/$courseId/assignments/$assignmentId/submissions/$userId",
            mapOf("comment" to mapOf("text_comment" to text)),
            "comment on assignment $assignmentId for user $userId",
        )
    }

    /** use_for_grading/purpose=grading make it the grading rubric shown in SpeedGrader. */
    fun attachRubric(courseId: Long, rubricId: Long, assignmentId: Long) {
        postJson(
            "courses/$courseId/rubric_associations",
            mapOf(
                "rubric_association" to mapOf(
                    "rubric_id" to rubricId,
                    "association_id" to assignmentId,
                    "association_type" to "Assignment",
                    "use_for_grading" to true,
                    "purpose" to "grading",
                )
            ),
            "attach rubric $rubricId to assignment $assignmentId",
        )
    }

    /** URL-encode a path segment (for values interpolated into API paths). */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

/**
 * The one course a name/code fragment refers to. An exact name or code wins outright, so a course
 * whose name is also the prefix of another ("CS30" alongside "CS30 Lab") still resolves; failing
 * that, a case-insensitive substring of the name or code must match exactly one course. Ambiguity
 * is an error, never a guess, so a sync cannot hit the wrong course; the error lists the candidates.
 *
 * Every visible course is searched, so a concluded course can still be named, but when nothing
 * matches only the active ones are listed: a sync is almost always for a course that is running.
 */
internal fun selectCanvasCourse(courses: List<CanvasCourse>, query: String): CanvasCourse {
    val exact = courses.filter {
        it.name.equals(query, ignoreCase = true) || it.courseCode.equals(query, ignoreCase = true)
    }
    val matches = exact.ifEmpty {
        courses.filter {
            it.name.contains(query, ignoreCase = true) ||
                (it.courseCode?.contains(query, ignoreCase = true) == true)
        }
    }
    return when (matches.size) {
        1 -> matches.single()
        0 -> throw CanvasException(
            "no Canvas course matching '$query'. Active courses:" + listing(courses.filter { it.active })
        )
        else -> throw CanvasException(
            "multiple Canvas courses match '$query':" + listing(matches) + "\nPass the course id or a longer fragment."
        )
    }
}

/** By name, then id, so the same set of courses always reads the same way. */
private fun listing(courses: List<CanvasCourse>): String =
    bulletList(courses.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))) { it.describe() }
