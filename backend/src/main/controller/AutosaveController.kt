package com.cs30.server.controller

import com.cs30.server.models.AutosaveRequest
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.GitService
import com.cs30.server.service.StudentIdentityService
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/autosave")
class AutosaveController(
    private val identity: StudentIdentityService,
    private val gitService: GitService,
    private val courseRepository: CourseRepository,
    @Value("\${CS30_COURSE_ID:}") private val courseId: String,
    @Value("\${CS30_LAB_ID:lab-01}") private val labId: String,
) {
    private val log = LoggerFactory.getLogger(AutosaveController::class.java)

    companion object {
        private val LANGUAGE_EXTENSION = mapOf("python" to "py", "java" to "java")
        private const val DEFAULT_EXTENSION = "kt"
    }

    @PostMapping
    fun autosave(
        @RequestBody req: AutosaveRequest,
        @RequestHeader("Authorization", required = false) auth: String?,
        session: HttpSession,
    ): ResponseEntity<Void> {
        log.info("📝 [AUTOSAVE] POST /api/autosave received")
        log.info("   problemSlug={}, codeLength={}, language={}", req.problemSlug, req.code.length, req.language)
        log.info("   auth header present={}, session id={}", auth != null, session.id)

        val email = identity.resolve(session, auth)
        if (email == null) {
            log.warn("❌ [AUTOSAVE] No authenticated user found. Returning 401")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        log.info("✅ [AUTOSAVE] Authenticated as {}", email)

        if (courseId.isBlank()) {
            log.error("❌ [AUTOSAVE] CS30_COURSE_ID not configured")
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }

        val course = courseRepository.findById(courseId).orElse(null)
        if (course == null) {
            log.error("❌ [AUTOSAVE] Course {} not found", courseId)
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        }

        if (!course.students.contains(email)) {
            log.warn("❌ [AUTOSAVE] Student {} not enrolled in course {}", email, courseId)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        log.info("✅ [AUTOSAVE] Student enrolled in course {}", courseId)

        val ext = LANGUAGE_EXTENSION[req.language.lowercase()] ?: DEFAULT_EXTENSION
        log.info("   file extension={}, repo={}", ext, course.studentGitRepo)

        runCatching {
            log.info("   ⏳ Calling gitService.saveAutosolution...")
            gitService.saveAutosolution(
                repoPath = course.studentGitRepo,
                section = course.section,
                labId = labId,
                assignmentId = req.problemSlug,
                studentId = email,
                code = req.code,
                extension = ext,
                authorEmail = email,
            )
            log.info("   ✅ Git operation completed")
        }.onFailure {
            log.error("❌ [AUTOSAVE] saveAutosolution failed: {}", it.message, it)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
        log.info("✅ [AUTOSAVE] SUCCESS: user={} problem={} codeSize={}", email, req.problemSlug, req.code.length)
        return ResponseEntity.accepted().build()
    }
}
