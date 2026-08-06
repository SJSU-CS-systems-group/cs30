package app

/**
 * Clears the first-paint loading splash defined in index.html / ta.html.
 *
 * The splash exists because the wasm bundle download + compile + Compose/Skia init happens
 * entirely before any Kotlin code runs, so it can't be a Composable — by the time Compose
 * could render a spinner, the wait is already over. The markup is plain HTML in the page
 * shell; this is the one hook that tells it we're done.
 *
 * Body must be a single JS *expression*, not a statement — the compiler inlines it as an
 * arrow-function body (`() => <body>`), so an `if (...)` here fails the webpack parse.
 * Hence `&&` for the not-defined guard; its return value is simply discarded.
 */
internal fun hideAppSplash(): Unit = js("window.__cs30HideSplash && window.__cs30HideSplash()")
