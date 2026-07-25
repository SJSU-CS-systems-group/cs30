package com.cs30.server.service

import com.cs30.server.dto.LabHealthReport
import com.cs30.server.dto.ProblemHealth
import com.cs30.server.dto.ProblemStatus
import com.cs30.server.repository.CourseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Verifies a lab is ready to open: every problem has its statement (index.html + problem.css) and
 * judge package (problem.yaml + data/) in the repo, and an accepted solution actually grades to AC.
 *
 * The judge is exercised with `/submit` only — one grade per problem. Reachability and readiness
 * are inferred from the grade outcomes rather than dedicated health calls: a connection failure
 * means the judge is down; a 503/Docker error means it isn't ready.
 */
@Service
class LabHealthService(
    private val courseRepository: CourseRepository,
    private val gitService: GitService,
    private val judgeService: JudgeService,
) {
    private val log = LoggerFactory.getLogger(LabHealthService::class.java)

    fun checkLab(courseId: String, labNumber: Int): LabHealthReport {
        val course = courseRepository.findById(courseId).orElse(null)
            ?: return fail(courseId, labNumber, "Course not found: $courseId")

        val repo = course.problemGitRepo
        if (repo.isBlank()) {
            return fail(courseId, labNumber, "Course has no problem git repository configured")
        }

        val lab = course.labs.find { it.labNumber == labNumber }
            ?: return fail(courseId, labNumber, "Lab $labNumber not found in course")

        var judgeReachable = true
        var judgeReady = true
        val problems = lab.problems.distinctBy { it.name }.sortedBy { it.name }.map { problem ->
            val name = problem.name
            // Language is configured in the DB, same as the problem pool path — per-problem, falling
            // back to the course default. We never guess it from the accepted solution's file.
            val language = problem.language.ifBlank { course.language }
            val files = gitService.problemFilesReady(repo, name, language)
            val packagePresent = files.problemYaml && files.data
            // Statement (html/css) + package (problem.yaml + data/) files must all be present.
            val fileMissing = buildList {
                if (!files.html) add("index.html")
                if (!files.css) add("problem.css")
                if (!files.problemYaml) add("problem.yaml")
                if (!files.data) add("data/")
            }

            when {
                // The problem configured for this lab isn't in the pool at all — distinct, clearer
                // than listing every file as "missing".
                !files.present ->
                    ProblemHealth(name, false, false, false, false,
                        ProblemStatus.NOT_READY, detail = "Problem '$name' not found in the pool ($repo)")

                fileMissing.isNotEmpty() ->
                    ProblemHealth(name, files.html, files.css, packagePresent, files.hasAnyAcceptedSolution,
                        ProblemStatus.NOT_READY, detail = "Missing: ${fileMissing.joinToString(", ")}")

                language.isBlank() ->
                    ProblemHealth(name, files.html, files.css, true, files.hasAnyAcceptedSolution,
                        ProblemStatus.NOT_READY, detail = "No language configured for this problem or course")

                // Files are all there, but no reference solution in the configured language to grade —
                // flag it here, before touching the judge, so we never compile a solution as the wrong
                // language (which would look like a spurious CE).
                files.acceptedSolution == null ->
                    ProblemHealth(name, files.html, files.css, true, files.hasAnyAcceptedSolution,
                        ProblemStatus.UNVERIFIED, detail = if (files.hasAnyAcceptedSolution)
                            "Accepted solutions exist, but none for the configured language '$language' — grading could not be verified."
                        else
                            "No accepted solution in submissions/accepted/ — grading could not be verified.")

                else -> try {
                    val result = judgeService.submit(name, repo, language, files.acceptedSolution!!.readText())
                    val graded = result.status == "AC" && result.total > 0 && result.passed == result.total
                    ProblemHealth(
                        name = name,
                        htmlPresent = files.html, cssPresent = files.css,
                        packagePresent = true, acceptedSolutionPresent = true,
                        status = if (graded) ProblemStatus.READY else ProblemStatus.NOT_READY,
                        verdict = result.status, passed = result.passed, total = result.total,
                        detail = if (graded) null else "Accepted solution graded ${result.status} (${result.passed}/${result.total})",
                    )
                } catch (e: Exception) {
                    when (classify(e)) {
                        JudgeFailure.UNREACHABLE -> judgeReachable = false
                        JudgeFailure.NOT_READY -> judgeReady = false
                        JudgeFailure.OTHER -> {}
                    }
                    log.warn("Grading failed for problem '$name': ${e.message}")
                    ProblemHealth(name, files.html, files.css, true, true, ProblemStatus.NOT_READY,
                        detail = "Grading failed: ${e.message}")
                }
            }
        }

        // Problem-specific messages so the TA dashboard can name exactly which problems are broken
        // (errors) vs merely unverifiable (warnings — e.g. no accepted solution in the configured
        // language). Warnings don't block the lab; errors do.
        val errors = problems.filter { it.status == ProblemStatus.NOT_READY }
            .map { "${it.name}: ${it.detail ?: "not ready"}" }
        val warnings = problems.filter { it.status == ProblemStatus.UNVERIFIED }
            .map { "${it.name}: ${it.detail ?: "grading could not be verified"}" }

        return LabHealthReport(
            courseId = courseId,
            labNumber = labNumber,
            ok = judgeReachable && judgeReady && errors.isEmpty(),
            judgeReachable = judgeReachable,
            judgeReady = judgeReady,
            problems = problems,
            errors = errors,
            warnings = warnings,
        )
    }

    private fun fail(courseId: String, labNumber: Int, detail: String) =
        LabHealthReport(courseId, labNumber, ok = false, judgeReachable = false, judgeReady = false,
            problems = emptyList(), detail = detail)

    private enum class JudgeFailure { UNREACHABLE, NOT_READY, OTHER }

    /** Classify a judge submit failure so the report can distinguish "judge down" from "not ready". */
    private fun classify(e: Throwable): JudgeFailure {
        if (e is java.net.ConnectException || e is java.io.IOException) return JudgeFailure.UNREACHABLE
        val msg = (e.message ?: "").lowercase()
        return when {
            "connection refused" in msg || "connect timed out" in msg || "failed to connect" in msg -> JudgeFailure.UNREACHABLE
            "(503)" in msg || "docker" in msg || "image not found" in msg -> JudgeFailure.NOT_READY
            else -> JudgeFailure.OTHER
        }
    }
}
