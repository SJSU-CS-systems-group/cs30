package html

import androidx.compose.runtime.compositionLocalOf

/** Pre-initialized HtmlRenderer provided from main(). Null on platforms that create lazily. */
val LocalHtmlRenderer = compositionLocalOf<HtmlRenderer?> { null }
