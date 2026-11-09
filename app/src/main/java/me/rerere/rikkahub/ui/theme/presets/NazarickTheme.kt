package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

/**
 * Nazarick — the house palette, and the fork's default (owner ruling, v0.6): obsidian
 * black, throne-room gold, blood crimson, with a Slane-Theocratic purple as the tertiary.
 * Registered FIRST in PresetThemes so fresh installs and unknown stored ids both land here;
 * every other preset (and Material You dynamic color) stays a one-tap choice on the theme
 * page — the default moved, the options did not.
 *
 * Hand-tuned tokens in the same shape as the generated presets: every on* pairing is the
 * high-contrast member, the surface ladder is monotonic, and error keeps the house red so
 * destructive actions read the same in every theme.
 */
val NazarickThemePreset by lazy {
    PresetTheme(
        id = "nazarick",
        name = {
            Text(stringResource(id = R.string.theme_name_nazarick))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

// ---- light: ivory parchment, dark gold ink, crimson seal ----
private val primaryLight = Color(0xFF7A5C14)
private val onPrimaryLight = Color(0xFFFFFBF4)
private val primaryContainerLight = Color(0xFFF6E4B0)
private val onPrimaryContainerLight = Color(0xFF3E2D00)
private val secondaryLight = Color(0xFF9B2335)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFFBD9DE)
private val onSecondaryContainerLight = Color(0xFF5E0714)
private val tertiaryLight = Color(0xFF51408C)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFE2DAFF)
private val onTertiaryContainerLight = Color(0xFF2E1E6B)
private val errorLight = Color(0xFFDC2626)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFEE2E2)
private val onErrorContainerLight = Color(0xFF991B1B)
private val backgroundLight = Color(0xFFFAF7F0)
private val onBackgroundLight = Color(0xFF1D1A14)
private val surfaceLight = Color(0xFFFAF7F0)
private val onSurfaceLight = Color(0xFF1D1A14)
private val surfaceVariantLight = Color(0xFFEDE7DA)
private val onSurfaceVariantLight = Color(0xFF4D4639)
private val outlineLight = Color(0xFF7E766A)
private val outlineVariantLight = Color(0xFFDDD5C6)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF332F28)
private val inverseOnSurfaceLight = Color(0xFFF5EFE4)
private val inversePrimaryLight = Color(0xFFE8C96A)
private val surfaceDimLight = Color(0xFFEEE9DF)
private val surfaceBrightLight = Color(0xFFFFFCF5)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF5F1E8)
private val surfaceContainerLight = Color(0xFFEFEADD)
private val surfaceContainerHighLight = Color(0xFFE8E2D3)
private val surfaceContainerHighestLight = Color(0xFFE1DAC9)

// ---- dark: obsidian, molten gold, deep crimson, aura purple ----
private val primaryDark = Color(0xFFD4AF37)
private val onPrimaryDark = Color(0xFF221A00)
private val primaryContainerDark = Color(0xFF4E3D0A)
private val onPrimaryContainerDark = Color(0xFFF3D98B)
private val secondaryDark = Color(0xFFE58FA0)
private val onSecondaryDark = Color(0xFF52101F)
private val secondaryContainerDark = Color(0xFF6F1B2B)
private val onSecondaryContainerDark = Color(0xFFFBD9DE)
private val tertiaryDark = Color(0xFFBFA8FF)
private val onTertiaryDark = Color(0xFF2A1A5C)
private val tertiaryContainerDark = Color(0xFF3C2A75)
private val onTertiaryContainerDark = Color(0xFFE2DAFF)
private val errorDark = Color(0xFFEF4444)
private val onErrorDark = Color(0xFF7F1D1D)
private val errorContainerDark = Color(0xFF991B1B)
private val onErrorContainerDark = Color(0xFFFEE2E2)
private val backgroundDark = Color(0xFF0B0A10)
private val onBackgroundDark = Color(0xFFE9E6F2)
private val surfaceDark = Color(0xFF0B0A10)
private val onSurfaceDark = Color(0xFFE9E6F2)
private val surfaceVariantDark = Color(0xFF262133)
private val onSurfaceVariantDark = Color(0xFFB7AFC9)
private val outlineDark = Color(0xFF8B839B)
private val outlineVariantDark = Color(0xFF3A3547)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFE9E6F2)
private val inverseOnSurfaceDark = Color(0xFF1B1822)
private val inversePrimaryDark = Color(0xFF7A5C14)
private val surfaceDimDark = Color(0xFF0B0A10)
private val surfaceBrightDark = Color(0xFF221E2E)
private val surfaceContainerLowestDark = Color(0xFF060509)
private val surfaceContainerLowDark = Color(0xFF12101A)
private val surfaceContainerDark = Color(0xFF181522)
private val surfaceContainerHighDark = Color(0xFF1F1B2B)
private val surfaceContainerHighestDark = Color(0xFF272236)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
