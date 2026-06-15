package com.cs30.server.service

import com.cs30.server.dto.SaveType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
open class GitService(
    @Value("\${git.repos.base-path:/var/git/courses}")
    private val basePath: String,
    @Value("\${git.server.ssh-host:}")
    private val sshHost: String,
    @Value("\${git.server.ssh-user:git}")
    private val sshUser: String,
    @Value("\${docker.path:/usr/local/bin/docker}")
    private val dockerPath: String
) {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

    /**
     * Initializes a Git repository at the given path on the remote server.
     * Creates the repo if it doesn't exist, skips if it already exists.
     */
    fun initGitRepo(repoPath: String) {
        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        // Check if repo already exists
        if (repositoryExists(repoPath)) {
            return
        }

        // SSH command to create directory and initialize repo
        val remoteCommand = "mkdir -p $repoPath && cd $repoPath && git init && git config user.email 'server@cs30.edu' && git config user.name 'CS30 Server'"

        val process = ProcessBuilder(
            "ssh",
            "$sshUser@$sshHost",
            remoteCommand
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Failed to initialize git repository on $sshHost: $output")
        }
    }

    /**
     * Saves a local file to a remote git repository and commits it.
     * @param repoPath Path to the git repo on the remote server
     * @param localFilePath Path to the local file to copy
     * @param remoteFileName Name for the file in the repo (e.g., "course.yml")
     */
    fun saveFileToRepo(repoPath: String, localFilePath: String, remoteFileName: String) {
        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        val localFile = java.io.File(localFilePath)
        if (!localFile.exists()) {
            throw RuntimeException("Local file not found: $localFilePath")
        }

        // Use rsync to copy the file
        val rsyncProcess = ProcessBuilder(
            "rsync", "-avz",
            localFile.absolutePath,
            "$sshUser@$sshHost:$repoPath/$remoteFileName"
        )
            .inheritIO()
            .start()

        if (rsyncProcess.waitFor() != 0) {
            throw RuntimeException("Failed to copy file to remote server")
        }

        // Commit the changes
        val commitCommand = "cd $repoPath && git add -A && git commit -m 'update: $remoteFileName' || true"
        val commitProcess = ProcessBuilder("ssh", "$sshUser@$sshHost", commitCommand)
            .inheritIO()
            .start()
        commitProcess.waitFor()
    }

    /**
     * Adds a single problem to the global problem repository.
     * Converts the problem to HTML using problemtools and saves it to: problemGitRepo/problemName/
     *
     * @param problemGitRepo The path to the problem git repo on the server
     * @param problemPath Path to the problem directory to convert
     */
    fun addProblemToRepo(
        problemGitRepo: String,
        problemPath: String
    ) {
        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        val problemDir = java.io.File(problemPath)
        if (!problemDir.exists() || !problemDir.isDirectory) {
            throw RuntimeException("Problem path does not exist or is not a directory: $problemPath")
        }

        val problemName = problemDir.name

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
                println("Pulling problemtools/full:latest image...")
                val pullProcess = ProcessBuilder(dockerPath, "pull", "problemtools/full:latest")
                    .inheritIO()
                    .start()
                if (pullProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to pull problemtools/full:latest image")
                }
                println("Image pulled successfully.")
            }

            println("Processing problem: $problemName")

            // Run docker to convert problem to HTML
            val dockerProcess = ProcessBuilder(
                dockerPath, "run", "--rm",
                "-v", "${problemDir.parentFile.absolutePath}:/problems:ro",
                "-v", "${tempDir.absolutePath}:/output",
                "--entrypoint", "problem2html",
                "problemtools/full:latest",
                "-d", "/output/$problemName",
                "/problems/$problemName"
            )
                .inheritIO()
                .start()

            if (dockerProcess.waitFor() != 0) {
                throw RuntimeException("Failed to convert problem: $problemName")
            }
            println("✓ Converted: $problemName")

            // Copy HTML output to remote repo (flat structure: problemGitRepo/problemName/)
            val remotePath = "$problemGitRepo/$problemName"
            println("Copying to remote: $remotePath")

            // Create the directory structure on remote
            val mkdirProcess = ProcessBuilder(
                "ssh", "$sshUser@$sshHost",
                "mkdir -p $remotePath"
            )
                .inheritIO()
                .start()
            mkdirProcess.waitFor()

            // Rsync the HTML files
            val rsyncProcess = ProcessBuilder(
                "rsync", "-avz",
                "${tempDir.absolutePath}/$problemName/",
                "$sshUser@$sshHost:$remotePath/"
            )
                .inheritIO()
                .start()

            if (rsyncProcess.waitFor() != 0) {
                throw RuntimeException("Failed to copy HTML files to remote server")
            }

            // Copy the data folder from original problem if it exists
            val dataDir = java.io.File(problemDir, "data")
            if (dataDir.exists() && dataDir.isDirectory) {
                println("Copying data folder...")
                val dataRsyncProcess = ProcessBuilder(
                    "rsync", "-avz",
                    "${dataDir.absolutePath}/",
                    "$sshUser@$sshHost:$remotePath/data/"
                )
                    .inheritIO()
                    .start()

                if (dataRsyncProcess.waitFor() != 0) {
                    println("Warning: Failed to copy data folder for: $problemName")
                } else {
                    println("✓ Data folder copied")
                }
            }

            // Commit the changes
            println("Committing changes...")
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'add problem: $problemName' || true"
            val commitProcess = ProcessBuilder("ssh", "$sshUser@$sshHost", commitCommand)
                .inheritIO()
                .start()
            commitProcess.waitFor()

            println("✓ Problem added successfully: $problemName")
        } finally {
            // Clean up temp directory
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
        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        val remotePath = "$problemGitRepo/$problemName"
        println("Removing problem from remote: $remotePath")

        // Remove the directory on remote
        val rmProcess = ProcessBuilder(
            "ssh", "$sshUser@$sshHost",
            "rm -rf $remotePath"
        )
            .inheritIO()
            .start()

        if (rmProcess.waitFor() != 0) {
            throw RuntimeException("Failed to remove problem directory from remote server")
        }

        // Commit the changes
        println("Committing changes...")
        val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'remove problem: $problemName' || true"
        val commitProcess = ProcessBuilder("ssh", "$sshUser@$sshHost", commitCommand)
            .inheritIO()
            .start()
        commitProcess.waitFor()

        println("✓ Problem removed successfully: $problemName")
    }

    /**
     * Adds all problems from a root directory to the global problem repository.
     * Expects input directory structure: root_dir/problem_name/
     * Converts each problem to HTML using problemtools and copies the data folder.
     *
     * @param problemGitRepo The path to the problem git repo on the server
     * @param problemsDir Root directory containing problem folders
     */
    fun addProblemsToRepo(
        problemGitRepo: String,
        problemsDir: String
    ) {
        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        val rootDir = java.io.File(problemsDir)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            throw RuntimeException("Problems directory does not exist or is not a directory: $problemsDir")
        }

        // Find all problem directories (direct subdirectories of root)
        val problemDirs = rootDir.listFiles { file -> file.isDirectory }
            ?.sortedBy { it.name } ?: emptyList()

        if (problemDirs.isEmpty()) {
            throw RuntimeException("No problem directories found in: $problemsDir")
        }

        println("Found ${problemDirs.size} problem(s) to process:")
        problemDirs.forEach { println("  - ${it.name}") }
        println()

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
                println("Pulling problemtools/full:latest image...")
                val pullProcess = ProcessBuilder(dockerPath, "pull", "problemtools/full:latest")
                    .inheritIO()
                    .start()
                if (pullProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to pull problemtools/full:latest image")
                }
                println("Image pulled successfully.")
            }

            val addedProblems = mutableListOf<String>()

            // Process each problem
            for (problemDir in problemDirs) {
                val problemName = problemDir.name
                println("Processing: $problemName")

                // Run docker to convert problem to HTML
                val dockerProcess = ProcessBuilder(
                    dockerPath, "run", "--rm",
                    "-v", "${problemDir.parentFile.absolutePath}:/problems:ro",
                    "-v", "${tempDir.absolutePath}:/output",
                    "--entrypoint", "problem2html",
                    "problemtools/full:latest",
                    "-d", "/output/$problemName",
                    "/problems/$problemName"
                )
                    .inheritIO()
                    .start()

                if (dockerProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to convert problem: $problemName")
                }
                println("✓ Converted: $problemName")

                // Copy HTML output to remote repo (flat structure: problemGitRepo/problemName/)
                val remotePath = "$problemGitRepo/$problemName"

                // Create the directory structure on remote
                val mkdirProcess = ProcessBuilder(
                    "ssh", "$sshUser@$sshHost",
                    "mkdir -p $remotePath"
                )
                    .inheritIO()
                    .start()
                mkdirProcess.waitFor()

                // Rsync the HTML files (--delete removes old files not in source)
                val rsyncProcess = ProcessBuilder(
                    "rsync", "-avz", "--delete",
                    "${tempDir.absolutePath}/$problemName/",
                    "$sshUser@$sshHost:$remotePath/"
                )
                    .inheritIO()
                    .start()

                if (rsyncProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to copy HTML files to remote server for: $problemName")
                }

                // Copy the data folder from original problem if it exists
                val dataDir = java.io.File(problemDir, "data")
                if (dataDir.exists() && dataDir.isDirectory) {
                    println("  Copying data folder...")
                    val dataRsyncProcess = ProcessBuilder(
                        "rsync", "-avz",
                        "${dataDir.absolutePath}/",
                        "$sshUser@$sshHost:$remotePath/data/"
                    )
                        .inheritIO()
                        .start()

                    if (dataRsyncProcess.waitFor() != 0) {
                        println("  Warning: Failed to copy data folder for: $problemName")
                    } else {
                        println("  ✓ Data folder copied")
                    }
                }

                addedProblems.add(problemName)
                println("✓ Copied: $problemName")

                // Clean up this problem's temp output for next iteration
                java.io.File("${tempDir.absolutePath}/$problemName").deleteRecursively()
            }

            // Single commit for all problems
            println("Committing all changes...")
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'add ${addedProblems.size} problems' || true"
            val commitProcess = ProcessBuilder("ssh", "$sshUser@$sshHost", commitCommand)
                .inheritIO()
                .start()
            commitProcess.waitFor()

            println("✓ ${addedProblems.size} problem(s) added successfully")
        } finally {
            // Clean up temp directory
            tempDir.deleteRecursively()
        }
    }

    /**
     * Saves a file to the repository and commits it.
     * Creates the full folder structure on first save:
     * <repo>/section_X/lab_X/problem_name/student_email/
     *   ├── autosave/
     *   └── submissions/
     *
     * @return The relative path to the saved file
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
        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        val timestamp = LocalDateTime.now().format(timestampFormatter)
        val studentDir = "section_$section/lab_$labNumber/$problemName/$studentEmail"
        val autosaveDir = "$studentDir/autosave"
        val submissionsDir = "$studentDir/submissions"

        // Determine the file path based on save type
        val (relativeFilePath, commitMessage) = when (saveType) {
            SaveType.AUTOSAVE -> {
                Pair("$autosaveDir/autosave-$timestamp.$extension", "Autosave: section_$section/lab_$labNumber/$problemName/$studentEmail")
            }
            SaveType.SUBMISSION -> {
                Pair("$submissionsDir/submission-$timestamp.$extension", "Submission: section_$section/lab_$labNumber/$problemName/$studentEmail")
            }
        }

        val fullFilePath = "$repoPath/$relativeFilePath"
        val latestPath = "$repoPath/$autosaveDir/latest.$extension"

        // SSH command to:
        // 1. Create the folder structure (autosave and submissions folders)
        // 2. Write the file
        // 3. For autosave, also update latest.<ext>
        // 4. Git add and commit
        val writeLatest = if (saveType == SaveType.AUTOSAVE) {
            "cat '$fullFilePath' > '$latestPath' &&"
        } else {
            ""
        }

        val remoteCommand = """
            mkdir -p "$repoPath/$autosaveDir" "$repoPath/$submissionsDir" &&
            cat > "$fullFilePath" << 'CODEEOF' $code CODEEOF
            $writeLatest
            cd "$repoPath" &&
            git add -A &&
            git commit -m "$commitMessage" || true
        """.trimIndent()

        val process = ProcessBuilder(
            "ssh",
            "$sshUser@$sshHost",
            remoteCommand
        )
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw RuntimeException("Failed to save file on $sshHost: $output")
        }

        return relativeFilePath
    }

    /**
     * Deletes a Git repository on the remote server.
     */
    fun deleteRepository(repoPath: String): Boolean {
        if (sshHost.isBlank()) {
            return false
        }

        val remotePath = if (repoPath.contains(":")) {
            repoPath.substringAfter(":")
        } else {
            repoPath
        }

        val process = ProcessBuilder(
            "ssh",
            "$sshUser@$sshHost",
            "rm -rf $remotePath"
        )
            .redirectErrorStream(true)
            .start()

        return process.waitFor() == 0
    }

    /**
     * Saves autosaved-solution.{extension} to the student directory on the remote server
     * and commits it. File is overwritten each save; git history records progression.
     */
    fun saveAutosolution(
        repoPath: String,
        section: Int,
        labId: String,
        assignmentId: String,
        studentId: String,
        code: String,
        extension: String,
        authorEmail: String,
    ) {
        val studentDir = "$repoPath/s$section/labs/$labId/assignments/$assignmentId/students/student-$studentId"
        val filePath = "$studentDir/autosaved-solution.$extension"
        val encodedCode = java.util.Base64.getEncoder().encodeToString(code.toByteArray(Charsets.UTF_8))
        val remoteCommand = """
            mkdir -p "$studentDir" &&
            printf '%s' '$encodedCode' | base64 -d > "$filePath" &&
            cd "$repoPath" &&
            git -c user.email='server@cs30.edu' -c user.name='CS30 Server' add -A &&
            git commit --author="$authorEmail <$authorEmail>" -m "autosave: $assignmentId" || true
        """.trimIndent()
        runLocal(remoteCommand)
    }

    /**
     * Appends one CSV row to activity-{sessionId}.csv in the student directory.
     * Creates the directory and CSV header on first event of the session.
     */
    fun appendActivityLogRow(
        repoPath: String,
        section: Int,
        labId: String,
        assignmentId: String,
        studentId: String,
        sessionId: String,
        csvRow: String,
    ) {
        val studentDir = "$repoPath/s$section/labs/$labId/assignments/$assignmentId/students/student-$studentId"
        val csvFile = "$studentDir/activity-$sessionId.csv"
        val header = "session_id,timestamp_ms,timestamp_iso,platform,event_kind,detail"
        val escapedRow = csvRow.replace("'", "'\\''")
        val remoteCommand = """
            mkdir -p "$studentDir" &&
            if [ ! -f "$csvFile" ]; then printf '%s\n' '$header' > "$csvFile"; fi &&
            printf '%s\n' '$escapedRow' >> "$csvFile"
        """.trimIndent()
        runLocal(remoteCommand)
    }

    /**
     * Commits the activity log CSV for a completed lockdown session.
     */
    fun commitActivityLog(
        repoPath: String,
        section: Int,
        labId: String,
        assignmentId: String,
        studentId: String,
        sessionId: String,
        authorEmail: String,
    ) {
        val remoteCommand = """
            cd "$repoPath" &&
            git -c user.email='server@cs30.edu' -c user.name='CS30 Server' add -A &&
            git commit --author="$authorEmail <$authorEmail>" -m "activity: $sessionId $assignmentId" || true
        """.trimIndent()
        runLocal(remoteCommand)
    }

    /** Executes a shell command locally. Throws on non-zero exit. */
    private fun runLocal(command: String): String {
        val process = ProcessBuilder("bash", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw RuntimeException("Command failed: $output")
        return output
    }

    /** Executes a shell command on the remote git server via SSH. Throws on non-zero exit. */
    private fun runSsh(command: String): String {
        val process = ProcessBuilder("ssh", "$sshUser@$sshHost", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw RuntimeException("SSH command failed on $sshHost: $output")
        return output
    }

    /**
     * Gets the latest submission/autosave for a student from the remote git repo.
     * Returns the file contents, or null if not found.
     */
    fun getLatestSubmission(
        repoPath: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        studentEmail: String,
        extension: String
    ): String? {
        if (sshHost.isBlank()) return null

        val filePath = "$repoPath/section_$section/lab_$labNumber/$problemName/$studentEmail/autosave/latest.$extension"

        val process = ProcessBuilder("ssh", "$sshUser@$sshHost", "cat '$filePath'")
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return if (exitCode == 0) output else null
    }

    /**
     * Checks if a repository exists on the remote server.
     */
    fun repositoryExists(repoPath: String): Boolean {
        if (sshHost.isBlank()) {
            return false
        }

        val remotePath = if (repoPath.contains(":")) {
            repoPath.substringAfter(":")
        } else {
            repoPath
        }

        val process = ProcessBuilder(
            "ssh",
            "$sshUser@$sshHost",
            "test -d $remotePath"
        )
            .redirectErrorStream(true)
            .start()

        return process.waitFor() == 0
    }
}