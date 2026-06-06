package lockdown

import java.io.File

/**
 * Stages all new files in [repoRoot] and commits with [message], authored by [studentEmail].
 * All failures are logged and swallowed — a commit failure never surfaces to the student UI.
 */
internal fun commitToGit(repoRoot: String, studentEmail: String, message: String) {
    try {
        val dir = File(repoRoot)
        if (!dir.isDirectory) {
            println("[GitCommit] repoRoot does not exist: $repoRoot")
            return
        }

        // "git add ." stages only files inside this student directory, not the whole repo.
        val addResult = ProcessBuilder("git", "add", ".")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val addOutput = addResult.inputStream.bufferedReader().readText()
        addResult.waitFor()
        if (addResult.exitValue() != 0) {
            println("[GitCommit] git add failed (exit ${addResult.exitValue()}): $addOutput")
            return
        }

        val commitResult = ProcessBuilder(
            "git", "commit",
            "--author", "$studentEmail <$studentEmail>",
            "-m", message
        )
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val commitOutput = commitResult.inputStream.bufferedReader().readText()
        commitResult.waitFor()
        if (commitResult.exitValue() != 0) {
            println("[GitCommit] git commit exited ${commitResult.exitValue()}: $commitOutput")
        } else {
            println("[GitCommit] committed: $message")
        }
    } catch (e: Exception) {
        println("[GitCommit] commit skipped: ${e.message}")
    }
}
