package labx.lockdown

/** Overwrite the system clipboard with a sentinel string so copied code cannot escape the app. */
expect fun clearSystemClipboard()

const val CLIPBOARD_SENTINEL: String = "[CS30 lab in progress]"
