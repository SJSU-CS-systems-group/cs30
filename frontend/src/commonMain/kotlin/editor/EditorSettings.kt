package editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The student's editor preferences, chosen in the Settings menu. They last for the session (until a
 * reload), like the theme. Provided once at the app root through [LocalEditorSettings].
 */
class EditorSettings {
    /** Color the code by syntax; off shows plain text. */
    var syntaxHighlighting by mutableStateOf(true)

    /** Auto-close brackets and quotes, wrap a selection in them, and delete an empty pair together. */
    var bracketAssist by mutableStateOf(true)
}

val LocalEditorSettings = staticCompositionLocalOf<EditorSettings> {
    error("LocalEditorSettings not provided. Provide it at the app root.")
}
