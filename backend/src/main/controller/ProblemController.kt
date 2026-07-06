package com.cs30.server.controller

import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.ProblemService
import com.cs30.server.service.StudentIdentityService
import data.LabProblemInfo
import data.ProblemContent
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files

@RestController
@RequestMapping("/api/problems")
class ProblemController(
    private val problemService: ProblemService,
    private val identityService: StudentIdentityService,
    private val courseRepository: CourseRepository
) {
    private val log = LoggerFactory.getLogger(ProblemController::class.java)

    /**
     * Returns problems for the authenticated student's active labs.
     * Returns 404 if student is not enrolled in any course.
     * Returns 200 with empty list if student is enrolled but has no active labs.
     */
    @GetMapping("/lab")
    fun listProblemsForStudent(
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<List<LabProblemInfo>> {
        val email = identityService.resolve(authHeader)
        if (email == null) {
            log.warn("Unauthorized request to /api/problems/lab")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        log.info("[PROBLEMS] GET /api/problems/lab for {}", email)

        val courses = courseRepository.findByStudentEmail(email)
        if (courses.isEmpty()) {
            log.info("[PROBLEMS] Student {} is not enrolled in any course", email)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }

        val problems = problemService.listProblemsForStudent(email)
        log.info("[PROBLEMS] Returning {} problems for {}", problems.size, email)
        return ResponseEntity.ok(problems)
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
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<ProblemContent> {
        val email = identityService.resolve(authHeader)
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

    @GetMapping("/{courseId}/section/{section}/lab/{labNumber}/{slug}/assets/**")
    fun getProblemAsset(
        @PathVariable courseId: String,
        @PathVariable section: Int,
        @PathVariable labNumber: Int,
        @PathVariable slug: String,
        request: HttpServletRequest,
        @RequestHeader("Authorization", required = false) authHeader: String?
    ): ResponseEntity<Resource> {
        val email = identityService.resolve(authHeader)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        // Extract the path after /assets/
        val assetPath = request.requestURI.substringAfter("/assets/")

        val file = problemService.getProblemAssetFile(email, courseId, section, labNumber, slug, assetPath)
            ?: return ResponseEntity.notFound().build()

        val resource = FileSystemResource(file)
        val mediaType = Files.probeContentType(file.toPath())?.let { MediaType.parseMediaType(it) }
            ?: MediaType.APPLICATION_OCTET_STREAM

        return ResponseEntity.ok()
            .contentType(mediaType)
            .body(resource)
    }
}
