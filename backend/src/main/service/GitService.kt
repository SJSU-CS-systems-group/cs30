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
    @Value("\${docker.path:/usr/local/bin/docker}")
    private val dockerPath: String
) {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")

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

        // Commit the changes
        val commitCommand = "cd $repoPath && git add -A && git commit -m 'update: $destFileName' || true"
        runLocal(commitCommand)
    }

    /**
     * Adds a single problem to the global problem repository.
     * Converts the problem to HTML using problemtools and saves it to: problemGitRepo/problemName/
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

            // Copy HTML output to repo (flat structure: problemGitRepo/problemName/)
            val destPath = java.io.File(problemGitRepo, problemName)
            destPath.mkdirs()
            println("Copying to: $destPath")

            // Copy the HTML files
            val htmlSource = java.io.File(tempDir, problemName)
            htmlSource.copyRecursively(destPath, overwrite = true)

            // Copy the data folder from original problem if it exists
            val dataDir = java.io.File(problemDir, "data")
            if (dataDir.exists() && dataDir.isDirectory) {
                println("Copying data folder...")
                val destDataDir = java.io.File(destPath, "data")
                dataDir.copyRecursively(destDataDir, overwrite = true)
                println("✓ Data folder copied")
            }

            // Commit the changes
            println("Committing changes...")
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'add problem: $problemName' || true"
            runLocal(commitCommand)

            println("✓ Problem added successfully: $problemName")
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
        println("Removing problem: $problemPath")

        if (problemPath.exists()) {
            problemPath.deleteRecursively()
        }

        // Commit the changes
        println("Committing changes...")
        val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'remove problem: $problemName' || true"
        runLocal(commitCommand)

        println("✓ Problem removed successfully: $problemName")
    }

    /**
     * Adds all problems from a root directory to the global problem repository.
     * Expects input directory structure: root_dir/problem_name/
     * Converts each problem to HTML using problemtools and copies the data folder.
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

                // Copy HTML output to repo
                val destPath = java.io.File(problemGitRepo, problemName)
                if (destPath.exists()) {
                    destPath.deleteRecursively()
                }
                destPath.mkdirs()

                val htmlSource = java.io.File(tempDir, problemName)
                htmlSource.copyRecursively(destPath, overwrite = true)

                // Copy the data folder from original problem if it exists
                val dataDir = java.io.File(problemDir, "data")
                if (dataDir.exists() && dataDir.isDirectory) {
                    println("  Copying data folder...")
                    val destDataDir = java.io.File(destPath, "data")
                    dataDir.copyRecursively(destDataDir, overwrite = true)
                    println("  ✓ Data folder copied")
                }

                addedProblems.add(problemName)
                println("✓ Copied: $problemName")

                // Clean up this problem's temp output
                java.io.File(tempDir, problemName).deleteRecursively()
            }

            // Single commit for all problems
            println("Committing all changes...")
            val commitCommand = "cd $problemGitRepo && git add -A && git commit -m 'add ${addedProblems.size} problems' || true"
            runLocal(commitCommand)

            println("✓ ${addedProblems.size} problem(s) added successfully")
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

        // Git add and commit
        val command = "cd $repoPath && git add -A && git commit -m '$commitMessage' || true"
        runLocal(command)

        return relativeFilePath
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
        labId: String,
        assignmentId: String,
        studentId: String,
        code: String,
        extension: String,
        authorEmail: String,
    ) {
        val studentDir = java.io.File(repoPath, "s$section/labs/$labId/assignments/$assignmentId/students/student-$studentId")
        studentDir.mkdirs()

        val filePath = java.io.File(studentDir, "autosaved-solution.$extension")
        filePath.writeText(code)

        val command = """
            cd "$repoPath" &&
            git -c user.email='server@cs30.edu' -c user.name='CS30 Server' add -A &&
            git commit --author="$authorEmail <$authorEmail>" -m "autosave: $assignmentId" || true
        """.trimIndent()
        runLocal(command)
    }

    /**
     * Appends one CSV row to activity-{sessionId}.csv in the student directory.
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
        val studentDir = java.io.File(repoPath, "s$section/labs/$labId/assignments/$assignmentId/students/student-$studentId")
        studentDir.mkdirs()

        val csvFile = java.io.File(studentDir, "activity-$sessionId.csv")
        val header = "session_id,timestamp_ms,timestamp_iso,platform,event_kind,detail"

        if (!csvFile.exists()) {
            csvFile.writeText("$header\n")
        }
        csvFile.appendText("$csvRow\n")
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
        val command = """
            cd "$repoPath" &&
            git -c user.email='server@cs30.edu' -c user.name='CS30 Server' add -A &&
            git commit --author="$authorEmail <$authorEmail>" -m "activity: $sessionId $assignmentId" || true
        """.trimIndent()
        runLocal(command)
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

    /**
     * Gets the latest submission/autosave for a student.
     */
    fun getLatestSubmission(
        repoPath: String,
        section: Int,
        labNumber: Int,
        problemName: String,
        studentEmail: String,
        extension: String
    ): String? {
        val filePath = java.io.File(repoPath, "section_$section/lab_$labNumber/$problemName/$studentEmail/autosave/latest.$extension")
        return if (filePath.exists()) filePath.readText() else null
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