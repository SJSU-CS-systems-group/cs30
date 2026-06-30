package com.cs30.judge

enum class Status { AC, WA, TLE, RTE, MLE, CE, JE }

// Worst-first. The overall verdict is the highest-precedence one across testcases.
val PRECEDENCE = listOf(Status.CE, Status.JE, Status.RTE, Status.MLE, Status.TLE, Status.WA, Status.AC)

// Worst status as a string, for recomputing overall after MLE relabeling.
private val ORDER = listOf("CE", "JE", "RTE", "MLE", "TLE", "WA", "AC")

fun worstStatus(statuses: List<String>): String =
    statuses.minByOrNull { ORDER.indexOf(it).let { i -> if (i < 0) ORDER.size else i } } ?: "AC"

data class TestcaseResult(
    val name: String,
    val status: Status,
    val timeS: Double,
    val detail: String? = null,
)

data class Verdict(
    val status: Status,
    val testcases: List<TestcaseResult> = emptyList(),
    val passed: Int = 0,
    val total: Int = 0,
    val maxTimeS: Double = 0.0,
    val rawStdout: String = "",
    val rawStderr: String = "",
    val returnCode: Int = 0,
)

data class RawRun(val stdout: String, val stderr: String, val returnCode: Int)

// One sample-or-custom case for /run: verdict + full output. status/time are null
// for a custom case submitted without an expected answer.
data class RunCase(
    val name: String,
    val status: String?,
    val timeS: Double?,
    val input: String?,
    val expected: String?,
    val stdout: String,
    val stderr: String,
)

// One graded case for /submit. Sample cases carry full detail (public); secret
// cases carry status + time only (the rest stays null — no leak).
data class SubmitCase(
    val name: String,
    val status: String,
    val timeS: Double,
    val input: String? = null,
    val expected: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
)

data class SubmitResult(
    val status: String,
    val passed: Int,
    val total: Int,
    val maxTimeS: Double,
    val cases: List<SubmitCase>,
    val compileOutput: String? = null,
)

data class RunResult(
    val cases: List<RunCase>,
    val compileOutput: String? = null,
)
