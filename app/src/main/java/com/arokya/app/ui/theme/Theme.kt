package com.arokya.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- Arokya design tokens (taken from your Figma flow map) ----
object Ar {
    val Teal = Color(0xFF1E8A82)        // primary actions, brand
    val TealDark = Color(0xFF166B65)
    val TealChipBg = Color(0xFFE4F3F1)
    val Cream = Color(0xFFEDEBE6)       // app background
    val Navy = Color(0xFF1B2A4A)        // headings / primary text
    val Slate = Color(0xFF5B6478)       // secondary text
    val Muted = Color(0xFF8A94A6)       // captions
    val Orange = Color(0xFFD9622B)      // proactive / hero accent
    val OrangeChipBg = Color(0xFFFDE6D8)
    val Amber = Color(0xFFD99B27)
    val AmberChipBg = Color(0xFFFFF3D6)
    val Purple = Color(0xFF7B4FA6)
    val PurpleChipBg = Color(0xFFEDE1F5)
    val Blue = Color(0xFF2E86AB)
    val BlueChipBg = Color(0xFFDCEEFB)
    val CardBg = Color(0xFFFFFFFF)
}

private val ArokyaColors = lightColorScheme(
    primary = Ar.Teal,
    onPrimary = Color.White,
    secondary = Ar.Blue,
    background = Ar.Cream,
    onBackground = Ar.Navy,
    surface = Ar.CardBg,
    onSurface = Ar.Navy,
    surfaceVariant = Ar.TealChipBg,
    onSurfaceVariant = Ar.Slate,
)

@Composable
fun ArokyaTheme(content: @Composable () -> Unit) {
    // The prototype is light-only; we keep it that way for the demo.
    isSystemInDarkTheme() // read but intentionally unused
    MaterialTheme(colorScheme = ArokyaColors, typography = ArokyaTypography) {
        // Screens call Text() with sizes/weights but no fontFamily, so the
        // family comes from LocalTextStyle. Seeding it with Poppins here makes
        // every Text in the app render in Poppins with one change, no per-call
        // edits — with Navy as the sensible default ink colour.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides
                    androidx.compose.material3.LocalTextStyle.current.copy(
                        fontFamily = Poppins,
                        color = Ar.Navy,
                    ),
            content = content,
        )
    }
}
