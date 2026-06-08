package com.cs30.server.controller

import com.cs30.server.service.ProblemService
import com.cs30.server.service.StudentIdentityService
import data.LabProblemInfo
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/problems")
class ProblemController(
    private val problemService: ProblemService,
    private val identityService: StudentIdentityService
) {
    private val log = LoggerFactory.getLogger(ProblemController::class.java)

    /**
     * Returns problems for the authenticated student's active labs.
     * Uses session or Bearer token to identify the student.
     */
    @GetMapping("/me")
    fun listProblemsForStudent(
        session: HttpSession,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<LabProblemInfo>> {
        val email = identityService.resolve(session, authHeader)
        if (email == null) {
            log.warn("Unauthorized request to /api/problems/me")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        log.info("[PROBLEMS] GET /api/problems/me for {}", email)
        val problems = problemService.listProblemsForStudent(email)

        return if (problems.isEmpty()) {
            log.info("[PROBLEMS] No active problems for {}", email)
            ResponseEntity.ok(emptyList())
        } else {
            log.info("[PROBLEMS] Returning {} problems for {}", problems.size, email)
            ResponseEntity.ok(problems)
        }
    }

    /**
     * Returns HTML for a specific problem.
     * Verifies the student has access via their enrollment.
     */
    @GetMapping("/{courseId}/section/{section}/lab/{labNumber}/{slug}")
    fun getProblemHtmlForStudent(
        @PathVariable courseId: String,
        @PathVariable section: Int,
        @PathVariable labNumber: Int,
        @PathVariable slug: String,
        session: HttpSession,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<String> {
        val email = identityService.resolve(session, authHeader)
        if (email == null) {
            log.warn("Unauthorized request to problem HTML")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        log.info("[PROBLEMS] GET /{}/section/{}/lab/{}/{} for {}", courseId, section, labNumber, slug, email)
        val html = problemService.getProblemHtmlForStudent(email, courseId, section, labNumber, slug)

        return if (html == null) {
            log.warn("[PROBLEMS] Problem not found or access denied: {}", slug)
            ResponseEntity.notFound().build()
        } else {
            log.info("[PROBLEMS] Returning HTML for {} ({} bytes)", slug, html.length)
            ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)
        }
    }

    /**
     * Returns CSS for a specific problem.
     */
    @GetMapping("/{courseId}/section/{section}/lab/{labNumber}/{slug}/css")
    fun getCssForProblem(
        @PathVariable courseId: String,
        @PathVariable section: Int,
        @PathVariable labNumber: Int,
        @PathVariable slug: String,
        session: HttpSession,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<String> {
        val email = identityService.resolve(session, authHeader)
        if (email == null) {
            log.warn("Unauthorized request to problem CSS")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        log.info("[PROBLEMS] GET /{}/section/{}/lab/{}/{}/css for {}", courseId, section, labNumber, slug, email)
        val css = problemService.getProblemCssForStudent(email, courseId, section, labNumber, slug)
        return if (css == null) {
            log.warn("[PROBLEMS] CSS not found for problem {}", slug)
            ResponseEntity.notFound().build()
        } else {
            log.info("[PROBLEMS] CSS returned for {} ({} bytes)", slug, css.length)
            ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(css)
        }
    }
}
