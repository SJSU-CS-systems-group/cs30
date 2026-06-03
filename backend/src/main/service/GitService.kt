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
    private val sshUser: String
) {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

    /**
     * Initializes a Git repository for a course on the remote server.
     * One repo is shared across all sections of the same course (code + year + semester).
     * Returns the path to the repository on the remote server.
     */
    fun initRepository(courseCode: String, year: Int, semester: String): String {
        val repoName = "${semester}${year % 100}-${courseCode}".lowercase()
        val repoPath = "$basePath/$repoName"

        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        // Check if repo already exists
        if (repositoryExists(repoPath)) {
            return repoPath
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

        return repoPath
    }

    /**
     * Saves a file to the repository and commits it.
     * Creates the full folder structure on first save:
     * <repo>/s<section>/labs/<lab-id>/assignments/<assignment-id>/students/student-<studentId>/
     *   ├── autosave/
     *   └── submissions/
     *
     * @return The relative path to the saved file
     */
    fun saveAndCommit(
        repoPath: String,
        section: Int,
        labId: String,
        assignmentId: String,
        studentId: String,
        code: String,
        extension: String,
        saveType: SaveType
    ): String {
        if (sshHost.isBlank()) {
            throw RuntimeException("git.server.ssh-host is not configured")
        }

        val timestamp = LocalDateTime.now().format(timestampFormatter)
        val studentDir = "s$section/labs/$labId/assignments/$assignmentId/students/student-$studentId"
        val autosaveDir = "$studentDir/autosave"
        val submissionsDir = "$studentDir/submissions"

        // Determine the file path based on save type
        val (relativeFilePath, commitMessage) = when (saveType) {
            SaveType.AUTOSAVE -> {
                Pair("$autosaveDir/autosave-$timestamp.$extension", "Autosave: student-$studentId - $labId/$assignmentId")
            }
            SaveType.SUBMISSION -> {
                Pair("$submissionsDir/submission-$timestamp.$extension", "Submission: student-$studentId - $labId/$assignmentId")
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