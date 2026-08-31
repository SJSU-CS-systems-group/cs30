package com.cs30.server.service

import com.cs30.server.dto.*
import com.cs30.server.repository.CourseRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import data.SubmissionInfo
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service
open class CodeService(
    private val courseRepository: CourseRepository,
    private val courseAccess: CourseAccessService,
    private val gitService: GitService,
    private val judgeService: JudgeService
) {
    private val log = org.slf4j.LoggerFactory.getLogger(CodeService::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    // Students currently mid-Run or mid-Submit. Same shape as ApiTokenStore.endingSessions — an
    // atomic claim keyed by the server-resolved studentEmail, so a fast double-click (or a client
    // scripting concurrent requests directly) can't fire two judge runs for one action. Shared
    // between Run and Submit, not two separate sets: there's no legitimate reason a student needs
    // both in flight at once.
    private val activeJudgeOperations = ConcurrentHashMap.newKeySet<String>()

    /**
     * Submit code for grading: saves to git repo and sends to judge.
     */
    fun submitCode(request: SubmitCodeRequest): SubmitCodeResponse {
        if (!activeJudgeOperations.add(request.studentEmail)) {
            return SubmitCodeResponse(false, "A run or submission is already in progress")
        }
        try {
            // Look up the course
            val course = courseRepository.findById(request.courseId).orElse(null)
                ?: return SubmitCodeResponse(false, "Course not found: ${request.courseId}")

            // Verify the caller belongs to the course (enrolled student, or its TA)
            if (!courseAccess.isMember(course, request.studentEmail)) {
                return SubmitCodeResponse(false, "Student ${request.studentEmail} is not enrolled in this course")
            }

            // Check lab deadline (the TA is never held to it)
            courseAccess.labDenialReason(course, request.labNumber, request.studentEmail)?.let {
                return SubmitCodeResponse(false, it)
            }

            // Get the repo path
            val repoPath = course.studentGitRepo
            if (repoPath.isBlank()) {
                return SubmitCodeResponse(false, "Course does not have a Git repository configured")
            }

            val language = request.language ?: course.language
            val extension = getExtension(language)
            val judgeLanguage = mapToJudgeLanguage(language)

            // Judge first so the result can be saved alongside the code with one shared timestamp.
            val judgeResult = try {
                judgeService.submit(
                    problemId = request.problemName,
                    poolPath = course.problemGitRepo,
                    language = judgeLanguage,
                    source = request.code
                )
            } catch (e: Exception) {
                logJudgeFailure(course.code, request.problemName, request.studentEmail, e)
                null
            }

            val response = if (judgeResult != null) {
                SubmitCodeResponse(
                    success = true,
                    message = "Submission judged successfully",
                    status = judgeResult.status,
                    passed = judgeResult.passed,
                    total = judgeResult.total,
                    maxTimeS = judgeResult.maxTimeS,
                    testcases = judgeResult.testcases.map { tc ->
                        TestcaseResult(
                            name = tc.name, status = tc.status, timeS = tc.timeS,
                            input = tc.input, expected = tc.expected, stdout = tc.stdout, stderr = tc.stderr
                        )
                    },
                    compileOutput = judgeResult.compileOutput
                )
            } else {
                SubmitCodeResponse(success = false, message = "Something went wrong while grading your submission. Please try again.")
            }

            // Persist code + result together under submissions/ (submission-<ts>.<ext> + result-<ts>.json).
            val filePath = try {
                gitService.saveSubmissionWithResult(
                    repoPath = repoPath,
                    section = request.section,
                    labNumber = request.labNumber,
                    problemName = request.problemName,
                    studentEmail = request.studentEmail,
                    code = request.code,
                    extension = extension,
                    result = objectMapper.writeValueAsString(response),
                )
            } catch (e: Exception) {
                logGitPersistFailure(course.code, request.problemName, request.studentEmail, e)
                // Grading may have genuinely succeeded, but the submission record itself never made
                // it into the git repo — the system of record for what a student actually turned
                // in. That's a real failure of this request as a whole (submitCode's contract is
                // "saves to git AND sends to judge"), not something safe to report as success.
                return response.copy(
                    success = false,
                    message = "${response.message} (failed to save submission)",
                )
            }

            return response.copy(filePath = filePath)
        } finally {
            activeJudgeOperations.remove(request.studentEmail)
        }
    }

    /**
     * Run code against sample testcases (doesn't save to git).
     */
    fun runCode(request: RunCodeRequest): RunCodeResponse {
        if (!activeJudgeOperations.add(request.studentEmail)) {
            return RunCodeResponse(false, "A run or submission is already in progress")
        }
        try {
            // Look up the course
            val course = courseRepository.findById(request.courseId).orElse(null)
                ?: return RunCodeResponse(false, "Course not found: ${request.courseId}")

            // Verify the caller belongs to the course (enrolled student, or its TA)
            if (!courseAccess.isMember(course, request.studentEmail)) {
                return RunCodeResponse(false, "Student ${request.studentEmail} is not enrolled in this course")
            }

            // Check lab deadline (the TA is never held to it)
            courseAccess.labDenialReason(course, request.labNumber, request.studentEmail)?.let {
                return RunCodeResponse(false, it)
            }

            // Determine language (from request or course default)
            val language = request.language ?: course.language
            val judgeLanguage = mapToJudgeLanguage(language)

            return try {
                val judgeResult = judgeService.run(
                    problemId = request.problemName,
                    poolPath = course.problemGitRepo,
                    language = judgeLanguage,
                    source = request.code,
                    customStdins = request.customStdins
                )

                RunCodeResponse(
                    success = true,
                    message = runOutcomeMessage(judgeResult),
                    testcases = judgeResult.testcases.map { tc ->
                        TestcaseResult(
                            name = tc.name,
                            status = tc.status,
                            timeS = tc.timeS,
                            input = tc.input,
                            expected = tc.expected,
                            stdout = tc.stdout,
                            stderr = tc.stderr
                        )
                    },
                    compileOutput = judgeResult.compileOutput
                )
            } catch (e: Exception) {
                logJudgeFailure(course.code, request.problemName, request.studentEmail, e)
                RunCodeResponse(false, "Something went wrong while running your code. Please try again.")
            }
        } finally {
            activeJudgeOperations.remove(request.studentEmail)
        }
    }

    // TODO(TA visibility): every judge/infra failure logged here (judge unreachable, a problem's
    // data misconfigured, etc.) currently dead-ends at this one log line — no TA or admin ever sees
    // it. Found during load testing: tenkindsofpeople (a problem with a custom output validator)
    // failed here with "FATAL ERROR: legacy is no longer supported" from bt, because its
    // problem.yaml predates a bt format change and had never been migrated (`bt upgrade`) — a real,
    // actionable misconfiguration a TA should be able to see and fix before students hit it, not
    // just students getting a generic "something went wrong" message. Planned fix: a new service
    // parallel to ActivityLogService, using LabHealthService's existing UNREACHABLE/NOT_READY/OTHER
    // classification, surfaced on the TA dashboard (extends TaActivityLogScreen's severity-tagged
    // feed pattern) — near-real-time, not the pull-based LabHealthController check that already
    // exists but only runs when a TA manually triggers it. This is the single call site that write
    // would hook into.
    // The student gets a fixed sentence, so this line is the only place the cause survives. `detail` is
    // the judge's own words, as its own field rather than buried in a wrapped message, so it can be
    // grepped.
    private fun logJudgeFailure(courseCode: String, problemName: String, studentEmail: String, e: Exception) {
        val judgeFailure = e as? JudgeUnavailableException
        log.error(
            "Judge error: course={} problem={} student={} judgeStatus={} detail={}",
            courseCode, problemName, studentEmail,
            judgeFailure?.httpStatus ?: "-",
            judgeFailure?.detail ?: e.message ?: e::class.simpleName,
            e,
        )
    }

    private fun logGitPersistFailure(courseCode: String, problemName: String, studentEmail: String, e: Exception) {
        log.error("Failed to save submission to git: course=$courseCode problem=$problemName student=$studentEmail: ${e.message}", e)
    }

    /**
     * Describes what the run actually produced, instead of a fixed success string.
     *
     * Not cosmetic. `HttpBackendService.runSummary` falls back to this message when the response
     * carries no testcases, so the previous hardcoded "Code executed successfully" was shown to the
     * student verbatim for a run that graded nothing — the same shape of false success as the
     * `worstStatus(emptyList) ?: "AC"` default that was removed from the judge.
     *
     * A compile error never reaches that fallback (runSummary checks compileOutput first and renders
     * "Compile error"), but the message is still wrong in the raw API response, which is what any
     * non-UI consumer reads.
     */
    private fun runOutcomeMessage(judgeResult: JudgeRunResponse): String = when {
        judgeResult.compileOutput != null -> "Your code did not compile"
        judgeResult.testcases.isEmpty() ->
            "No test cases were run — this problem may have no sample tests, or its test data may be misconfigured"
        else -> "Code executed successfully"
    }

    private fun getExtension(language: String): String {
        return when (language.lowercase()) {
            "java" -> "java"
            "python" -> "py"
            "c" -> "c"
            "c++", "cpp" -> "cpp"
            "javascript", "js" -> "js"
            else -> "txt"
        }
    }

    private fun mapToJudgeLanguage(language: String): String {
        return when (language.lowercase()) {
            "java" -> "java"
            "python" -> "python"
            "c" -> "c"
            "c++", "cpp" -> "cpp"
            "javascript", "js" -> "javascript"
            else -> language.lowercase()
        }
    }

    /**
     * List all past submissions for a student's problem, sorted by timestamp (latest first).
     */
    fun listSubmissions(
        courseId: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        studentEmail: String
    ): List<SubmissionInfo> {
        val course = courseRepository.findById(courseId).orElse(null) ?: return emptyList()

        if (!courseAccess.isMember(course, studentEmail)) {
            log.warn("Student $studentEmail not enrolled in course $courseId")
            return emptyList()
        }

        val repoPath = course.studentGitRepo
        if (repoPath.isBlank()) return emptyList()

        val submissionsDir = File(repoPath, "section_$section/lab_$labNumber/$problemName/$studentEmail/submissions")
        if (!submissionsDir.exists() || !submissionsDir.isDirectory) {
            return emptyList()
        }

        // Find all result-*.json files and parse them
        val submissions = submissionsDir.listFiles()
            ?.filter { it.name.startsWith("result-") && it.name.endsWith(".json") }
            ?.mapNotNull { resultFile ->
                try {
                    val result = objectMapper.readValue<Map<String, Any?>>(resultFile)
                    val stem = resultFile.name.removePrefix("result-").removeSuffix(".json")
                    val tIndex = stem.indexOf('T')
                    // Filename uses hyphens in the time part (e.g. T20-35-50) for filesystem
                    // compatibility; convert back to colons to form a valid ISO 8601 instant.
                    val timestamp = if (tIndex >= 0)
                        stem.substring(0, tIndex + 1) + stem.substring(tIndex + 1).replace('-', ':') + "Z"
                    else stem + "Z"

                    val tsKey = stem
                    val filePath = (result["codeFilePath"] as? String)?.takeIf { it.isNotBlank() }
                        ?: submissionsDir.listFiles()
                            ?.find { it.name.startsWith("submission-$tsKey") }
                            ?.absolutePath
                        ?: ""

                    val code = filePath.takeIf { it.isNotBlank() }?.let { path ->
                        runCatching { File(path).readText() }.getOrDefault("")
                    } ?: ""

                    SubmissionInfo(
                        timestamp = timestamp,
                        passed = (result["passed"] as? Number)?.toInt() ?: 0,
                        total = (result["total"] as? Number)?.toInt() ?: 0,
                        maxTimeMs = (result["maxTimeS"] as? Number)?.toDouble()?.times(1000),
                        status = (result["status"] as? String) ?: "Unknown",
                        filePath = filePath,
                        code = code,
                    )
                } catch (e: Exception) {
                    log.error("Failed to parse submission result: ${resultFile.name}", e)
                    null
                }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()

        return submissions
    }
}