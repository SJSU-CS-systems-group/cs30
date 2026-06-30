package lockdown

/** Overwrite the system clipboard with a sentinel string so copied code cannot escape the app. */
expect fun clearSystemClipboard()

/** Write arbitrary text to the system clipboard. */
expect fun copyToClipboard(text: String)

const val CLIPBOARD_SENTINEL: String = "[CS30 lab in progress]"
