package com.arokya.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.arokya.app.R

/**
 * Poppins — the app's single typeface, bundled as .ttf so it renders identically
 * offline (matching Arokya's on-device ethos; no Google Fonts network fetch).
 *
 * Only the four weights the UI actually uses are shipped: a font file is
 * ~160 KB each, so four weights keep the APK lean while covering Normal (body),
 * Medium (labels), SemiBold (buttons/headings) and Bold (titles).
 */
val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

/**
 * Every Material text style re-based on Poppins. Screens set their own sizes
 * inline, so this mainly guarantees Material components (buttons, dialogs) and
 * any un-styled Text pick up Poppins rather than the system sans.
 */
val ArokyaTypography: Typography = Typography().run {
    fun TextStyle.p() = copy(fontFamily = Poppins)
    Typography(
        displayLarge = displayLarge.p(), displayMedium = displayMedium.p(), displaySmall = displaySmall.p(),
        headlineLarge = headlineLarge.p(), headlineMedium = headlineMedium.p(), headlineSmall = headlineSmall.p(),
        titleLarge = titleLarge.p(), titleMedium = titleMedium.p(), titleSmall = titleSmall.p(),
        bodyLarge = bodyLarge.p(), bodyMedium = bodyMedium.p(), bodySmall = bodySmall.p(),
        labelLarge = labelLarge.p(), labelMedium = labelMedium.p(), labelSmall = labelSmall.p(),
    )
}
