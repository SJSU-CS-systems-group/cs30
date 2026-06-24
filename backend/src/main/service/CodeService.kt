package com.cs30.server.service

import com.cs30.server.dto.*
import com.cs30.server.repository.CourseRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.stereotype.Service

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
        val lab = course.labs.find { it.labNumber == request.labNumber }
            ?: return SubmitCodeResponse(false, "Lab ${request.labNumber} not found")
        if (java.time.LocalDateTime.now().isAfter(lab.endDateTime)) {
            return SubmitCodeResponse(false, "Lab submission deadline has passed")
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
        val lab = course.labs.find { it.labNumber == request.labNumber }
            ?: return RunCodeResponse(false, "Lab ${request.labNumber} not found")
        if (java.time.LocalDateTime.now().isAfter(lab.endDateTime)) {
            return RunCodeResponse(false, "Lab deadline has passed")
        }

        // Determine language (from request or course default)
        val language = request.language ?: course.language
        val judgeLanguage = mapToJudgeLanguage(language)

        return try {
            val judgeResult = judgeService.run(
                problemId = request.problemName,
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
}