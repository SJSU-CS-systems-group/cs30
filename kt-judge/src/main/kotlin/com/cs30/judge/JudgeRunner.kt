package com.cs30.judge

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// Builds and runs the hardened `docker run` for one job, then parses the result.
// Mirrors judge/runner.py: same flags, same orchestrator protocol. The student
// code runs only inside the ephemeral container, never on the host.
@Component
class JudgeRunner(private val props: JudgeProperties) {

    private val log = org.slf4j.LoggerFactory.getLogger(JudgeRunner::class.java)

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // The gid the container runs as. A group NAME is configured (judge.sandbox.group) and resolved
    // to a GID on THIS host once (getent, which also sees LDAP/SSSD groups). Resolved lazily and
    // cached — the host group doesn't change while we run. If unset/unresolvable, falls back to uid.
    private val effectiveGid: Int by lazy { resolveGid() }

    private fun resolveGid(): Int {
        val group = props.sandbox.group
        if (group.isBlank()) return props.sandbox.uid
        return try {
            val p = ProcessBuilder("getent", "group", group).start()
            val line = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            // getent format: name:x:GID:members
            val gid = line.split(":").getOrNull(2)?.toIntOrNull()
            if (gid != null) {
                log.info("Resolved sandbox group '{}' to gid {}", group, gid)
                gid
            } else {
                log.warn("Could not resolve group '{}' (getent gave '{}'); falling back to uid {} as gid", group, line, props.sandbox.uid)
                props.sandbox.uid
            }
        } catch (e: Exception) {
            log.warn("Failed to resolve group '{}': {}; falling back to uid {} as gid", group, e.message, props.sandbox.uid)
            props.sandbox.uid
        }
    }

    // incontainer.py stays Python (it runs INSIDE the sandbox). Ship it as a
    // resource, extract once, and mount it read-only at /in/orch.py.
    private val orchPath: Path = extractOrchestrator()

    private val drain = Executors.newCachedThreadPool { r ->
        Thread(r, "proc-drain").apply { isDaemon = true }
    }

    private data class OrchCase(
        val name: String,
        @JsonProperty("bt_name") val btName: String,
        val input: String?,
        val expected: String?,
        val stdout: String?,
        val stderr: String?,
    )

    private data class OrchOutput(
        @JsonProperty("verdict_text") val verdictText: String,
        val cases: List<OrchCase> = emptyList(),
    )

    /**
     * A killed container needs no flag of its own: incontainer.py prints its JSON exactly once, as the
     * last statement of main(), so a container stopped at the wall budget emits *nothing*. [stdout] is
     * empty and the blank-output check in parseSubmit/parseSamples catches it.
     */
    private data class Proc(
        val stdout: String,
        val stderr: String,
        val exit: Int,
    )

