package editor

// Passed in by frontend/build.gradle.kts from application.properties (editor.max-custom-test-cases).
actual val maxCustomTestCases: Int =
    System.getProperty("cs30.maxCustomTestCases", "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
