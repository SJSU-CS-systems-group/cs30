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

        val ALERT_EVENTS = setOf(
            "FocusLoss", "FullscreenExit", "TabHidden", "PasteFromOutside",
            "ContextMenu", "DevToolsAttempt", "ClipboardEscape", "WindowRestored"
        )

        private val FOCUS_LOST_EVENTS = setOf("FocusLoss", "TabHidden")
        private val FOCUS_GAINED_EVENTS = setOf("FocusGained", "TabVisible")
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
        loginSessionRepository.findFirstByStudentEmailAndLoggedOutAtIsNull(studentEmail)?.ipAddress ?: "unknown-ip"
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

            // Run docker to convert problem to HTML (read from source, output to temp).
            // CSS is copied to the output directory by default (no flag needed) — passing
            // -c would set --no-css and suppress it.
            val dockerProcess = ProcessBuilder(
                dockerPath, "run", "--rm",
                "-v", "${problemDir.parentFile.absolutePath}:/problems:ro",
                "-v", "${tempDir.absolutePath}:/output",
                "--entrypoint", "problem2html",
                "problemtools/full:latest",
                "-d", "/output/$problemName",
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

                // Run docker to convert problem to HTML (read from source, output to temp).
                // CSS is copied to the output directory by default (no flag needed) — passing
                // -c would set --no-css and suppress it.
                val dockerProcess = ProcessBuilder(
                    dockerPath, "run", "--rm",
                    "-v", "${rootDir.absolutePath}:/problems:ro",
                    "-v", "${tempDir.absolutePath}:/output",
                    "--entrypoint", "problem2html",
                    "problemtools/full:latest",
                    "-d", "/output/$problemName",
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

        // Scoped to the specific files this call actually wrote (not `git add -A`, which stages the
        // whole repo) — under concurrent load, -A sweeps up other students' pending, not-yet-committed
        // writes too, misattributing their changes to this commit's author/IP. bestsubmission.json is
        // included only if it exists: it's created on a student's first submission (see
        // updateMetadataIfBetter's `else -> true` branch) and skipped on later lower-scoring ones where
        // it's untouched but already present — checking existence avoids `git add` erroring on a path
        // that was never created if an earlier metadata write ever failed.
        val metadataPath = "$submissionsDir/bestsubmission.json"
        val filesToAdd = listOfNotNull(
            codePath,
            resultPath,
            metadataPath.takeIf { java.io.File(repoPath, metadataPath).exists() },
        )
        val addArgs = filesToAdd.joinToString(" ") { "\"$it\"" }
        val command = """
            cd "$repoPath" &&
            git add $addArgs &&
            git commit -m 'Submission: section_$section/lab_$labNumber/$problemName/${ipFor(studentEmail)}'
        """.trimIndent()
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

        val relativeFilePath = "section_$section/lab_$labNumber/$problemName/$studentEmail/autosaved-solution.$extension"
        val command = """
            cd "$repoPath" &&
            git add "$relativeFilePath" &&
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
     * Treats an empty commit as a non-error (logs at DEBUG) — git reports this two different ways
     * depending on repo state: "nothing to commit, working tree clean" when the whole repo is clean,
     * or "no changes added to commit" when unrelated files elsewhere have unstaged changes (e.g. a
     * concurrent activity-log write) — both mean the same thing for our purposes: the specific file(s)
     * this call staged had no actual diff (identical autosave content saved twice in a row is the
     * common case), not a real failure. All other non-zero exits are logged at ERROR and thrown.
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
            val isEmptyCommit = output.contains("nothing to commit") || output.contains("no changes added to commit")
            if (exitCode != 0 && !isEmptyCommit) {
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

    /**
     * Count ALERT-level violations for each student from today's activity log CSVs.
     * Returns a map of studentEmail -> violation count.
     *
     * ALERT-level events (actual violations) are: FocusLoss, FullscreenExit, TabHidden,
     * PasteFromOutside, ContextMenu, DevToolsAttempt, ClipboardEscape, WindowRestored
     */
    fun countViolationsForSection(repoPath: String, section: Int): Map<String, Int> {
        val today = java.time.LocalDate.now().toString()
        val todayDir = java.io.File(repoPath, "section_$section/ActivityLogs/$today")

        if (!todayDir.exists() || !todayDir.isDirectory) {
            return emptyMap()
        }

        val violationCounts = mutableMapOf<String, Int>()

        todayDir.listFiles { f -> f.name.endsWith("_activity.csv") }?.forEach { csvFile ->
            try {
                // Extract email from filename: email_date_activity.csv
                val email = csvFile.name.substringBefore("_")

                // Read CSV and count alert events
                csvFile.readLines().drop(1).forEach { line -> // Skip header
                    val parts = parseActivityCsvLine(line)
                    if (parts.size >= 6) {
                        val eventKind = parts[5]
                        if (eventKind in ALERT_EVENTS) {
                            violationCounts[email] = violationCounts.getOrDefault(email, 0) + 1
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to read activity log: ${csvFile.absolutePath}", e)
            }
        }

        return violationCounts
    }

    /**
     * Determines each student's current focus state from today's activity log CSVs: the most
     * recent FocusLoss/TabHidden vs FocusGained/TabVisible event (by timestamp) wins. Students with
     * no such event today are absent from the map — the caller decides the default for that case
     * (e.g. "just logged in, no violations yet" vs "not logged in at all").
     * Returns a map of studentEmail -> hasFocus (true = focus on, false = focus lost).
     */
    fun getFocusStatusForSection(repoPath: String, section: Int): Map<String, Boolean> {
        val today = java.time.LocalDate.now().toString()
        val todayDir = java.io.File(repoPath, "section_$section/ActivityLogs/$today")

        if (!todayDir.exists() || !todayDir.isDirectory) {
            return emptyMap()
        }

        val focusStatus = mutableMapOf<String, Boolean>()

        todayDir.listFiles { f -> f.name.endsWith("_activity.csv") }?.forEach { csvFile ->
            try {
                val email = csvFile.name.substringBefore("_")

                var latestTimestampMs = -1L
                var latestHasFocus: Boolean? = null
                csvFile.readLines().drop(1).forEach { line -> // Skip header
                    val parts = parseActivityCsvLine(line)
                    if (parts.size >= 6) {
                        val eventKind = parts[5]
                        val hasFocus = when (eventKind) {
                            in FOCUS_LOST_EVENTS -> false
                            in FOCUS_GAINED_EVENTS -> true
                            else -> null
                        }
                        if (hasFocus != null) {
                            val timestampMs = parts[1].toLongOrNull() ?: 0
                            if (timestampMs >= latestTimestampMs) {
                                latestTimestampMs = timestampMs
                                latestHasFocus = hasFocus
                            }
                        }
                    }
                }

                latestHasFocus?.let { focusStatus[email] = it }
            } catch (e: Exception) {
                log.warn("Failed to read activity log for focus status: ${csvFile.absolutePath}", e)
            }
        }

        return focusStatus
    }

    /**
     * Get detailed activity log entries for a student for today.
     * @param sinceMs only entries strictly newer than this are returned — lets callers that already
     * hold everything up to their last fetch pull just the delta instead of the whole day's CSV.
     * Returns list of activity entries with all details for display.
     */
    fun getActivityLogForStudent(repoPath: String, section: Int, studentEmail: String, sinceMs: Long = 0): List<ActivityLogEntry> {
        val today = java.time.LocalDate.now().toString()
        val csvFile = java.io.File(repoPath, "section_$section/ActivityLogs/$today/${studentEmail}_${today}_activity.csv")

        if (!csvFile.exists()) {
            return emptyList()
        }

        return try {
            csvFile.readLines().drop(1).mapNotNull { line -> // Skip header
                val parts = parseActivityCsvLine(line)
                if (parts.size >= 6) {
                    ActivityLogEntry(
                        token = parts[0],
                        timestampMs = parts[1].toLongOrNull() ?: 0,
                        timestampIso = parts[2],
                        platform = parts[3],
                        problem = parts[4],
                        eventKind = parts[5],
                        detail = parts.getOrNull(6),
                        severity = if (parts[5] in ALERT_EVENTS) "ALERT" else "INFO"
                    )
                } else null
            }.filter { it.timestampMs > sinceMs }.sortedByDescending { it.timestampMs }
        } catch (e: Exception) {
            log.warn("Failed to read activity log for $studentEmail: ${csvFile.absolutePath}", e)
            emptyList()
        }
    }

    /**
     * Parse a CSV line handling quoted fields with commas.
     */
    private fun parseActivityCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString().trim())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString().trim())
        return result
    }
}

data class ActivityLogEntry(
    val token: String,
    val timestampMs: Long,
    val timestampIso: String,
    val platform: String,
    val problem: String,
    val eventKind: String,
    val detail: String?,
    val severity: String
)
