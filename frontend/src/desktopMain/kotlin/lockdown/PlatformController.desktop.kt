package lockdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

@Composable
actual fun rememberPlatformLockdownController(): LockdownController {
    val controller = remember { LockdownController() }
    val window = LocalComposeWindow.current
    SideEffect { controller.window = window }
    return controller
}