    private fun extractOrchestrator(): Path {
        val tmp = Files.createTempFile("incontainer", ".py")
        (javaClass.getResourceAsStream("/incontainer.py")
            ?: error("incontainer.py resource missing from the jar")).use {
            Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING)
        }
        tmp.toFile().setReadable(true, false)   // world-readable: the container uid must read it
        tmp.toFile().deleteOnExit()
        return tmp
    }

    private fun dockerFlags(name: String): List<String> {
        val s = props.sandbox
        val gid = effectiveGid
        return listOf(
            "--rm",
            "--name", name,
            "--network=none",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--read-only",
            "--tmpfs", "/work:rw,exec,size=${s.workTmpfsMb}m,nr_inodes=16384,uid=${s.uid},gid=${gid}",
            "--tmpfs", "/tmp:rw,exec,size=${s.tmpTmpfsMb}m,nr_inodes=4096,uid=${s.uid},gid=${gid}",
            "--pids-limit=${s.pidsLimit}",
            "--memory=${s.memoryMb}m", "--memory-swap=${s.memoryMb}m",
            "--cpus=${s.cpus}",
            "--ulimit", "fsize=${s.fsizeBytes}",
            "-u", "${s.uid}:${gid}",
        )
    }

    private fun invoke(
        problemDir: Path,
        mounts: List<Pair<Path, String>>,
        btArgs: List<String>,
        wallTimeout: Int,
        entrypoint: String? = null,
    ): Proc {
        val name = "kt-judge-${UUID.randomUUID()}"
        val cmd = mutableListOf("docker", "run")
        cmd.addAll(dockerFlags(name))
        if (entrypoint != null) cmd.addAll(listOf("--entrypoint", entrypoint))
        cmd.addAll(listOf("-v", "${problemDir.toAbsolutePath().normalize()}:/problem:ro"))
        for ((host, container) in mounts) {
            cmd.addAll(listOf("-v", "${host.toAbsolutePath().normalize()}:$container:ro"))
        }
        cmd.add(props.image)
        cmd.addAll(btArgs)

        val proc = ProcessBuilder(cmd).start()
        // Drain both pipes concurrently so a large output can't deadlock the wait.
        // errors="replace": non-UTF-8 bytes become U+FFFD instead of crashing.
        val out: Future<String> = readAsync(proc.inputStream)
        val err: Future<String> = readAsync(proc.errorStream)

        if (!proc.waitFor(wallTimeout.toLong(), TimeUnit.SECONDS)) {
            // Wall-timeout: kill the container (the --rm client dying alone can
            // leave it running), then the local process.
            runCatching {
                ProcessBuilder("docker", "kill", name).start().waitFor(5, TimeUnit.SECONDS)
            }
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
        }
        val exit = if (proc.isAlive) -1 else proc.exitValue()
        return Proc(out.get(), err.get(), exit)
    }

    private fun readAsync(ins: InputStream): Future<String> =
        drain.submit(Callable { ins.readBytes().toString(Charsets.UTF_8) })

    fun runSubmit(problemDir: Path, codePath: Path, wallTimeout: Int): SubmitResult {
        val sub = codePath.fileName.toString()
        val mounts = listOf(codePath to "/in/$sub", orchPath to "/in/orch.py")
        val proc = invoke(problemDir, mounts, listOf("/in/orch.py", sub, "--mode", "submit"), wallTimeout, "python3")
        return parseSubmit(proc.stdout, proc.stderr, countGradedCases(problemDir))
    }

    /**
     * How many cases a submit SHOULD grade: every `.in` under data/sample and data/secret, recursively
     * (nested test groups included). Submit mode runs `bt run` with no path filter, so this is exactly
     * bt's own case count — verified against bt's reported totals for all 7 problems where a measured
     * bt number exists (artistwhoshallnotbenamed 8, cascade 6, pascalmagic 33, roadtorome 10,
     * tenkindsofpeople 11, arrayshift 9, skylinereconstruction 100), with no other directories present
     * under data/ in any of the 13 problems.
     *
     * Returns 0 when the directory is unreadable or absent, which disables the completeness check rather
     * than rejecting the submission — a counting failure must never fail a student's correct code.
     */
    internal fun countGradedCases(problemDir: Path): Int =
        listOf("sample", "secret").sumOf { group ->
            val dir = problemDir.resolve("data").resolve(group)
            if (!Files.isDirectory(dir)) 0 else runCatching {
                Files.walk(dir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".in") }.count().toInt()
                }
            }.getOrDefault(0)
        }

    fun runSamples(problemDir: Path, codePath: Path, customStdins: List<String>, wallTimeout: Int): RunResult {
        val sub = codePath.fileName.toString()
        val temps = mutableListOf<Path>()
        try {
            val mounts = mutableListOf(codePath to "/in/$sub", orchPath to "/in/orch.py")
            customStdins.forEachIndexed { i, stdin ->
                val p = writeTemp(stdin)
                temps.add(p)
                mounts.add(p to "/in/custom_${i + 1}.in")
            }
            val proc = invoke(problemDir, mounts, listOf("/in/orch.py", sub, "--mode", "run"), wallTimeout, "python3")
            return parseSamples(proc.stdout, proc.stderr)
        } finally {
            temps.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun writeTemp(content: String): Path {
        val p = Files.createTempFile("judge-in", ".in")
        Files.writeString(p, content)
        p.toFile().setReadable(true, false)   // world-readable for the container uid
        return p
    }

    /**
     * Rejects output that cannot be trusted to describe a complete run, before anything interprets it.
     *
     * Must run ABOVE the compile-error branch in both callers: a bt crash invalidates the whole output,
     * including its CE classification. `parseRunOutput` decides "compile error" on the substring
     * `"compil"`, which build chatter satisfies — so classifying first would tell the student their code
     * did not compile, with bt's Python traceback as the compile output.
     *
     * @param expected 0 when the count is unknown, which is always so for /run (it grades a
     *   caller-chosen subset). 0 omits the count from the message rather than guessing.
     */
    private fun requireTrustworthyRun(label: String, verdictText: String, graded: Int, expected: Int) {
        if (!JudgeParser.btCrashed(verdictText)) return
        log.warn("judge.refuse reason=bt-crashed mode={} problem-cases={} bt-parsed={}", label, expected, graded)
        throw incomplete(label, "the judge tool crashed", graded, expected, verdictText)
    }

    /** Single shape for every refusal, so the wording and the bt-output budget stay in one place. */
    private fun incomplete(label: String, reason: String, graded: Int, expected: Int, verdictText: String): JudgeError {
        val progress = if (expected > 0) " after $graded of $expected test cases" else ""
        return JudgeError(
            "$label did not finish: $reason$progress. Refusing to report a verdict from a partial run. " +
                "bt output: ${verdictText.take(BT_OUTPUT_CHARS)}",
        )
    }

    private companion object {
        /** Chars of bt output attached to a refusal — enough to diagnose, short enough to log. */
        const val BT_OUTPUT_CHARS = 500
    }

    internal fun parseSubmit(
        orchStdout: String,
        orchStderr: String,
        expectedCases: Int = 0,
    ): SubmitResult {
        if (orchStdout.isBlank()) {
            throw RuntimeException("submit orchestrator produced no output: ${orchStderr.take(BT_OUTPUT_CHARS)}")
        }
        val data = mapper.readValue<OrchOutput>(orchStdout)
        val verdict = JudgeParser.parseRunOutput(data.verdictText, "", 0)

        // Must stay above the CE branch — see requireTrustworthyRun.
        requireTrustworthyRun("grading", data.verdictText, verdict.testcases.size, expectedCases)

        if (verdict.status == Status.CE && verdict.testcases.isEmpty()) {
            return SubmitResult("CE", 0, 0, 0.0, emptyList(), JudgeParser.cleanCompileOutput(data.verdictText))
        }
        // A well-formed problem always has at least one test case (submit mode runs against ALL
        // of data/sample + data/secret with no path filter) — zero here, with a non-CE status,
        // means bt itself refused/failed to grade (bad problem config, a bt version needing
        // `bt upgrade`, etc.), not a real verdict. Surfacing this as a JudgeError (rather than
        // falling through to worstStatus(emptyList()), which defaults to "AC") makes the actual
        // bt diagnostic text reach the backend's logs instead of silently reporting a false pass.
        if (verdict.testcases.isEmpty()) {
            log.warn("judge.refuse reason=nothing-graded problem-cases={} bt-parsed=0", expectedCases)
            throw JudgeError("no test cases were graded — problem may be misconfigured. bt output: ${data.verdictText.take(BT_OUTPUT_CHARS)}")
        }

        // Catches "graded some", which the emptiness check cannot. A partial run is worse than no run:
        // the cases that completed are the fast early ones, they usually all passed, and
        // worstStatus(all-AC) is "AC" — so the student is shown a pass. Observed under load: submissions
        // returning AC with passed == total after grading 1 of 100 cases, because `total` counts what the
        // judge parsed, not what the problem has. Nothing in the response was inconsistent, so no
        // passed-vs-total check could catch it.
        //
        // Stays BELOW the CE branch: a genuine compile error legitimately grades zero cases.
        //
        // `<` not `!=`: more cases than we counted means countGradedCases is wrong, and rejecting a
        // complete submission is worse than accepting one. expectedCases == 0 disables the check.
        if (expectedCases > 0 && verdict.total < expectedCases) {
            log.warn("judge.refuse reason=incomplete problem-cases={} bt-parsed={}", expectedCases, verdict.total)
            throw JudgeError(
                "grading incomplete: only ${verdict.total} of $expectedCases test cases were graded. " +
                    "Refusing to report a verdict. bt output: ${data.verdictText.take(BT_OUTPUT_CHARS)}",
            )
        }
        val detail = data.cases.associateBy { it.btName }
        val cases = verdict.testcases.map { tc ->
            val d = detail[tc.name]
            val isSample = tc.name.startsWith("sample/")
            var inp: String? = null
            var exp: String? = null
            var out: String? = null
            var er: String? = null
            if (d != null) {
                out = d.stdout
                er = JudgeParser.stripBtNoise(d.stderr ?: "")
                if (isSample) {
                    inp = d.input
                    exp = d.expected
                }
            }
            var status = tc.status.name
            if (status == "RTE" && JudgeParser.isMemoryError(er)) status = "MLE"
            SubmitCase(tc.name, status, tc.timeS, inp, exp, out, er)
        }
        return SubmitResult(
            status = worstStatus(cases.map { it.status }),
            passed = verdict.passed,
            total = verdict.total,
            maxTimeS = verdict.maxTimeS,
            cases = cases,
        )
    }

    internal fun parseSamples(orchStdout: String, orchStderr: String): RunResult {
        if (orchStdout.isBlank()) {
            throw RuntimeException("run orchestrator produced no output: ${orchStderr.take(BT_OUTPUT_CHARS)}")
        }
        val data = mapper.readValue<OrchOutput>(orchStdout)
        val verdict = JudgeParser.parseRunOutput(data.verdictText, "", 0)

        // The same integrity gate as parseSubmit, in the same position — above the CE branch. No
        // completeness count here: /run grades a filtered subset (samples plus any custom inputs), so
        // the expected set is the caller's, not the problem's; expected = 0 omits the count from the
        // message rather than inventing one.
        requireTrustworthyRun("run", data.verdictText, verdict.testcases.size, expected = 0)

        if (verdict.status == Status.CE && verdict.testcases.isEmpty()) {
            return RunResult(emptyList(), JudgeParser.cleanCompileOutput(data.verdictText))
        }
        val byName = verdict.testcases.associateBy { it.name }
        val cases = data.cases.map { c ->
            val tc = byName[c.btName]
            val er = JudgeParser.stripBtNoise(c.stderr ?: "")
            var status = tc?.status?.name
            if (status == "RTE" && JudgeParser.isMemoryError(er)) status = "MLE"
            RunCase(c.name, status, tc?.timeS, c.input, c.expected, c.stdout ?: "", er)
        }
        return RunResult(cases)
    }
}
