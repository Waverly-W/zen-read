package com.zenread.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.zenread.core.model.ThemeAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the generated preset palettes against copy-paste slips (wrong scheme, broken tone pairs). */
class AccentColorSchemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    private fun ColorScheme.textPairs() = listOf(
        "primary" to (primary to onPrimary),
        "primaryContainer" to (primaryContainer to onPrimaryContainer),
        "secondary" to (secondary to onSecondary),
        "secondaryContainer" to (secondaryContainer to onSecondaryContainer),
        "tertiary" to (tertiary to onTertiary),
        "tertiaryContainer" to (tertiaryContainer to onTertiaryContainer),
        "error" to (error to onError),
        "surface" to (surface to onSurface),
        "surfaceVariant" to (surfaceContainerHighest to onSurfaceVariant),
        "inverseSurface" to (inverseSurface to inverseOnSurface),
    )

    @Test
    fun textRolesMeetWcagAa() {
        ThemeAccent.entries.forEach { accent ->
            listOf(false, true).forEach { dark ->
                accentColorScheme(accent, dark).textPairs().forEach { (role, pair) ->
                    val ratio = contrast(pair.first, pair.second)
                    assertTrue("$accent dark=$dark $role contrast $ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test
    fun lightAndDarkSchemesAreTheRightWayRound() {
        ThemeAccent.entries.forEach { accent ->
            assertTrue("$accent light surface", accentColorScheme(accent, dark = false).surface.luminance() > 0.5f)
            assertTrue("$accent dark surface", accentColorScheme(accent, dark = true).surface.luminance() < 0.1f)
        }
    }

    @Test
    fun everyAccentHasItsOwnPrimary() {
        val primaries = ThemeAccent.entries.map { accentColorScheme(it, dark = false).primary }
        assertEquals(primaries.size, primaries.toSet().size)
    }

    @Test
    fun lightAndDarkShareTonalPalettes() {
        // primaryFixed is tone 90 of the primary palette in both schemes.
        ThemeAccent.entries.forEach { accent ->
            assertEquals(
                accent.name,
                accentColorScheme(accent, dark = false).primaryFixed,
                accentColorScheme(accent, dark = true).primaryFixed,
            )
        }
    }
}
