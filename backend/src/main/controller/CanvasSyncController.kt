package com.cs30.server.controller

import com.cs30.server.models.CliToken
import com.cs30.server.models.CliTokenRole
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CanvasSyncService
import com.cs30.server.service.CliTokenService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The cs30 side of a Canvas sync, for the CLI's course2canvas and submissions2canvas: the lab plan
 * (window, problems, roster) and each student's best submission. The CLI runs these anywhere - it
 * has no database or repo access of its own - so this is where the data leaves the server.
 *
 * Gated by the CLI token rather than a browser session, like the problem upload endpoints. The
 * admin token reaches any course; a TA token only the section that TA is assigned to
 * (Course.taEmail), the same ownership rule the TA dashboard applies. Lives under /api/admin/ so
 * the IP allowlist and kiosk gate leave it alone (see WebConfig): being reachable from off campus
 * is the point.
 */
@RestController
@RequestMapping("/api/admin/canvas")
class CanvasSyncController(
    private val cliTokenService: CliTokenService,
    private val canvasSyncService: CanvasSyncService,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(CanvasSyncController::class.java)

    @GetMapping("/lab")
    fun lab(
        @RequestParam code: String,
        @RequestParam year: Int,
        @RequestParam semester: String,
        @RequestParam section: Int,
        @RequestParam lab: Int,
        @RequestHeader("Authorization", required = false) authHeader: String?,
    ): ResponseEntity<Any> {
        val token = when (val access = access(authHeader, code, year, semester, section)) {
            is Access.Denied -> return access.response
            is Access.Allowed -> access.token
        }
        return try {
            val plan = canvasSyncService.labPlan(code, year, semester, section, lab)
            log.info(
                "[canvas-sync] {} ({}) read lab {} of {} section {} ({} {})",
                token.email, token.role, lab, code, section, semester, year,
            )
            ResponseEntity.ok(plan)
        } catch (e: IllegalArgumentException) {
            error(HttpStatus.NOT_FOUND, e.message ?: "Not found")
        } catch (e: Exception) {
            log.error("[canvas-sync] failed to read lab {} of {} section {}", lab, code, section, e)
            error(HttpStatus.INTERNAL_SERVER_ERROR, e.message ?: "Failed to read the lab")
        }
    }

    @GetMapping("/lab/submissions")
    fun submissions(
        @RequestParam code: String,
        @RequestParam year: Int,
        @RequestParam semester: String,
        @RequestParam section: Int,
        @RequestParam lab: Int,
        @RequestParam problem: String,
        @RequestHeader("Authorization", required = false) authHeader: String?,
    ): ResponseEntity<Any> {
        val token = when (val access = access(authHeader, code, year, semester, section)) {
            is Access.Denied -> return access.response
            is Access.Allowed -> access.token
        }
        return try {
            val submissions = canvasSyncService.bestSubmissions(code, year, semester, section, lab, problem)
            log.info(
                "[canvas-sync] {} ({}) read {} submission(s) for '{}' in lab {} of {} section {} ({} {})",
                token.email, token.role, submissions.size, problem, lab, code, section, semester, year,
            )
            ResponseEntity.ok(submissions)
        } catch (e: IllegalArgumentException) {
            error(HttpStatus.NOT_FOUND, e.message ?: "Not found")
        } catch (e: Exception) {
            log.error(
                "[canvas-sync] failed to read submissions for '{}' in lab {} of {} section {}",
                problem, lab, code, section, e,
            )
            error(HttpStatus.INTERNAL_SERVER_ERROR, e.message ?: "Failed to read the submissions")
        }
    }

    /** Either the caller's token, when it may read this course section, or the response to send instead. */
    private sealed interface Access {
        data class Allowed(val token: CliToken) : Access
        data class Denied(val response: ResponseEntity<Any>) : Access
    }

    /**
     * A TA is checked against the exact section asked for, and a course that does not exist looks
     * the same to them as one they are not assigned to: 403 either way, so the endpoint reveals
     * nothing about other courses. That leaves PROFESSOR, and nothing issues such a token today.
     */
    private fun access(authHeader: String?, code: String, year: Int, semester: String, section: Int): Access {
        val token = cliTokenService.resolveAuthorization(authHeader)
            ?: return Access.Denied(error(HttpStatus.UNAUTHORIZED, "Valid CLI token required"))
        return when (token.role) {
            CliTokenRole.ADMIN -> Access.Allowed(token)
            CliTokenRole.TA -> {
                val assigned = courseRepository.findByTaEmail(token.email).any {
                    it.code == code && it.year == year && it.semester == semester && it.section == section
                }
                if (assigned) {
                    Access.Allowed(token)
                } else {
                    log.warn(
                        "[canvas-sync] TA {} denied: not the TA for {} section {} ({} {})",
                        token.email, code, section, semester, year,
                    )
                    Access.Denied(
                        error(HttpStatus.FORBIDDEN, "This token is not the TA for $code section $section ($semester $year)")
                    )
                }
            }
            else -> Access.Denied(error(HttpStatus.FORBIDDEN, "Only the admin or the section's TA can use this"))
        }
    }

    private fun error(status: HttpStatus, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(mapOf("error" to message))
}
