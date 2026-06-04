package labx

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import labx.html.HtmlRenderer
import labx.html.LocalHtmlRenderer
import labx.lockdown.LocalComposeWindow
import java.awt.Desktop
import java.awt.Toolkit
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities

fun main() {
    val activityLogDir = "/Users/spartan/Dev/CS30/fall26-cmpe30/s1/labs/lab-01/assignments/assignment-01/students/student01"

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
            onCloseRequest = ::exitApplication,
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
                    activityLogDir = activityLogDir,
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
                    onCloseApp = ::exitApplication
                )
            }
        }
    }
}
