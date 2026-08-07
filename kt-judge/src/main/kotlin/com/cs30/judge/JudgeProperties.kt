package com.cs30.judge

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "judge")
data class JudgeProperties(
    val image: String = "judge-sandbox:latest",
    val sandbox: Sandbox = Sandbox(),
    val concurrency: Concurrency = Concurrency(),
    val timeouts: Timeouts = Timeouts(),
    val limits: Limits = Limits(),
    val languages: Map<String, String> = mapOf(
        "c" to ".c", "cpp" to ".cpp", "java" to ".java", "python" to ".py",
    ),
) {
    data class Sandbox(
        val memoryMb: Int = 1024,
        val cpus: Double = 1.0,
        val pidsLimit: Int = 256,
        val fsizeBytes: Long = 33_554_432,
        val workTmpfsMb: Int = 512,
        val tmpTmpfsMb: Int = 128,
        val uid: Int = 1000,
        // Host group NAME the container runs as. Its GID is looked up on THIS host (getent) at
        // runtime, so the same config works on any host without hardcoding a numeric GID. If unset
        // or unresolvable, the container's group falls back to `uid`.
        val group: String = "",
    )

    data class Concurrency(
        val maxWorkers: Int = Runtime.getRuntime().availableProcessors(),
        val maxQueueSize: Int = 100,
    )

    data class Timeouts(
        val runAllWallSeconds: Int = 60,
    )

    data class Limits(
        val maxCustomCases: Int = 10,
    )
}
