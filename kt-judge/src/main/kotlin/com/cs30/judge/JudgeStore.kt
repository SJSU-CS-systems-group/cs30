package com.cs30.judge

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Admission + concurrency control, mirroring judge/store.py:
//   - a fixed pool of maxWorkers runs the docker jobs (each blocks on I/O),
//   - a Semaphore(maxQueueSize) is the in-flight gate -> QueueFull (429),
//   - the caller blocks on the future up to wall+margin -> SyncTimeout (504).
@Service
class JudgeStore(
    private val props: JudgeProperties,
    private val runner: JudgeRunner,
) {
    private val pool = Executors.newFixedThreadPool(props.concurrency.maxWorkers)
    private val admission = Semaphore(props.concurrency.maxQueueSize)

    private companion object {
        const val SYNC_MARGIN_SECONDS = 10
        val JAVA_PUBLIC_CLASS = Regex("""\bpublic\s+(?:final\s+|abstract\s+)?class\s+([A-Za-z_]\w*)""")
    }

    fun submitSync(req: SubmitRequest): SubmitResult {
        validate(req.problemId, req.language, req.poolPath)
        val problemDir = resolveProblemDir(req.problemId, req.poolPath)
        val wall = req.wallTimeout ?: props.timeouts.runAllWallSeconds
        return runAndWait(wall) {
            val code = stage(req.language, req.source)
            try {
                runner.runSubmit(problemDir, code, wall)
            } finally {
                cleanup(code)
            }
        }
    }

    fun runSync(req: RunRequest): RunResult {
        validate(req.problemId, req.language, req.poolPath)
        val customs = customStdins(req)
        val maxN = props.limits.maxCustomCases
        if (customs.size > maxN) throw JudgeError("too many custom cases (${customs.size} > $maxN)")
        val problemDir = resolveProblemDir(req.problemId, req.poolPath)
        val wall = req.wallTimeout ?: props.timeouts.runAllWallSeconds
        return runAndWait(wall) {
            val code = stage(req.language, req.source)
            try {
                runner.runSamples(problemDir, code, customs, wall)
            } finally {
                cleanup(code)
            }
        }
    }

    // In-flight count at this exact instant, including any job currently being admitted (its permit
    // is acquired before this is read). Shared by the dynamic-timeout estimate below and queueStatus().
    private fun inFlightCount(): Int = props.concurrency.maxQueueSize - admission.availablePermits()

    // Conservative: assumes every job ahead of this one takes the full `wall` seconds. Overestimating
    // is the safe direction — better to wait a bit longer than necessary than to time out a job that
    // was only stuck behind others in the pool's internal queue, not actually struggling to run.
    private fun estimatedWaitSeconds(wall: Int): Int {
        val aheadRounds = kotlin.math.ceil(inFlightCount().toDouble() / props.concurrency.maxWorkers)
        return (aheadRounds * wall).toInt()
    }

    private fun <T> runAndWait(wall: Int, work: () -> T): T {
        if (!admission.tryAcquire()) {
            throw QueueFull("judge at capacity (${props.concurrency.maxQueueSize} in flight)")
        }
        // Queue-depth-aware budget: a fixed wall+margin timer starts the moment this job is handed to
        // the pool, not when a worker thread actually picks it up — so a job stuck behind many others
        // in maxWorkers' internal queue could time out having never truly started. Sizing the wait to
        // the queue depth at admission time gives it a fair budget instead of a one-size-fits-all one.
        val dynamicTimeoutSeconds = wall + estimatedWaitSeconds(wall) + SYNC_MARGIN_SECONDS
        // The permit is released by the TASK's finally, not the caller's — so a
        // job the caller gave up on (504) still holds its slot until it finishes.
        val future: Future<T> = pool.submit(Callable { try { work() } finally { admission.release() } })
        return try {
            future.get(dynamicTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            throw SyncTimeout("judge did not return in time (overloaded); retry later")
        } catch (e: ExecutionException) {
            throw e.cause ?: e   // surface the real failure (-> 400 / 500)
        }
    }

    fun queueStatus(): QueueStatusResponse = QueueStatusResponse(
        inFlight = inFlightCount(),
        maxQueueSize = props.concurrency.maxQueueSize,
        maxWorkers = props.concurrency.maxWorkers,
    )

    private fun stage(language: String, source: String): Path {
        val ext = extFor(language)
        val dir = Files.createTempDirectory("judge-sub-")
        val code = dir.resolve(submissionFilename(language, ext, source))
        Files.writeString(code, source)
        code.toFile().setReadable(true, false)   // readable by the container's (different) uid
        return code
    }

    private fun cleanup(code: Path) {
        runCatching {
            Files.deleteIfExists(code)
            Files.deleteIfExists(code.parent)
        }
    }

    private fun extFor(language: String): String =
        props.languages[language.lowercase()] ?: throw JudgeError("unsupported language: '$language'")

    private fun submissionFilename(language: String, ext: String, source: String): String {
        if (language.lowercase() == "java") {
            // Java requires the file name to match the public class name.
            val m = JAVA_PUBLIC_CLASS.find(source)
            return if (m != null) "${m.groupValues[1]}.java" else "Main.java"
        }
        return "submission$ext"
    }

    private fun isPlainName(s: String): Boolean =
        s.isNotEmpty() && !s.contains('/') && !s.contains('\\') && !s.startsWith('.')

    private fun resolveProblemDir(problemId: String, poolPath: String): Path {
        if (!isPlainName(problemId)) throw JudgeError("invalid problem_id: '$problemId'")
        if (poolPath.isBlank()) throw JudgeError("missing pool_path")
        val base = Paths.get(poolPath).toAbsolutePath().normalize()
        if (!Files.isDirectory(base)) throw JudgeError("unknown pool path: '$poolPath'")
        val path = base.resolve(problemId).normalize()
        if (path.parent != base || !Files.isRegularFile(path.resolve("problem.yaml"))) {
            throw JudgeError("unknown problem_id: '$problemId' in '$base'")
        }
        return path
    }

    private fun validate(problemId: String, language: String, poolPath: String) {
        resolveProblemDir(problemId, poolPath)
        extFor(language)
    }

    // Prefer the list; fall back to the deprecated single `stdin` for back-compat.
    private fun customStdins(req: RunRequest): List<String> = when {
        req.customStdins.isNotEmpty() -> req.customStdins
        req.stdin != null -> listOf(req.stdin)
        else -> emptyList()
    }

    @PreDestroy
    fun shutdown() {
        pool.shutdownNow()
    }
}
