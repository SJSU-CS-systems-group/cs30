package lockdown

actual val defaultReporterBaseUrl: String = System.getProperty("cs30.backend.url", "http://localhost:8080")
