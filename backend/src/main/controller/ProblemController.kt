package com.cs30.server.controller

import com.cs30.server.service.ProblemService
import com.cs30.server.service.StudentIdentityService
import data.LabProblemInfo
import data.ProblemContent
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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
     */
    @GetMapping("/lab")
    fun listProblemsForStudent(
        session: HttpSession,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<LabProblemInfo>> {
        val email = identityService.resolve(session, authHeader)
        if (email == null) {
            log.warn("Unauthorized request to /api/problems/lab")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        log.info("[PROBLEMS] GET /api/problems/lab for {}", email)
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
     * Returns HTML and CSS for a specific problem.
     */
    @GetMapping("/{courseId}/section/{section}/lab/{labNumber}/{slug}")
    fun getProblemContent(
        @PathVariable courseId: String,
        @PathVariable section: Int,
        @PathVariable labNumber: Int,
        @PathVariable slug: String,
        session: HttpSession,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<ProblemContent> {
        val email = identityService.resolve(session, authHeader)
        if (email == null) {
            log.warn("Unauthorized request to problem content")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        log.info("[PROBLEMS] GET /{}/section/{}/lab/{}/{} for {}", courseId, section, labNumber, slug, email)
        val content = problemService.getProblemContent(email, courseId, section, labNumber, slug)

        return if (content == null) {
            log.warn("[PROBLEMS] Problem not found or access denied: {}", slug)
            ResponseEntity.notFound().build()
        } else {
            log.info("[PROBLEMS] Returning content for {} (html: {} bytes, css: {} bytes)", slug, content.html.length, content.css.length)
            ResponseEntity.ok(content)
        }
    }
}
