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
        val commitCommand = "cd $repoPath && git add -A && git commit -m 'Update $remoteFileName' || true"
        val commitProcess = ProcessBuilder("ssh", "$sshUser@$sshHost", commitCommand)
            .inheritIO()
            .start()
        commitProcess.waitFor()
    }

    /**
     * Adds a single problem to an existing problem repository.
     * Converts the problem to HTML and saves it to: section/lab/problemTitle
     *
     * @param problemGitRepo The path to the problem git repo on the server
     * @param section The section number
     * @param labNumber The lab number
     * @param problemPath Path to the problem directory to convert
     */
    fun addProblemToRepo(
        problemGitRepo: String,
        section: Int,
        labNumber: Int,
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

            // Copy HTML output to remote repo with path: section_X/lab_X/problemTitle
            val remotePath = "$problemGitRepo/section_$section/lab_$labNumber/$problemName"
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

            // Commit the changes
            println("Committing changes...")
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'Add problem: section_$section/lab_$labNumber/$problemName' || true"
            val commitProcess = ProcessBuilder("ssh", "$sshUser@$sshHost", commitCommand)
                .inheritIO()
                .start()
            commitProcess.waitFor()

            println("✓ Problem added successfully: section_$section/lab_$labNumber/$problemName")
        } finally {
            // Clean up temp directory
            tempDir.deleteRecursively()
        }
    }

    /**
     * Adds all labs from a directory structure to the problem repository.
     * Expects input directory structure: Section_X/Lab_X/problem_name/
     * Converts each problem to HTML and mirrors the structure in the repo.
     *
     * @param problemGitRepo The path to the problem git repo on the server
     * @param labsDir Root directory containing Section_X folders
     */
    fun addLabsToRepo(
        problemGitRepo: String,
        labsDir: String
    ) {
        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        val rootDir = java.io.File(labsDir)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            throw RuntimeException("Labs directory does not exist or is not a directory: $labsDir")
        }

        // Find all Section_X directories
        val sectionDirs = rootDir.listFiles { file ->
            file.isDirectory && file.name.matches(Regex("Section_\\d+", RegexOption.IGNORE_CASE))
        }?.sortedBy { it.name } ?: emptyList()

        if (sectionDirs.isEmpty()) {
            throw RuntimeException("No Section_X directories found in: $labsDir")
        }

        // Collect all problems to process: (sectionDir, labDir, problemDir)
        data class ProblemInfo(val sectionName: String, val labName: String, val problemDir: java.io.File)
        val allProblems = mutableListOf<ProblemInfo>()

        for (sectionDir in sectionDirs) {
            val labDirs = sectionDir.listFiles { file ->
                file.isDirectory && file.name.matches(Regex("Lab_\\d+", RegexOption.IGNORE_CASE))
            }?.sortedBy { it.name } ?: continue

            for (labDir in labDirs) {
                val problemDirs = labDir.listFiles { file -> file.isDirectory } ?: continue
                for (problemDir in problemDirs) {
                    allProblems.add(ProblemInfo(sectionDir.name, labDir.name, problemDir))
                }
            }
        }

        if (allProblems.isEmpty()) {
            throw RuntimeException("No problems found in the directory structure")
        }

        println("Found ${allProblems.size} problem(s) to process:")
        allProblems.forEach { println("  - ${it.sectionName}/${it.labName}/${it.problemDir.name}") }
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
            for (problem in allProblems) {
                val problemName = problem.problemDir.name
                val relativePath = "${problem.sectionName}/${problem.labName}/$problemName"
                println("Processing: $relativePath")

                // Run docker to convert problem to HTML
                val dockerProcess = ProcessBuilder(
                    dockerPath, "run", "--rm",
                    "-v", "${problem.problemDir.parentFile.absolutePath}:/problems:ro",
                    "-v", "${tempDir.absolutePath}:/output",
                    "--entrypoint", "problem2html",
                    "problemtools/full:latest",
                    "-d", "/output/$problemName",
                    "/problems/$problemName"
                )
                    .inheritIO()
                    .start()

                if (dockerProcess.waitFor() != 0) {
                    throw RuntimeException("Failed to convert problem: $relativePath")
                }
                println("✓ Converted: $relativePath")

                // Copy HTML output to remote repo preserving structure
                val remotePath = "$problemGitRepo/$relativePath"

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
                    throw RuntimeException("Failed to copy HTML files to remote server for: $relativePath")
                }

                addedProblems.add(relativePath)
                println("✓ Copied: $relativePath")

                // Clean up this problem's temp output for next iteration
                java.io.File("${tempDir.absolutePath}/$problemName").deleteRecursively()
            }

            // Single commit for all problems
            println("Committing all changes...")
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'Add ${addedProblems.size} problems across sections and labs' || true"
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
        if (sshHost.isBlank()) throw RuntimeException("git.server.ssh-host is not configured")
        val studentDir = "$repoPath/s$section/labs/$labId/assignments/$assignmentId/students/student-$studentId"
        val filePath = "$studentDir/autosaved-solution.$extension"
        val remoteCommand = """
            mkdir -p "$studentDir" &&
            cat > "$filePath" << 'AUTOSAVEEOF' $code AUTOSAVEEOF
            cd "$repoPath" &&
            git -c user.email='server@cs30.edu' -c user.name='CS30 Server' add -A &&
            git commit --author="$authorEmail <$authorEmail>" -m "autosave: $assignmentId" || true
        """.trimIndent()
        runSsh(remoteCommand)
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
        if (sshHost.isBlank()) throw RuntimeException("git.server.ssh-host is not configured")
        val studentDir = "$repoPath/s$section/labs/$labId/assignments/$assignmentId/students/student-$studentId"
        val csvFile = "$studentDir/activity-$sessionId.csv"
        val header = "session_id,timestamp_ms,timestamp_iso,platform,event_kind,detail"
        val remoteCommand = """
            mkdir -p "$studentDir" &&
            if [ ! -f "$csvFile" ]; then printf '%s\n' "$header" > "$csvFile"; fi &&
            cat >> "$csvFile" << 'CSVEOF' $csvRow CSVEOF
        """.trimIndent()
        runSsh(remoteCommand)
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
        if (sshHost.isBlank()) throw RuntimeException("git.server.ssh-host is not configured")
        val remoteCommand = """
            cd "$repoPath" &&
            git -c user.email='server@cs30.edu' -c user.name='CS30 Server' add -A &&
            git commit --author="$authorEmail <$authorEmail>" -m "activity: $sessionId $assignmentId" || true
        """.trimIndent()
        runSsh(remoteCommand)
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