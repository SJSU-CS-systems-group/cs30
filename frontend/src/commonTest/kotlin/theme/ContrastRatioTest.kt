package theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

// WCAG 2.x contrast math (https://www.w3.org/TR/WCAG21/#contrast-minimum). AA for normal text is
// 4.5:1. This turns the AA/ANSI-limitation claims documented in Theme.kt / EditorPalette.kt /
// the cs30-ui-style skill from unverified comments into an enforced, repeatable check.
private const val WCAG_AA_NORMAL_TEXT = 4.5

private fun srgbChannelToLinear(c: Float): Double =
    if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

private fun relativeLuminance(color: Color): Double {
    val r = srgbChannelToLinear(color.red)
    val g = srgbChannelToLinear(color.green)
    val b = srgbChannelToLinear(color.blue)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun contrastRatio(a: Color, b: Color): Double {
    val l1 = maxOf(relativeLuminance(a), relativeLuminance(b))
    val l2 = minOf(relativeLuminance(a), relativeLuminance(b))
    return (l1 + 0.05) / (l2 + 0.05)
}

class ContrastRatioTest {

    private val allThemes = AppTheme.entries
    private val highContrastThemes = setOf(AppTheme.LIGHT_HIGH_CONTRAST, AppTheme.DARK_HIGH_CONTRAST)

    // ANSI is documented (cs30-ui-style skill, "Accepted ANSI limitation") as not meeting AA for
    // every pairing — the 16-color palette can't satisfy it. These are the specific, known
    // shortfalls measured against this test's own math; any other ANSI pairing must still pass.
    private val knownAnsiShortfalls = setOf(
        // ANSI green (the only "green" the 16-color palette offers) on white: ~3.11:1, inherent
        // to ANSI's fixed palette — see cs30-ui-style skill, "Accepted ANSI limitation".
        AppTheme.LIGHT_ANSI to "pass",
    )

    @Test
    fun textOnSurfaceMeetsAA() {
        for (theme in allThemes) {
            val cs = colorSchemeFor(theme)
            assertTrue(
                contrastRatio(cs.onSurface, cs.surface) >= WCAG_AA_NORMAL_TEXT,
                "$theme: onSurface vs surface = ${contrastRatio(cs.onSurface, cs.surface)}, need >= $WCAG_AA_NORMAL_TEXT"
            )
            assertTrue(
                contrastRatio(cs.onBackground, cs.background) >= WCAG_AA_NORMAL_TEXT,
                "$theme: onBackground vs background = ${contrastRatio(cs.onBackground, cs.background)}, need >= $WCAG_AA_NORMAL_TEXT"
            )
        }
    }

    @Test
    fun verdictColorsOnSurfaceMeetAA() {
        for (theme in allThemes) {
            val cs = colorSchemeFor(theme)
            val palette = editorPaletteFor(theme)
            val verdicts = mapOf("pass" to palette.pass, "fail" to palette.fail, "warning" to palette.warning)

            for ((name, color) in verdicts) {
                val ratio = contrastRatio(color, cs.surface)
                val isKnownShortfall = (theme to name) in knownAnsiShortfalls
                if (isKnownShortfall) {
                    assertTrue(
                        ratio < WCAG_AA_NORMAL_TEXT,
                        "$theme: $name vs surface = $ratio is now >= $WCAG_AA_NORMAL_TEXT — " +
                            "this was a documented ANSI shortfall, remove it from knownAnsiShortfalls"
                    )
                } else {
                    assertTrue(
                        ratio >= WCAG_AA_NORMAL_TEXT,
                        "$theme: $name vs surface = $ratio, need >= $WCAG_AA_NORMAL_TEXT"
                    )
                }
            }
        }
    }

    @Test
    fun highContrastThemesHaveNoShortfalls() {
        // High-contrast schemes are claimed AA-compliant with no caveat (unlike ANSI) — confirm
        // no entry in knownAnsiShortfalls accidentally covers them.
        for (theme in highContrastThemes) {
            assertTrue(
                knownAnsiShortfalls.none { it.first == theme },
                "$theme is high-contrast and must have zero documented shortfalls"
            )
        }
    }
}
