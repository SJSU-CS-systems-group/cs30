package com.cs30.server.service

import com.cs30.server.dto.*
import com.cs30.server.repository.CourseRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import data.SubmissionInfo
import org.springframework.stereotype.Service
import java.io.File

@Service
open class CodeService(
    private val courseRepository: CourseRepository,
    private val gitService: GitService,
    private val judgeService: JudgeService
) {
    private val log = org.slf4j.LoggerFactory.getLogger(CodeService::class.java)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    fun saveCode(request: SaveCodeRequest): SaveCodeResponse {
        // Look up the course
        val course = courseRepository.findById(request.courseId).orElse(null)
            ?: return SaveCodeResponse(false, "Course not found: ${request.courseId}")

        // Verify student is enrolled
        if (!course.students.contains(request.studentEmail)) {
            return SaveCodeResponse(false, "Student ${request.studentEmail} is not enrolled in this course")
        }

        // Check lab deadline
        checkLabDeadline(course, request.labNumber)?.let {
            return SaveCodeResponse(false, it)
        }

        // Get the repo path
        val repoPath = course.studentGitRepo
        if (repoPath.isBlank()) {
            return SaveCodeResponse(false, "Course does not have a Git repository configured")
        }

        // Determine file extension based on course language
        val extension = when (course.language.lowercase()) {
            "java" -> "java"
            "python" -> "py"
            "c" -> "c"
            "c++" -> "cpp"
            "javascript" -> "js"
            else -> "txt"
        }

        return try {
            val filePath = gitService.saveAndCommit(
                repoPath = repoPath,
                section = request.section,
                labNumber = request.labNumber,
                problemName = request.problemName,
                studentEmail = request.studentEmail,
                code = request.code,
                extension = extension,
                saveType = request.saveType
            )
            SaveCodeResponse(true, "Code saved successfully", filePath)
        } catch (e: Exception) {
            SaveCodeResponse(false, "Failed to save code: ${e.message}")
        }
    }

    /**
     * Submit code for grading: saves to git repo and sends to judge.
     */
    fun submitCode(request: SubmitCodeRequest): SubmitCodeResponse {
        // Look up the course
        val course = courseRepository.findById(request.courseId).orElse(null)
            ?: return SubmitCodeResponse(false, "Course not found: ${request.courseId}")

        // Verify student is enrolled
        if (!course.students.contains(request.studentEmail)) {
            return SubmitCodeResponse(false, "Student ${request.studentEmail} is not enrolled in this course")
        }

        // Check lab deadline
        checkLabDeadline(course, request.labNumber)?.let {
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
            log.error("Judge error: ${e.message}", e)
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
            SubmitCodeResponse(success = false, message = "Judge error while grading submission")
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
            log.error("Failed to save submission to git: ${e.message}", e)
            return response.copy(message = "${response.message} (failed to save: ${e.message})")
        }

        return response.copy(filePath = filePath)
    }

    /**
     * Run code against sample testcases (doesn't save to git).
     */
    fun runCode(request: RunCodeRequest): RunCodeResponse {
        // Look up the course
        val course = courseRepository.findById(request.courseId).orElse(null)
            ?: return RunCodeResponse(false, "Course not found: ${request.courseId}")

        // Verify student is enrolled
        if (!course.students.contains(request.studentEmail)) {
            return RunCodeResponse(false, "Student ${request.studentEmail} is not enrolled in this course")
        }

        // Check lab deadline
        checkLabDeadline(course, request.labNumber)?.let {
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
                message = "Code executed successfully",
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
            log.error("Judge error: ${e.message}", e)
            RunCodeResponse(false, "Judge error: ${e.message}")
        }
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

        if (!course.students.contains(studentEmail)) {
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
                    val timestamp = resultFile.name
                        .removePrefix("result-")
                        .removeSuffix(".json")
                        .replace("T", " ")
                        .replace("-", ":")
                        .replaceFirst(":", "-")
                        .replaceFirst(":", "-")  // Result: 2024-01-15 10:30:00

                    val tsKey = resultFile.name.removePrefix("result-").removeSuffix(".json")
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

    private fun checkLabDeadline(course: com.cs30.server.models.Course, labNumber: Int): String? {
        val lab = course.labs.find { it.labNumber == labNumber }
            ?: return "Lab $labNumber not found"
        if (java.time.LocalDateTime.now().isAfter(lab.endDateTime)) {
            return "Lab deadline has passed"
        }
        return null
    }
}