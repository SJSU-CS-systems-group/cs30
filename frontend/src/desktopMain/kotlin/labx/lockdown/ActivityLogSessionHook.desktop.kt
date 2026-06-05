package labx.lockdown

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopActivityLogSessionHook(
    private val assignmentBase: String,
    private val studentEmail: String,
) : ActivityLogSessionHook {

    private fun studentDir(problemSlug: String) =
        "$assignmentBase/$problemSlug/students/$studentEmail"

    override fun onSessionStart(sessionId: String, problemSlug: String): ActivityLogSink {
        val timestamp = Instant.now().toString().replace(":", "-")
        val fileName = "activity-$timestamp.csv"
        return PlatformActivityLogSink(
            "desktop",
            CompositeActivityLogSink(
                ConsoleActivityLogSink(),
                CsvActivityLogSink(studentDir(problemSlug), fileName)
            )
        )
    }

    override suspend fun onSessionEnd(sessionId: String, problemSlug: String) {
        withContext(Dispatchers.IO) {
            commitToGit(studentDir(problemSlug), studentEmail, "activity: $sessionId $problemSlug")
        }
    }
}
