package com.cs30.cli

import com.cs30.server.dto.CanvasLabPlan
import com.cs30.server.dto.CourseQuery
import com.cs30.server.dto.CourseRef
import com.cs30.server.dto.StudentBestSubmission
import com.cs30.server.dto.StudentOverrideDto
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Raised for any problem talking to the cs30 server, with a message fit to print as it is. */
class Cs30ApiException(message: String) : RuntimeException(message)

/**
 * The cs30 server as the commands that run remotely see it: the endpoints CanvasSyncController
 * exposes, authenticated with the CLI token. This is the only way those commands reach cs30 - they
 * have no database or repository access of their own, so they can run from any machine.
 *
 * Configuration is checked on first use rather than in the constructor, so a command can still
 * print its help on a machine where nothing is configured yet.
 */
class Cs30ApiClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        // The server may grow fields; an older CLI keeps working.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
        const val OVERRIDES_PATH = "/api/admin/canvas/overrides"
    }

    // A JacksonException from any of these means the 2xx body was not the expected JSON -
    // typically the web app's HTML, which an older server answers with for a path it does not
    // know. The commands catch it and say so (see NOT_JSON_ERROR).

    /**
     * The courses a query fits, over those this token may see: one to go on with, none or several
     * to report. CourseQuery(active = true) alone is the courses that have not ended.
     */
    fun findCourses(query: CourseQuery): List<CourseRef> =
        mapper.readValue(
            get(
                "/api/admin/canvas/courses",
                listOfNotNull(
                    query.code?.let { "code" to it },
                    query.year?.let { "year" to it.toString() },
                    query.semester?.let { "semester" to it },
                    query.section?.let { "section" to it.toString() },
                    ("active" to "true").takeIf { query.active },
                ),
                "list courses matching $query",
            )
        )

    /** The lab as the Canvas commands need it. Throws Cs30ApiException when it cannot be read. */
    fun labPlan(code: String, year: Int, semester: String, section: Int, lab: Int): CanvasLabPlan =
        mapper.readValue(
            get(
                "/api/admin/canvas/lab",
                labQuery(code, year, semester, section, lab),
                "read lab $lab of $code section $section",
            )
        )

    /** Every enrolled student's best submission for one problem of the lab - students without one are absent. */
    fun bestSubmissions(
        code: String,
        year: Int,
        semester: String,
        section: Int,
        lab: Int,
        problem: String,
    ): List<StudentBestSubmission> =
        mapper.readValue(
            get(
                "/api/admin/canvas/lab/submissions",
                labQuery(code, year, semester, section, lab) + ("problem" to problem),
                "read submissions for $problem in lab $lab of $code section $section",
            )
        )

    /** Every student override: enrollment email → the Canvas student id to match instead. */
    fun studentOverrides(): List<StudentOverrideDto> =
        mapper.readValue(get(OVERRIDES_PATH, emptyList(), "read student overrides"))

    /** Adds an override, or repoints an existing one. Returns the server's message. */
    fun addStudentOverride(email: String, studentId: String): String =
        serverMessage(
            send("POST", OVERRIDES_PATH, listOf("email" to email, "studentId" to studentId), "add override for $email")
        )

    /** Removes an override. Returns the server's message; an unknown email is a Cs30ApiException. */
    fun removeStudentOverride(email: String): String =
        serverMessage(send("DELETE", OVERRIDES_PATH, listOf("email" to email), "remove override for $email"))

    /** The `message` field of a success body. Not JSON at all throws, like every other response. */
    private fun serverMessage(body: String): String =
        mapper.readValue<Map<String, String?>>(body)["message"] ?: "OK"

    private fun labQuery(code: String, year: Int, semester: String, section: Int, lab: Int) = listOf(
        "code" to code,
        "year" to year.toString(),
        "semester" to semester,
        "section" to section.toString(),
        "lab" to lab.toString(),
    )

    private fun requireConfig() {
        if (baseUrl.isBlank()) {
            throw Cs30ApiException(
                "no cs30 server configured: pass --server, set CS30_BACKEND_URL, or put cs30.backend.url in cs30.properties"
            )
        }
        if (token.isBlank()) {
            throw Cs30ApiException(
                "no CLI token: pass --token, set CS30_ADMIN_TOKEN, or put cs30.cli.token in cs30.properties"
            )
        }
    }

    private fun get(path: String, query: List<Pair<String, String>>, describe: String): String =
        send("GET", path, query, describe)

    /**
     * Sends [method] to [path] and returns the body of a 2xx response. Anything else becomes a
     * Cs30ApiException that says what went wrong in the server's own words when it gave any (the
     * `error` field of its JSON body), so a refused token or an unknown course reads the same over
     * HTTP as it did when the command ran on the server.
     */
    private fun send(method: String, path: String, query: List<Pair<String, String>>, describe: String): String {
        requireConfig()
        val url = baseUrl.trimEnd('/') + path +
            if (query.isEmpty()) "" else "?" +
                query.joinToString("&") { (name, value) -> name + "=" + URLEncoder.encode(value, Charsets.UTF_8) }
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .timeout(REQUEST_TIMEOUT)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            // A refused connection comes as a ConnectException with no message at all.
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            throw Cs30ApiException("cannot reach $baseUrl: $reason")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw Cs30ApiException("$describe interrupted")
        }

        val status = response.statusCode()
        val body = response.body().orEmpty()
        if (status in 200..299) return body

        val error = errorMessage(body)
        throw Cs30ApiException(
            when (status) {
                401 -> "the server rejected the CLI token: ${error ?: "HTTP 401"}"
                403, 404 -> error ?: "$describe failed: HTTP $status"
                else -> "$describe failed: HTTP $status" +
                    (error ?: body.take(300).takeIf { it.isNotBlank() })?.let { ": $it" }.orEmpty()
            }
        )
    }

    /** The `error` field of the JSON body the server sends on failure, or null when there is none. */
    private fun errorMessage(body: String): String? = try {
        mapper.readTree(body)?.get("error")?.takeIf { it.isTextual }?.asText()
    } catch (e: Exception) {
        null
    }
}

/**
 * What a JacksonException out of Cs30ApiClient means to the person running a command: the server
 * answered 2xx but not with JSON - typically the web app's HTML for a path an older release does
 * not know. Every remote command catches the exception and prints this.
 */
internal const val NOT_JSON_ERROR =
    "ERROR: the server did not answer with JSON - it may be running a release without this endpoint"
