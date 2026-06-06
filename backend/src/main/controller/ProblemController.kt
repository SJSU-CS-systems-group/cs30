package com.cs30.server.controller

import com.cs30.server.service.ProblemService
import data.ProblemSummary
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/problems")
class ProblemController(private val problemService: ProblemService) {
    private val log = LoggerFactory.getLogger(ProblemController::class.java)

    @GetMapping
    fun listProblems(): ResponseEntity<List<ProblemSummary>> {
        log.info("📚 [PROBLEMS] GET /api/problems")
        val problems = problemService.listProblems()
        return if (problems.isEmpty()) {
            log.warn("⚠️  No problems available (course path may not be configured)")
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
        } else {
            log.info("✅ [PROBLEMS] Returning {} problems", problems.size)
            ResponseEntity.ok(problems)
        }
    }

    @GetMapping("/css")
    fun getCss(): ResponseEntity<String> {
        log.info("🎨 [PROBLEMS] GET /api/problems/css")
        val css = problemService.getProblemCss()
        return if (css == null) {
            log.warn("❌ [PROBLEMS] CSS file not found")
            ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        } else {
            log.info("✅ [PROBLEMS] CSS returned ({} bytes)", css.length)
            ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(css)
        }
    }

    @GetMapping("/{slug}")
    fun getProblemHtml(@PathVariable slug: String): ResponseEntity<String> {
        log.info("📄 [PROBLEMS] GET /api/problems/{}", slug)
        val html = problemService.getProblemHtml(slug)
        return if (html == null) {
            log.warn("❌ [PROBLEMS] Problem {} not found", slug)
            ResponseEntity.notFound().build()
        } else {
            log.info("✅ [PROBLEMS] Problem {} returned ({} bytes)", slug, html.length)
            ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)
        }
    }
}
