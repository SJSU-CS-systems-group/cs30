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

    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

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

    private data class Proc(val stdout: String, val stderr: String, val exit: Int)

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
        return listOf(
            "--rm",
            "--name", name,
            "--network=none",
            "--cap-drop=ALL",
            "--security-opt=no-new-privileges",
            "--read-only",
            "--tmpfs", "/work:rw,exec,size=${s.workTmpfsMb}m,nr_inodes=16384,uid=${s.uid},gid=${s.gid}",
            "--tmpfs", "/tmp:rw,exec,size=${s.tmpTmpfsMb}m,nr_inodes=4096,uid=${s.uid},gid=${s.gid}",
            "--pids-limit=${s.pidsLimit}",
            "--memory=${s.memoryMb}m", "--memory-swap=${s.memoryMb}m",
            "--cpus=${s.cpus}",
            "--ulimit", "fsize=${s.fsizeBytes}",
            "-u", "${s.uid}:${s.gid}",
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
        return parseSubmit(proc.stdout, proc.stderr)
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

    private fun parseSubmit(orchStdout: String, orchStderr: String): SubmitResult {
        if (orchStdout.isBlank()) {
            throw RuntimeException("submit orchestrator produced no output: ${orchStderr.take(500)}")
        }
        val data = mapper.readValue<OrchOutput>(orchStdout)
        val verdict = JudgeParser.parseRunOutput(data.verdictText, "", 0)
        if (verdict.status == Status.CE && verdict.testcases.isEmpty()) {
            return SubmitResult("CE", 0, 0, 0.0, emptyList(), JudgeParser.cleanCompileOutput(data.verdictText))
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

    private fun parseSamples(orchStdout: String, orchStderr: String): RunResult {
        if (orchStdout.isBlank()) {
            throw RuntimeException("run orchestrator produced no output: ${orchStderr.take(500)}")
        }
        val data = mapper.readValue<OrchOutput>(orchStdout)
        val verdict = JudgeParser.parseRunOutput(data.verdictText, "", 0)
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
