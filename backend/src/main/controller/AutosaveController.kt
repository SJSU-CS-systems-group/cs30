package com.cs30.server.controller

import com.cs30.server.models.AutosaveRequest
import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.GitService
import com.cs30.server.service.StudentIdentityService
import jakarta.servlet.http.HttpSession
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/autosave")
class AutosaveController(
    private val identity: StudentIdentityService,
    private val gitService: GitService,
    private val courseRepository: CourseRepository,
) {
    private val log = LoggerFactory.getLogger(AutosaveController::class.java)

    companion object {
        private val LANGUAGE_EXTENSION = mapOf("python" to "py", "java" to "java")
        private const val DEFAULT_EXTENSION = "txt"
    }

    @PostMapping
    fun autosave(
        @RequestBody req: AutosaveRequest,
        @RequestHeader("Authorization", required = false) auth: String?,
        session: HttpSession,
    ): ResponseEntity<Void> {
        log.info("[AUTOSAVE] POST /api/autosave received")
        log.info("   problemSlug={}, codeLength={}, language={}", req.problemSlug, req.code.length, req.language)
        log.info("   auth header present={}, session id={}", auth != null, session.id)

        val email = identity.resolve(session, auth)
        if (email == null) {
            // Expected for stale/expired tabs (the client stops autosaving on 401) — debug, not warn.
            log.debug("[AUTOSAVE] No authenticated user found. Returning 401")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        log.info("[AUTOSAVE] Authenticated as {}", email)

        val course = courseRepository.findById(req.courseId).orElse(null)
            ?: run {
                log.warn("[AUTOSAVE] Course not found: {}", req.courseId)
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
            }
        if (email !in course.students) {
            log.warn("[AUTOSAVE] {} not enrolled in course {}", email, req.courseId)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        log.info("[AUTOSAVE] course={} section={} lab={}", course.id, req.section, req.labNumber)

        val ext = extensionFor(req.language)
        log.info("   file extension={}, repo={}", ext, course.studentGitRepo)

        runCatching {
            log.info("[AUTOSAVE] Calling gitService.saveAutosolution...")
            gitService.saveAutosolution(
                repoPath = course.studentGitRepo,
                section = req.section,
                labNumber = req.labNumber,
                problemName = req.problemSlug,
                studentEmail = email,
                code = req.code,
                extension = ext,
                authorEmail = email,
            )
            log.info("[AUTOSAVE] Git operation completed")
        }.onFailure {
            log.error("[AUTOSAVE] saveAutosolution failed: {}", it.message, it)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build()
        }
        log.info("[AUTOSAVE] SUCCESS: user={} problem={} codeSize={}", email, req.problemSlug, req.code.length)
        return ResponseEntity.accepted().build()
    }

    /**
     * Returns the student's latest autosaved code for a problem (empty body if none),
     * so the editor can repopulate when the problem is reopened.
     */
    @GetMapping("/{courseId}/{section}/{labNumber}/{problemSlug}")
    fun latestAutosave(
        @PathVariable courseId: String,
        @PathVariable section: Int,
        @PathVariable labNumber: Int,
        @PathVariable problemSlug: String,
        @RequestHeader("Authorization", required = false) auth: String?,
        session: HttpSession,
    ): ResponseEntity<String> {
        val email = identity.resolve(session, auth)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val course = courseRepository.findById(courseId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        if (email !in course.students) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        // Derive the language authoritatively from the problem so the extension matches the write.
        val lab = course.labs.find { it.labNumber == labNumber }
        val problem = lab?.problems?.find { it.name == problemSlug }
        val language = problem?.language?.ifBlank { course.language } ?: course.language
        val ext = extensionFor(language)
        log.info("[AUTOSAVE] GET lookup: labFound={} problemFound={} problemLang={} courseLang={} ext={}",
            lab != null, problem != null, problem?.language, course.language, ext)

        val studentDir = java.io.File(course.studentGitRepo, "section_$section/lab_$labNumber/$problemSlug/$email")
        val filesInDir = studentDir.listFiles()?.map { it.name } ?: emptyList()
        log.info("[AUTOSAVE] GET studentDir={} exists={} files={}", studentDir.absolutePath, studentDir.exists(), filesInDir)

        val code = gitService.readLatestAutosave(
            repoPath = course.studentGitRepo,
            section = section,
            labNumber = labNumber,
            problemName = problemSlug,
            studentEmail = email,
            extension = ext,
        )
        log.info("[AUTOSAVE] GET latest user={} problem={} found={} lookingFor=autosaved-solution.{}", email, problemSlug, code != null, ext)
        return ResponseEntity.ok(code ?: "")
    }

    private fun extensionFor(language: String): String =
        LANGUAGE_EXTENSION[language.lowercase()] ?: DEFAULT_EXTENSION
}
