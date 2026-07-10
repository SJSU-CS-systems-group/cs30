package com.cs30.server.dto

/**
 * Health report for one lab: is every problem ready to be graded before the lab opens.
 * `judgeReachable`/`judgeReady` are derived from the per-problem grade attempts (submit-only) —
 * there are no separate health calls to the judge.
 */
data class LabHealthReport(
    val courseId: String,
    val labNumber: Int,
    val ok: Boolean,
    val judgeReachable: Boolean,
    val judgeReady: Boolean,
    val problems: List<ProblemHealth>,
    val detail: String? = null,
)

/**
 * READY      — statement + package present and an accepted solution graded AC.
 * UNVERIFIED — statement + package present, but no accepted solution to grade, so the judge/Docker
 *              pipeline could not be exercised for this problem. Not broken, just not provable.
 * NOT_READY  — a statement/package file is missing, or the accepted solution didn't grade AC.
 */
enum class ProblemStatus { READY, UNVERIFIED, NOT_READY }

data class ProblemHealth(
    val name: String,
    val htmlPresent: Boolean,
    val cssPresent: Boolean,
    val packagePresent: Boolean,            // problem.yaml + data/
    val acceptedSolutionPresent: Boolean,
    val status: ProblemStatus,
    val verdict: String? = null,            // grade result: "AC"/"WA"/… or null if not graded
    val passed: Int? = null,
    val total: Int? = null,
    val detail: String? = null,
)
