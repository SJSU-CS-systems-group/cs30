package com.cs30.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Covers [JudgeRunner.countGradedCases] — the number a submit is checked against before its verdict is
 * trusted. Getting it wrong in either direction is harmful: too low and a truncated grade is accepted as
 * complete; too high and a correct submission is rejected. The count must equal bt's own, which for
 * submit mode is every `.in` under data/sample and data/secret (bt runs with no path filter there).
 */
class JudgeRunnerTest {

    // JudgeRunner's constructor only stores props and extracts the orchestrator resource; no container
    // is started until runSubmit/runSamples, so a default-props instance is enough to reach the counter.
    private val runner = JudgeRunner(JudgeProperties())

    private fun caseFile(dir: Path, name: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("$name.in"), "1\n")
        Files.writeString(dir.resolve("$name.ans"), "1\n")
    }

    @Test fun `counts sample and secret cases`(@TempDir root: Path) {
        val data = root.resolve("data")
        caseFile(data.resolve("sample"), "1")
        caseFile(data.resolve("sample"), "2")
        repeat(5) { caseFile(data.resolve("secret"), "s$it") }
        assertEquals(7, runner.countGradedCases(root))
    }

    @Test fun `counts cases inside nested test groups`(@TempDir root: Path) {
        // Some problems group secret cases into subdirectories; bt grades those too, so the walk must
        // be recursive. cascade ships a data/test_group.yaml, so this layout is not hypothetical.
        val data = root.resolve("data")
        caseFile(data.resolve("sample"), "1")
        caseFile(data.resolve("secret").resolve("group1"), "a")
        caseFile(data.resolve("secret").resolve("group1"), "b")
        caseFile(data.resolve("secret").resolve("group2"), "c")
        assertEquals(4, runner.countGradedCases(root))
    }

    @Test fun `ignores answer files and other extensions`(@TempDir root: Path) {
        val secret = root.resolve("data").resolve("secret")
        caseFile(secret, "1")
        Files.writeString(secret.resolve("notes.txt"), "x")
        Files.writeString(secret.resolve("2.ans"), "orphan answer, no input")
        assertEquals(1, runner.countGradedCases(root))
    }

    @Test fun `ignores directories other than sample and secret`(@TempDir root: Path) {
        // data/invalid_input and similar exist in some problem formats for input-validator testing and
        // are never graded, so they must not inflate the expected count.
        val data = root.resolve("data")
        caseFile(data.resolve("sample"), "1")
        caseFile(data.resolve("invalid_input"), "bad")
        assertEquals(1, runner.countGradedCases(root))
    }

    @Test fun `returns zero when data directory is absent`(@TempDir root: Path) {
        // 0 disables the completeness check rather than rejecting the submission — a counting failure
        // must never fail a student's correct code.
        assertEquals(0, runner.countGradedCases(root))
    }

    @Test fun `returns zero for an empty data directory`(@TempDir root: Path) {
        Files.createDirectories(root.resolve("data").resolve("secret"))
        assertEquals(0, runner.countGradedCases(root))
    }
}
