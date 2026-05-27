package labx

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import labx.lockdown.LocalComposeWindow

fun main() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "CS30 Code Editor",
            state = rememberWindowState(width = 1280.dp, height = 800.dp)
        ) {
            CompositionLocalProvider(LocalComposeWindow provides window) {
                App()
            }
        }
    }
}
