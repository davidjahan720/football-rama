package fr.footballrama.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Palette sombre "industrielle". Ratios de contraste WCAG (texte) mesures :
 * Text/Background 16.0, Text/Surface 14.2, Text/SurfaceFocused 11.6, TextMuted/Background 9.2,
 * TextMuted/SurfaceFocused 6.7, Accent/Background 11.5, OnAccent/Accent 11.5, Fort 6.5, Moyen 8.9,
 * Erreur 8.4, Avertissement 10.1, Info 11.3, Badge competition 9.6. Tous >= 4.5:1 (AA).
 */
object FrColors {
    val Background = Color(0xFF101418)
    val Surface = Color(0xFF1A2027)
    val SurfaceFocused = Color(0xFF26303A)
    val Outline = Color(0xFF5B6874) // 3.2:1 sur le fond (element non textuel, >= 3:1)
    val Text = Color(0xFFECEFF2)
    val TextMuted = Color(0xFFAEB8C2)
    val Accent = Color(0xFFFFC233) // indicateur de focus
    val AccentFocused = Color(0xFFFFD466)
    val OnAccent = Color(0xFF101418)
    val FortBg = Color(0xFF1F6B3A)
    val FortText = Color(0xFFFFFFFF)
    val MoyenBg = Color(0xFF4D3A00)
    val MoyenText = Color(0xFFFFE6A8)
    val ErrorBg = Color(0xFF4A1C1C)
    val ErrorText = Color(0xFFFFB4AB)
    val WarningBg = Color(0xFF3B3000)
    val WarningText = Color(0xFFFFE08A)
    val InfoBg = Color(0xFF1A2B3A)
    val InfoText = Color(0xFFCFE6FF)
    val BadgeBg = Color(0xFF2A3440)
    val BadgeText = Color(0xFFD6E2EE)
}

/** Marges de securite overscan (TV) : ~5 % horizontal, ~5 % vertical d'un ecran 960x540 dp. */
object Safe {
    val Horizontal = 48.dp
    val Vertical = 27.dp
}

/** Tailles lisibles a 3 m : corps >= 18 sp. */
object FrText {
    val Display = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
    val Title = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    val Section = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
    val Body = TextStyle(fontSize = 20.sp, lineHeight = 26.sp)
    val BodyStrong = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    val Label = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
    /** Texte secondaire (titre EPG, mentions) : 16 sp minimum. */
    val Small = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
}

@Composable
fun FootballRamaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FrColors.Accent,
            onPrimary = FrColors.OnAccent,
            secondary = FrColors.BadgeBg,
            onSecondary = FrColors.BadgeText,
            background = FrColors.Background,
            onBackground = FrColors.Text,
            surface = FrColors.Surface,
            onSurface = FrColors.Text,
            surfaceVariant = FrColors.SurfaceFocused,
            onSurfaceVariant = FrColors.TextMuted,
            error = FrColors.ErrorText,
            onError = FrColors.ErrorBg,
            border = FrColors.Accent,
            borderVariant = FrColors.Outline,
        ),
        typography = Typography(
            bodyLarge = FrText.Body,
            bodyMedium = FrText.Body,
            bodySmall = FrText.Small,
            labelLarge = FrText.Label,
            labelMedium = FrText.Label,
            labelSmall = FrText.Small,
            titleLarge = FrText.Title,
            titleMedium = FrText.Section,
            titleSmall = FrText.BodyStrong,
            headlineSmall = FrText.Display,
        ),
        content = content,
    )
}
