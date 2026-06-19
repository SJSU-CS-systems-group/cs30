package editor

// Baked in at build time from application.properties (editor.max-custom-test-cases)
// by the generateEditorWebConfig task in build.gradle.kts (WEB_MAX_CUSTOM_TEST_CASES).
actual val maxCustomTestCases: Int = WEB_MAX_CUSTOM_TEST_CASES
