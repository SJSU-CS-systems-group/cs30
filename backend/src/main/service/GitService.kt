package com.cs30.server.service

import com.cs30.server.dto.SaveType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class SubmissionMetadata(
    val highestPassed: Int,
    val total: Int,
    val bestSubmissionPath: String
)

@Service
open class GitService(
    @Value("\${git.repos.base-path:/var/git/courses}")
    private val basePath: String,
    @Value("\${docker.path:/usr/local/bin/docker}")
    private val dockerPath: String,
    @Value("\${git.server.email:server@cs30.edu}")
    private val gitEmail: String,
    @Value("\${git.server.name:CS30 Server}")
    private val gitName: String,
) {
    private val log = LoggerFactory.getLogger(GitService::class.java)
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
    private val objectMapper = jacksonObjectMapper()

    @PostConstruct
    fun configureGitIdentity() {
        runLocal("git config --global user.email '$gitEmail'")
        runLocal("git config --global user.name '$gitName'")
        log.info("Git identity configured as: {} <{}>", gitName, gitEmail)
    }

    /**
     * Initializes a Git repository at the given path.
     * Creates the repo if it doesn't exist, skips if it already exists.
     */
    fun initGitRepo(repoPath: String) {
        if (repositoryExists(repoPath)) {
            return
        }

        val command = "mkdir -p $repoPath && cd $repoPath && git init && git config user.email 'server@cs30.edu' && git config user.name 'CS30 Server'"
        runLocal(command)
    }

    /**
     * Saves a file to a git repository and commits it.
     * @param repoPath Path to the git repo
     * @param localFilePath Path to the source file to copy
     * @param destFileName Name for the file in the repo (e.g., "course.yml")
     */
    fun saveFileToRepo(repoPath: String, localFilePath: String, destFileName: String) {
        val localFile = java.io.File(localFilePath)
        if (!localFile.exists()) {
            throw RuntimeException("Source file not found: $localFilePath")
        }

        val destFile = java.io.File(repoPath, destFileName)
        localFile.copyTo(destFile, overwrite = true)

        val commitCommand = "cd $repoPath && git add -A && git commit -m 'update: $destFileName'"
        runLocalCommit(commitCommand)
    }

    /**
     * Adds a single problem to the global problem repository.
     * Moves the problem folder to problemGitRepo/problemName/ and converts to HTML using problemtools.
     */
    fun addProblemToRepo(
        problemGitRepo: String,
        problemPath: String
    ) {
        val problemDir = java.io.File(problemPath)
        if (!problemDir.exists() || !problemDir.isDirectory) {
            throw RuntimeException("Problem path does not exist or is not a directory: $problemPath")
        }

        val problemName = problemDir.name
        val destPath = java.io.File(problemGitRepo, problemName)

        // Delete existing problem folder if it exists
        if (destPath.exists()) {
            log.info("Removing existing problem folder: {}", destPath)
            destPath.deleteRecursively()
        }

        // Move problem folder to repo
        log.info("Moving problem '{}' to {}", problemName, destPath)
        if (!problemDir.renameTo(destPath)) {
            // If rename fails (e.g., cross-filesystem), fall back to copy + delete
            problemDir.copyRecursively(destPath, overwrite = true)
            problemDir.deleteRecursively()
        }
        log.info("Problem moved to: {}", destPath)

        // Create temp directory for HTML output
        val tempDir = java.io.File.createTempFile("problemtools", "").apply {
            delete()
            mkdirs()
        }

        try {
            // Check if problemtools image exists, pull if not
            val imageCheck = ProcessBuilder(dockerPath, "image", "inspect", "problemtools/full:latest")
                .redirectErrorStream(true)
                .start()
            if (imageCheck.waitFor() != 0) {
                log.info("Pulling problemtools/full:latest image...")
                val pullProcess = ProcessBuilder(dockerPath, "pull", "problemtools/full:latest")
                    .inheritIO()
                    .start()
                if (pullProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to pull problemtools/full:latest image")
                }
                log.info("Image pulled successfully.")
            }

            log.info("Converting problem to HTML: {}", problemName)

            // Run docker to convert problem to HTML (read from repo, output to temp)
            // -c copies the CSS file to the output directory as problem.css
            val dockerProcess = ProcessBuilder(
                dockerPath, "run", "--rm",
                "-v", "$problemGitRepo:/problems:ro",
                "-v", "${tempDir.absolutePath}:/output",
                "--entrypoint", "problem2html",
                "problemtools/full:latest",
                "-c", "-d", "/output/$problemName",
                "/problems/$problemName"
            )
                .inheritIO()
                .start()

            if (dockerProcess.waitFor() != 0) {
                throw RuntimeException("Failed to convert problem: $problemName")
            }
            log.info("Converted: {}", problemName)

            // Copy the HTML files into the problem folder (overwrites/adds to existing files)
            val htmlSource = java.io.File(tempDir, problemName)
            htmlSource.copyRecursively(destPath, overwrite = true)

            log.info("Committing problem: {}", problemName)
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'add problem: $problemName'"
            runLocalCommit(commitCommand)

            log.info("Problem added successfully: {}", problemName)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Removes a problem from the global problem repository.
     */
    fun removeProblemFromRepo(
        problemGitRepo: String,
        problemName: String
    ) {
        val problemPath = java.io.File(problemGitRepo, problemName)
        log.info("Removing problem: {}", problemPath)

        if (problemPath.exists()) {
            problemPath.deleteRecursively()
        }

        log.info("Committing removal of: {}", problemName)
        val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'remove problem: $problemName'"
        runLocalCommit(commitCommand)

        log.info("Problem removed successfully: {}", problemName)
    }

    /**
     * Adds all problems from a root directory to the global problem repository.
     * Moves each problem folder to problemGitRepo/problemName/ and converts to HTML.
     * Deletes the source directory after all problems are moved.
     */
    fun addProblemsToRepo(
        problemGitRepo: String,
        problemsDir: String
    ) {
        val rootDir = java.io.File(problemsDir)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            throw RuntimeException("Problems directory does not exist or is not a directory: $problemsDir")
        }

        val problemDirs = rootDir.listFiles { file -> file.isDirectory }
            ?.sortedBy { it.name } ?: emptyList()

        if (problemDirs.isEmpty()) {
            throw RuntimeException("No problem directories found in: $problemsDir")
        }

        log.info("Found {} problem(s) to process: {}", problemDirs.size, problemDirs.map { it.name })

        // First, move all problem folders to the repo
        val movedProblems = mutableListOf<String>()
        for (problemDir in problemDirs) {
            val problemName = problemDir.name
            val destPath = java.io.File(problemGitRepo, problemName)

            // Delete existing problem folder if it exists
            if (destPath.exists()) {
                log.info("Removing existing problem folder: {}", destPath)
                destPath.deleteRecursively()
            }

            // Move problem folder to repo
            log.info("Moving problem '{}' to {}", problemName, destPath)
            if (!problemDir.renameTo(destPath)) {
                // If rename fails (e.g., cross-filesystem), fall back to copy + delete
                problemDir.copyRecursively(destPath, overwrite = true)
                problemDir.deleteRecursively()
            }
            movedProblems.add(problemName)
            log.info("Moved: {}", problemName)
        }

        // Delete the source directory (now empty)
        log.info("Removing source directory: {}", rootDir)
        rootDir.deleteRecursively()

        // Create temp directory for HTML output
        val tempDir = java.io.File.createTempFile("problemtools", "").apply {
            delete()
            mkdirs()
        }

        try {
            // Check if problemtools image exists, pull if not
            val imageCheck = ProcessBuilder(dockerPath, "image", "inspect", "problemtools/full:latest")
                .redirectErrorStream(true)
                .start()
            if (imageCheck.waitFor() != 0) {
                log.info("Pulling problemtools/full:latest image...")
                val pullProcess = ProcessBuilder(dockerPath, "pull", "problemtools/full:latest")
                    .inheritIO()
                    .start()
                if (pullProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to pull problemtools/full:latest image")
                }
                log.info("Image pulled successfully.")
            }

            // Convert each problem to HTML
            for (problemName in movedProblems) {
                log.info("Converting to HTML: {}", problemName)

                // Run docker to convert problem to HTML (read from repo, output to temp)
                // -c copies the CSS file to the output directory as problem.css
                val dockerProcess = ProcessBuilder(
                    dockerPath, "run", "--rm",
                    "-v", "$problemGitRepo:/problems:ro",
                    "-v", "${tempDir.absolutePath}:/output",
                    "--entrypoint", "problem2html",
                    "problemtools/full:latest",
                    "-c", "-d", "/output/$problemName",
                    "/problems/$problemName"
                )
                    .inheritIO()
                    .start()

                if (dockerProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to convert problem: $problemName")
                }
                log.info("Converted: {}", problemName)

                // Copy the HTML files into the problem folder
                val destPath = java.io.File(problemGitRepo, problemName)
                val htmlSource = java.io.File(tempDir, problemName)
                htmlSource.copyRecursively(destPath, overwrite = true)

                // Clean up this problem's temp output
                htmlSource.deleteRecursively()
            }

            log.info("Committing {} problems...", movedProblems.size)
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'add ${movedProblems.size} problems'"
            runLocalCommit(commitCommand)

            log.info("{} problem(s) added successfully", movedProblems.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Saves a file to the repository and commits it.
     * Creates the full folder structure on first save.
     */
    fun saveAndCommit(
        repoPath: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        studentEmail: String,
        code: String,
        extension: String,
        saveType: SaveType
    ): String {
        val timestamp = LocalDateTime.now().format(timestampFormatter)
        val studentDir = "section_$section/lab_$labNumber/$problemName/$studentEmail"
        val autosaveDir = "$studentDir/autosave"
        val submissionsDir = "$studentDir/submissions"

        val (relativeFilePath, commitMessage) = when (saveType) {
            SaveType.AUTOSAVE -> {
                Pair("$autosaveDir/autosave-$timestamp.$extension", "Autosave: section_$section/lab_$labNumber/$problemName/$studentEmail")
            }
            SaveType.SUBMISSION -> {
                Pair("$submissionsDir/submission-$timestamp.$extension", "Submission: section_$section/lab_$labNumber/$problemName/$studentEmail")
            }
        }

        // Create directories
        java.io.File(repoPath, autosaveDir).mkdirs()
        java.io.File(repoPath, submissionsDir).mkdirs()

        // Write the file
        val fullFilePath = java.io.File(repoPath, relativeFilePath)
        fullFilePath.writeText(code)

        // For autosave, also update latest.<ext>
        if (saveType == SaveType.AUTOSAVE) {
            val latestPath = java.io.File(repoPath, "$autosaveDir/latest.$extension")
            latestPath.writeText(code)
        }

        val command = "cd $repoPath && git add -A && git commit -m '$commitMessage'"
        runLocalCommit(command)

        return relativeFilePath
    }

    /**
     * Saves a submission's code and its judge result together under submissions/,
     * sharing one timestamp: submission-<ts>.<ext> and result-<ts>.<resultExtension>.
     * Also updates metadata.json with the highest score if this submission is better.
     * Returns the code file's repo-relative path.
     */
    fun saveSubmissionWithResult(
        repoPath: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        studentEmail: String,
        code: String,
        extension: String,
        result: String,
        resultExtension: String = "json",
    ): String {
        val timestamp = LocalDateTime.now().format(timestampFormatter)
        val submissionsDir = "section_$section/lab_$labNumber/$problemName/$studentEmail/submissions"
        java.io.File(repoPath, submissionsDir).mkdirs()

        val codePath = "$submissionsDir/submission-$timestamp.$extension"
        val resultPath = "$submissionsDir/result-$timestamp.$resultExtension"
        val absoluteCodePath = java.io.File(repoPath, codePath).absolutePath
        java.io.File(repoPath, codePath).writeText(code)

        // Embed the code file's absolute path in the result JSON so listSubmissions can retrieve
        // it without fragile file-name matching.
        val resultWithPath = try {
            val map = objectMapper.readValue<MutableMap<String, Any?>>(result)
            map["codeFilePath"] = absoluteCodePath
            objectMapper.writeValueAsString(map)
        } catch (_: Exception) {
            result
        }
        java.io.File(repoPath, resultPath).writeText(resultWithPath)

        // Update metadata with highest score
        updateMetadataIfBetter(repoPath, submissionsDir, codePath, result)

        val command = "cd $repoPath && git add -A && git commit -m 'Submission: section_$section/lab_$labNumber/$problemName/$studentEmail'"
        runLocalCommit(command)

        return codePath
    }

    /**
     * Updates metadata.json if this submission has a higher or equal score.
     */
    private fun updateMetadataIfBetter(
        repoPath: String,
        submissionsDir: String,
        codePath: String,
        result: String
    ) {
        try {
            // Parse the result to get passed/total
            val resultJson = objectMapper.readValue<Map<String, Any?>>(result)
            val passed = (resultJson["passed"] as? Number)?.toInt() ?: 0
            val total = (resultJson["total"] as? Number)?.toInt() ?: 0

            val metadataFile = java.io.File(repoPath, "$submissionsDir/bestsubmission.json")

            // Check if we should update (new score >= existing highest)
            val shouldUpdate = if (metadataFile.exists()) {
                val existing = objectMapper.readValue<SubmissionMetadata>(metadataFile)
                passed > existing.highestPassed ||
                    (passed == existing.highestPassed && total == existing.total)
            } else {
                true
            }

            if (shouldUpdate) {
                val metadata = SubmissionMetadata(
                    highestPassed = passed,
                    total = total,
                    bestSubmissionPath = codePath
                )
                metadataFile.writeText(objectMapper.writeValueAsString(metadata))
            }
        } catch (e: Exception) {
            log.error("Failed to update metadata: {}", e.message, e)
        }
    }

    /**
     * Deletes a Git repository.
     */
    fun deleteRepository(repoPath: String): Boolean {
        val dir = java.io.File(repoPath)
        return if (dir.exists()) {
            dir.deleteRecursively()
        } else {
            true
        }
    }

    /**
     * Saves autosaved-solution.{extension} to the student directory and commits it.
     */
    fun saveAutosolution(
        repoPath: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        studentEmail: String,
        code: String,
        extension: String,
        authorEmail: String,
    ) {
        val studentDir = java.io.File(repoPath, "section_$section/lab_$labNumber/$problemName/$studentEmail")
        studentDir.mkdirs()

        val filePath = java.io.File(studentDir, "autosaved-solution.$extension")
        filePath.writeText(code)

        val command = """
            cd "$repoPath" &&
            git add -A &&
            git commit --author="$authorEmail <$authorEmail>" -m "autosave: $problemName [$authorEmail]"
        """.trimIndent()
        runLocalCommit(command)
    }

    /**
     * Appends one CSV row to the student's daily activity log:
     * section_{section}/ActivityLogs/{date}/{studentEmail}_{date}_activity.csv
     * One file per student per day; the token column distinguishes login sessions.
     */
    fun appendActivityLog(
        repoPath: String,
        section: Int,
        studentEmail: String,
        date: String,
        csvRow: String,
    ) {
        val dir = java.io.File(repoPath, "section_$section/ActivityLogs/$date")
        dir.mkdirs()

        val csvFile = java.io.File(dir, "${studentEmail}_${date}_activity.csv")
        val header = "token,timestamp_ms,timestamp_iso,platform,problem,event_kind,detail"

        if (!csvFile.exists()) {
            csvFile.writeText("$header\n")
        }
        csvFile.appendText("$csvRow\n")
    }

    /**
     * Commits the activity log(s) when a lockdown session ends.
     */
    fun commitActivityLog(
        repoPath: String,
        authorEmail: String,
    ) {
        val command = """
            cd "$repoPath" &&
            git add -A &&
            git commit --author="$authorEmail <$authorEmail>" -m "activity log [$authorEmail]"
        """.trimIndent()
        runLocalCommit(command)
    }

    /** Executes a shell command locally. Throws on non-zero exit. */
    private fun runLocal(command: String): String {
        log.debug("git cmd: {}", command)
        val process = ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        log.debug("git exit={} output: {}", exitCode, output.trim())
        if (exitCode != 0) {
            log.error("git command failed (exit {}): {}", exitCode, output.trim())
            throw RuntimeException("Command failed: $output")
        }
        return output
    }

    /**
     * Runs a git commit command. Treats "nothing to commit" as a non-error (logs at DEBUG).
     * All other non-zero exits are logged at ERROR and thrown.
     */
    private fun runLocalCommit(command: String) {
        log.debug("git cmd: {}", command)
        val process = ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        log.debug("git exit={} output: {}", exitCode, output.trim())
        if (exitCode != 0 && !output.contains("nothing to commit")) {
            log.error("git commit failed (exit {}): {}", exitCode, output.trim())
            throw RuntimeException("Git commit failed: $output")
        }
    }

    /**
     * Reads the latest autosaved code for a student/problem, or null if none exists.
     * Reads exactly the file written by [saveAutosolution].
     */
    fun readLatestAutosave(
        repoPath: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        studentEmail: String,
        extension: String
    ): String? {
        val file = java.io.File(repoPath, "section_$section/lab_$labNumber/$problemName/$studentEmail/autosaved-solution.$extension")
        return if (file.exists()) file.readText() else null
    }

    /**
     * Checks if a repository exists.
     */
    fun repositoryExists(repoPath: String): Boolean {
        return java.io.File(repoPath).isDirectory
    }

    /**
     * Checks if a problem exists in the global problem repository.
     */
    fun problemExistsInRepo(problemGitRepo: String, problemName: String): Boolean {
        val problemPath = java.io.File(problemGitRepo, "$problemName/index.html")
        return problemPath.exists()
    }
}