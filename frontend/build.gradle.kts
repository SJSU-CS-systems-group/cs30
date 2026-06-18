import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Detect host OS+arch and pick the matching JavaFX native classifier.
val javafxClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm")) -> "mac-aarch64"
        os.contains("mac") -> "mac"
        os.contains("windows") -> "win"
        os.contains("linux") && arch.contains("aarch64") -> "linux-aarch64"
        os.contains("linux") -> "linux"
        else -> error("Unsupported host platform: os=$os arch=$arch")
    }
}
val javafxVersion = "21.0.4"

val appProps = Properties().also { props ->
    val f = rootProject.file("application.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

kotlin {
    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "composeApp"
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(project(":data"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation("org.openjfx:javafx-base:$javafxVersion:$javafxClassifier")
            implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxClassifier")
            implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxClassifier")
            implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxClassifier")
            implementation("org.openjfx:javafx-web:$javafxVersion:$javafxClassifier")
            implementation("org.openjfx:javafx-media:$javafxVersion:$javafxClassifier")
            implementation("net.java.dev.jna:jna:5.14.0")  // macOS NSPresentationOptions for lockdown
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.MainKt"

        jvmArgs += listOf(
            "-Dcs30.backend.url=${appProps.getProperty("cs30.backend.url", "http://localhost:8080")}",
            "-Dcs30.maxCustomTestCases=${appProps.getProperty("editor.max-custom-test-cases", "1")}"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "cs30"
            packageVersion = "1.0.0"
            modules("javafx.controls", "javafx.swing", "javafx.web", "jdk.unsupported")
        }
    }
}

compose.experimental {
    web.application {}
}
