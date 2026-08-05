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
import auth.KioskGateCheck
import auth.KioskGateStatus
import auth.KioskSecretDesktop
import html.HtmlRenderer
import html.LocalHtmlRenderer
import lockdown.LocalComposeWindow
import java.awt.Desktop
import java.awt.Toolkit
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import javax.swing.JOptionPane
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
        val url = URI("${AuthConfigDesktop.BACKEND_BASE_URL}/api/logout").toURL()
        println("[logoutAndExit] calling $url")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        KioskSecretDesktop.value?.let { conn.setRequestProperty(KioskSecretDesktop.headerName, it) }
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

/**
 * Tells the student why the app will not start, instead of letting them reach a half-loaded UI that
 * fails every action with an opaque "HTTP 403".
 */
private fun showKioskBlockedDialog() {
    val dismissed = CountDownLatch(1)
    SwingUtilities.invokeLater {
        JOptionPane.showMessageDialog(
            null,
            KioskGateCheck.blockedMessage,
            KIOSK_BLOCKED_TITLE,
            JOptionPane.ERROR_MESSAGE
        )
        dismissed.countDown()
    }
    dismissed.await()
}

private const val KIOSK_BLOCKED_TITLE = "CS30 cannot start"

fun main() {
    // Ask before building any UI whether this process can get past the kiosk gate. Only an
    // unambiguous rejection stops startup — see KioskGateCheck for why anything else proceeds.
    if (KioskGateCheck.probe() == KioskGateStatus.BLOCKED) {
        println("[kiosk] blocked by the backend; not starting the UI")
        showKioskBlockedDialog()
        return
    }

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
