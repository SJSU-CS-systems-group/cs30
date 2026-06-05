package com.cs30.server.service

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
            cat > "$fullFilePath" << 'CODEEOF'
$code
CODEEOF
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