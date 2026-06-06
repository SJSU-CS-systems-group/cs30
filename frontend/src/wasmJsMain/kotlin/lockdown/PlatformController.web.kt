package lockdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPlatformLockdownController(): LockdownController =
    remember { LockdownController() }
