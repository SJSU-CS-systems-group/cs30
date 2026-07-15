package com.cs30.server.service

import com.cs30.server.repository.LoginSessionRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

data class SubmissionMetadata(
    val highestPassed: Int,
    val total: Int,
    val bestSubmissionPath: String
)

/**
 * What's present for a problem in the repo.
 * `acceptedSolution` is a reference solution in the requested language (null if none match);
 * `hasAnyAcceptedSolution` is true if submissions/accepted/ holds any solution at all, so callers
 * can tell "no accepted solution" apart from "none in the configured language".
 */
data class ProblemFiles(
    val html: Boolean,
    val css: Boolean,
    val problemYaml: Boolean,
    val data: Boolean,
    val acceptedSolution: java.io.File?,
    val hasAnyAcceptedSolution: Boolean,
)

@Service
open class GitService(
    @Value("\${git.repos.base-path:/var/git/courses}")
    private val basePath: String,
    // Bare "docker" so it resolves via PATH on any host (matches the judge). Override with
    // docker.path / DOCKER_PATH only for non-standard install locations.
    @Value("\${docker.path:docker}")
    private val dockerPath: String,
    @Value("\${git.server.email:server@cs30.edu}")
    private val gitEmail: String,
    @Value("\${git.server.name:CS30 Server}")
    private val gitName: String,
    private val loginSessionRepository: LoginSessionRepository,
) {
    private val log = LoggerFactory.getLogger(GitService::class.java)

    companion object {
        private const val REPO_LOCK_TIMEOUT_SECONDS = 30L
        private const val REPO_LOCK_WARN_THRESHOLD_MS = 2000L
    }

    private val repoLocks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Serializes all git operations (add/commit/init) against one repo. git add -A stages the
     * entire working tree and only one git process can hold .git/index.lock at once — with ~30
     * students committing to the same shared course repo, unserialized access crashes with
     * index.lock collisions. This is repo-level contention, not file-level: even though every
     * student's file is at a disjoint path, git's index/lock/branch history are singular per
     * repository, so any two git operations against the same repo must be serialized regardless
     * of which files they touch. Keyed by canonical path so string variance (trailing slash, etc.)
     * can't split one real repo into two locks. Bounded wait, not indefinite: a wedged git
     * subprocess should surface as an error, not silently exhaust the Tomcat thread pool. Logs a
     * warning if the wait itself was non-trivial, so contention is visible long before it's ever
     * severe enough to approach the timeout.
     *
     * Deliberately NOT applied to plain filesystem reads/writes (readLatestAutosave,
     * appendActivityLog, the per-student file writes in saveSubmissionWithResult/saveAutosolution,
     * etc.) — those only ever touch one student's own path, so they can never collide with each
     * other and don't need to wait on anything. Only git operations touch shared, repo-global state.
     */
    private fun <T> withRepoLock(repoPath: String, block: () -> T): T {
        val canonical = java.io.File(repoPath).canonicalPath
        val lock = repoLocks.computeIfAbsent(canonical) { ReentrantLock() }
        val waitStartNanos = System.nanoTime()
        if (!lock.tryLock(REPO_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw RuntimeException("Timed out waiting for git repo lock: $repoPath")
        }
        val waitMs = (System.nanoTime() - waitStartNanos) / 1_000_000
        if (waitMs > REPO_LOCK_WARN_THRESHOLD_MS) {
            log.warn("[GitService] waited {}ms for repo lock: {}", waitMs, repoPath)
        }
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** The student's current device IP (via login_sessions), for commit messages — not the git author. */
    private fun ipFor(studentEmail: String): String =
        loginSessionRepository.findByStudentEmailAndLoggedOutAtIsNull(studentEmail)?.ipAddress ?: "unknown-ip"
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
    private val objectMapper = jacksonObjectMapper()

    /**
     * Initializes a Git repository at the given path. Creates the repo if it doesn't exist.
     * Identity is set separately by [ensureLocalGitIdentity] (called from every commit), not here —
     * this skips entirely for already-existing repos, and per the README's setup instructions every
     * managed repo is manually `git init`'d before this is ever called on it.
     */
    fun initGitRepo(repoPath: String) {
        if (repositoryExists(repoPath)) {
            return
        }

        withRepoLock(repoPath) {
            val command = "mkdir -p $repoPath && cd $repoPath && git init"
            runLocal(command)
        }
    }

    /**
     * Sets the server's git identity locally on this one repo — never `--global`, which would
     * overwrite the identity of whatever developer/admin machine happens to run this backend.
     * Idempotent and cheap, so it's called before every commit rather than once at startup, which
     * also covers repos that already existed before the backend ever touched them (see initGitRepo).
     */
    private fun ensureLocalGitIdentity(repoPath: String) {
        runLocal("cd \"$repoPath\" && git config user.email '$gitEmail' && git config user.name '$gitName'")
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
        runLocalCommit(repoPath, commitCommand)
    }

    /**
     * Adds a single problem to the global problem repository.
     * Converts to HTML using problemtools first, then moves the problem folder to problemGitRepo/problemName/.
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
                    .redirectErrorStream(true)
                    .start()
                val pullOutput = pullProcess.inputStream.bufferedReader().readText()
                if (pullProcess.waitFor() != 0) {
                    val hint = if (pullOutput.contains("permission denied", ignoreCase = true)) {
                        " (Try running again with sudo)"
                    } else ""
                    throw RuntimeException("Failed to pull problemtools/full:latest image$hint")
                }
                log.info("Image pulled successfully.")
            }

            log.info("Converting problem to HTML: {}", problemName)

            // Run docker to convert problem to HTML (read from source, output to temp)
            // -c copies the CSS file to the output directory as problem.css
            val dockerProcess = ProcessBuilder(
                dockerPath, "run", "--rm",
                "-v", "${problemDir.parentFile.absolutePath}:/problems:ro",
                "-v", "${tempDir.absolutePath}:/output",
                "--entrypoint", "problem2html",
                "problemtools/full:latest",
                "-c", "-d", "/output/$problemName",
                "/problems/$problemName"
            )
                .redirectErrorStream(true)
                .start()
            val convertOutput = dockerProcess.inputStream.bufferedReader().readText()

            if (dockerProcess.waitFor() != 0) {
                val hint = if (convertOutput.contains("permission denied", ignoreCase = true)) {
                    " (Try running with sudo or add your user to the docker group: sudo usermod -aG docker \$USER)"
                } else ""
                throw RuntimeException("Failed to convert problem: $problemName$hint")
            }
            log.info("Converted: {}", problemName)

            // Delete old HTML/CSS files if they exist in the source folder
            java.io.File(problemDir, "index.html").delete()
            java.io.File(problemDir, "problem.css").delete()

            // Copy the HTML files into the source problem folder
            val htmlSource = java.io.File(tempDir, problemName)
            htmlSource.copyRecursively(problemDir, overwrite = true)

            // Delete existing problem folder in repo if it exists
            if (destPath.exists()) {
                log.info("Removing existing problem folder: {}", destPath)
                destPath.deleteRecursively()
            }

            // Move problem folder (now with HTML/CSS) to repo
            log.info("Moving problem '{}' to {}", problemName, destPath)
            if (!problemDir.renameTo(destPath)) {
                // If rename fails (e.g., cross-filesystem), fall back to copy + delete
                problemDir.copyRecursively(destPath, overwrite = true)
                problemDir.deleteRecursively()
            }
            log.info("Problem moved to: {}", destPath)

            log.info("Committing problem: {}", problemName)
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'add problem: $problemName'"
            runLocalCommit(problemGitRepo, commitCommand)

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
        runLocalCommit(problemGitRepo, commitCommand)

        log.info("Problem removed successfully: {}", problemName)
    }

    /**
     * Adds all problems from a root directory to the global problem repository.
     * Converts each problem to HTML first, then moves the folders to problemGitRepo/problemName/.
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
                    .redirectErrorStream(true)
                    .start()
                val pullOutput = pullProcess.inputStream.bufferedReader().readText()
                if (pullProcess.waitFor() != 0) {
                    val hint = if (pullOutput.contains("permission denied", ignoreCase = true)) {
                        " (Try running with sudo or add your user to the docker group: sudo usermod -aG docker \$USER)"
                    } else ""
                    throw RuntimeException("Failed to pull problemtools/full:latest image$hint")
                }
                log.info("Image pulled successfully.")
            }

            // First, convert all problems to HTML (reading from source directory)
            for (problemDir in problemDirs) {
                val problemName = problemDir.name
                log.info("Converting to HTML: {}", problemName)

                // Run docker to convert problem to HTML (read from source, output to temp)
                // -c copies the CSS file to the output directory as problem.css
                val dockerProcess = ProcessBuilder(
                    dockerPath, "run", "--rm",
                    "-v", "${rootDir.absolutePath}:/problems:ro",
                    "-v", "${tempDir.absolutePath}:/output",
                    "--entrypoint", "problem2html",
                    "problemtools/full:latest",
                    "-c", "-d", "/output/$problemName",
                    "/problems/$problemName"
                )
                    .redirectErrorStream(true)
                    .start()
                val convertOutput = dockerProcess.inputStream.bufferedReader().readText()

                if (dockerProcess.waitFor() != 0) {
                    val hint = if (convertOutput.contains("permission denied", ignoreCase = true)) {
                        " (Try running with sudo or add your user to the docker group: sudo usermod -aG docker \$USER)"
                    } else ""
                    throw RuntimeException("Failed to convert problem: $problemName$hint")
                }
                log.info("Converted: {}", problemName)

                // Delete old HTML/CSS files if they exist in the source folder
                java.io.File(problemDir, "index.html").delete()
                java.io.File(problemDir, "problem.css").delete()

                // Copy the HTML files into the source problem folder
                val htmlSource = java.io.File(tempDir, problemName)
                htmlSource.copyRecursively(problemDir, overwrite = true)

                // Clean up this problem's temp output
                htmlSource.deleteRecursively()
            }

            // Now move all problem folders (with HTML/CSS) to the repo
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

            log.info("Committing {} problems...", movedProblems.size)
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'add ${movedProblems.size} problems'"
            runLocalCommit(problemGitRepo, commitCommand)

            log.info("{} problem(s) added successfully", movedProblems.size)
        } finally {
            tempDir.deleteRecursively()
        }
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

        val command = "cd $repoPath && git add -A && git commit -m 'Submission: section_$section/lab_$labNumber/$problemName/${ipFor(studentEmail)}'"
        runLocalCommit(repoPath, command)

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
            git commit --author="$authorEmail <$authorEmail>" -m "autosave: $problemName [${ipFor(authorEmail)}]"
        """.trimIndent()
        runLocalCommit(repoPath, command)
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
     * Commits the activity log(s) when a lockdown session ends. Adds only this student's own
     * activity CSV file(s), never `git add -A` — since appendActivityLog doesn't commit
     * immediately, a broad `-A` here would risk sweeping up another student's not-yet-committed
     * row into this commit's authorship. Scoping to just this student's files means
     * appendActivityLog needs no locking of its own: it's a plain per-student file write, and this
     * can never touch a file that isn't this student's.
     */
    fun commitActivityLog(
        repoPath: String,
        section: Int,
        authorEmail: String,
    ) {
        val activityLogsDir = java.io.File(repoPath, "section_$section/ActivityLogs")
        val studentFiles = activityLogsDir.listFiles { it.isDirectory }
            ?.flatMap { dateDir ->
                dateDir.listFiles { f -> f.name.startsWith("${authorEmail}_") && f.name.endsWith("_activity.csv") }
                    ?.toList().orEmpty()
            }
            .orEmpty()

        if (studentFiles.isEmpty()) {
            log.debug("[GitService] no pending activity log files for {}", authorEmail)
            return
        }

        val repoRoot = java.io.File(repoPath)
        val addArgs = studentFiles.joinToString(" ") { "\"${it.relativeTo(repoRoot).path}\"" }
        val command = """
            cd "$repoPath" &&
            git add $addArgs &&
            git commit --author="$authorEmail <$authorEmail>" -m "activity log [${ipFor(authorEmail)}]"
        """.trimIndent()
        runLocalCommit(repoPath, command)
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
     * Runs a git commit command against repoPath. Ensures local git identity first (see
     * ensureLocalGitIdentity) so this never depends on the running machine's global git config.
     * Treats "nothing to commit" as a non-error (logs at DEBUG). All other non-zero exits are
     * logged at ERROR and thrown.
     */
    private fun runLocalCommit(repoPath: String, command: String) {
        withRepoLock(repoPath) {
            ensureLocalGitIdentity(repoPath)
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
        return java.io.File(repoPath, ".git").isDirectory
    }

    /**
     * Checks if a problem exists in the global problem repository.
     */
    fun problemExistsInRepo(problemGitRepo: String, problemName: String): Boolean {
        return problemFilesReady(problemGitRepo, problemName).html
    }

    /**
     * Inspect what's present for a problem under <problemGitRepo>/<problemName>/. One shared check
     * used by both `validatecourse` (CLI) and the lab-health endpoint. `convertProblemToHtml` copies
     * the full package here (problem.yaml + data/ + submissions/) AND generates the statement
     * (index.html + problem.css), so a healthy problem has all of these.
     *
     * `acceptedSolution` is a reference solution under submissions/accepted/ whose extension matches
     * `language` (the problem/course language from the DB) — used to smoke-test the judge with a
     * known-good solution. We match on the configured language rather than grading whatever file is
     * there, so a solution in a different language can't be compiled as the wrong one.
     */
    fun problemFilesReady(problemGitRepo: String, problemName: String, language: String = ""): ProblemFiles {
        val dir = java.io.File(problemGitRepo, problemName)
        val acceptedFiles = java.io.File(dir, "submissions/accepted")
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile }
            ?.toList()
            .orEmpty()
        val extensions = extensionsForLanguage(language)
        return ProblemFiles(
            html = java.io.File(dir, "index.html").isFile,
            css = java.io.File(dir, "problem.css").isFile,
            problemYaml = java.io.File(dir, "problem.yaml").isFile,
            data = java.io.File(dir, "data").isDirectory,
            acceptedSolution = acceptedFiles.firstOrNull { it.extension.lowercase() in extensions },
            hasAnyAcceptedSolution = acceptedFiles.isNotEmpty(),
        )
    }

    /** Source-file extensions for a judge language — used to find a same-language accepted solution. */
    private fun extensionsForLanguage(language: String): Set<String> = when (language.lowercase()) {
        "java" -> setOf("java")
        "python", "py" -> setOf("py")
        "c" -> setOf("c")
        "c++", "cpp" -> setOf("cpp", "cc", "cxx")
        else -> emptySet()
    }
}
