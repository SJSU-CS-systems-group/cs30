package labx.lockdown

import androidx.compose.runtime.Composable

/** Returns a LockdownController appropriately wired for the current platform. */
@Composable
expect fun rememberPlatformLockdownController(): LockdownController
