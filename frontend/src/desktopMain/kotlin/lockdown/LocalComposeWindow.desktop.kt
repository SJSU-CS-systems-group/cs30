package lockdown

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow

/** Provided from the desktop entry point so LockdownController can manipulate the window. */
val LocalComposeWindow = staticCompositionLocalOf<ComposeWindow?> { null }
