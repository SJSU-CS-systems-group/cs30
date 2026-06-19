package app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import auth.ApiToken
import auth.AuthConfigDesktop
import html.HtmlRenderer
import html.LocalHtmlRenderer
import lockdown.LocalComposeWindow
import java.awt.Desktop
import java.awt.Toolkit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities

/**
 * Synchronously logout from the backend before closing the application.
 * This ensures the session token is revoked when the user closes the window.
 */
private fun logoutAndExit() {
    val token = ApiToken.value
    println("[logoutAndExit] token=${if (token != null) "present" else "null"}")
    if (token == null) return

    val result = runCatching {
        val url = URL("${AuthConfigDesktop.BACKEND_BASE_URL}/api/logout")
        println("[logoutAndExit] calling $url")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        val code = conn.responseCode
        println("[logoutAndExit] response code=$code")
        conn.disconnect()
    }
    result.onFailure { e ->
        println("[logoutAndExit] error: ${e.message}")
    }
    ApiToken.value = null
}

fun main() {
    // Register shutdown hook to logout when JVM exits (fallback for force-quit)
    Runtime.getRuntime().addShutdownHook(Thread {
        println("[shutdown-hook] JVM shutting down, attempting logout")
        logoutAndExit()
    })

    // Pre-initialize HtmlRenderer on the EDT before Compose starts.
    // JFXPanel() uses WaitDispatchSupport.enter() internally — calling it from inside
    // a Compose paint cycle causes reentry crashes. Creating it here (before application {})
    // is safe because no Compose render loop is active yet.
    lateinit var htmlRenderer: HtmlRenderer
    val latch = CountDownLatch(1)
    SwingUtilities.invokeLater {
        htmlRenderer = HtmlRenderer()
        latch.countDown()
    }
    latch.await()

    application {
        Window(
            onCloseRequest = {
                logoutAndExit()
                exitApplication()
            },
            title = "CS30 Code Editor",
            undecorated = true,
            state = rememberWindowState(placement = WindowPlacement.Maximized)
        ) {
            // undecorated=true disables macOS's automatic safe-area inset below menu bar.
            // Clamp the window below the menu bar on first composition.
            val gc = window.graphicsConfiguration
            val screenInsets = remember { Toolkit.getDefaultToolkit().getScreenInsets(gc) }
            SideEffect {
                val currentY = window.y
                if (currentY < screenInsets.top) {
                    window.setLocation(window.x, screenInsets.top)
                    println("[main] 📐 Window clamped below menu bar: y=$currentY → y=${screenInsets.top} (insets.top=${screenInsets.top})")
                } else {
                    println("[main] ✓ Window already below menu bar: y=$currentY (insets.top=${screenInsets.top})")
                }
            }

            CompositionLocalProvider(
                LocalComposeWindow provides window,
                LocalHtmlRenderer provides htmlRenderer,
            ) {
                App(
                    bringToFront = {
                        SwingUtilities.invokeLater {
                            if (Desktop.isDesktopSupported() &&
                                Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
                                Desktop.getDesktop().requestForeground(true)
                            }
                            window.toFront()
                            window.requestFocusInWindow()
                        }
                    },
                    onCloseApp = {
                        logoutAndExit()
                        exitApplication()
                    }
                )
            }
        }
    }
}
