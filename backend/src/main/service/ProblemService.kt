package com.cs30.server.service

import com.cs30.server.repository.CourseRepository
import data.ProblemSummary
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

@Service
class ProblemService(
    private val courseRepository: CourseRepository,
    @Value("\${CS30_COURSE_ID:}") private val courseId: String,
    @Value("\${CS30_PROBLEMS_PATH:}") private val problemsPathOverride: String,
) {
    private val log = LoggerFactory.getLogger(ProblemService::class.java)

    private fun problemsPath(): String? {
        // Dev shortcut: CS30_PROBLEMS_PATH bypasses the DB lookup entirely
        if (problemsPathOverride.isNotBlank()) {
            log.debug("Using CS30_PROBLEMS_PATH override: {}", problemsPathOverride)
            return problemsPathOverride
        }
        if (courseId.isBlank()) {
            log.warn("Neither CS30_PROBLEMS_PATH nor CS30_COURSE_ID is configured")
            return null
        }
        val course = courseRepository.findById(courseId).orElse(null) ?: run {
            log.warn("Course {} not found", courseId)
            return null
        }
        val path = course.problemGitRepo?.takeIf { it.isNotBlank() }
        if (path == null) log.warn("Course {} has no problemGitRepo configured", courseId)
        return path
    }

    fun listProblems(): List<ProblemSummary> {
        val path = problemsPath() ?: return emptyList()
        return File(path).listFiles()
            ?.filter { it.isDirectory && it.resolve("index.html").exists() }
            ?.map { ProblemSummary(slug = it.name, title = formatTitle(it.name)) }
            ?.sortedBy { it.title }
            ?: emptyList()
    }

    fun getProblemHtml(slug: String): String? {
        val path = problemsPath() ?: return null
        val file = File(path, "$slug/index.html")
        return if (file.exists()) file.readText() else null
    }

    fun getProblemCss(): String? {
        val path = problemsPath() ?: return null
        val file = File(path, "problem.css")
        return if (file.exists()) file.readText() else null
    }

    private fun formatTitle(slug: String): String = slug
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .split("-").joinToString(" ") { word ->
            word.split("_").joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercase() }
            }
        }
}
