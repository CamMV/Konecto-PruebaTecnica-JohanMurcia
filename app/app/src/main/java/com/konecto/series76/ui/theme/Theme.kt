package com.konecto.series76.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.konecto.series76.R

/** Color variables of the Figma file (the "Dark" collection). */
object KonectoColors {
    val Primary = Color(0xFF5D4CD6) // Dark/Primary
    val PrimaryLight = Color(0xFF9D90FF) // links/accents on dark cards (contrast)
    val Secondary = Color(0xFF2B2B2B) // Dark/Secondary (cards, secondary buttons)
    val Content = Color(0xFFF3F3F3) // Dark/Content
    val SubContent = Color(0xFFC4C4C4) // Dark/Sub content
    val Placeholder = Color(0xFF999999) // Dark/Placeholder
    val Border = Color(0xFF252525) // Dark/Border
    val Black = Color(0xFF191919) // Black (input, bottom sheet)
    val Danger = Color(0xFFFF6666) // errors, "not sent"
    val DeleteTop = Color(0xFFE91E63) // swipe-to-delete panel (vertical gradient, Chat Screen 3:816)
    val DeleteBottom = Color(0xFFC2185B)
    val CardBorder = Color(0x17FFFFFF) // list cards: white 9 % over the radial gradient
    val SuggestionBorder = Color(0xFF3A3060) // suggestion cards + chat input border (unfocused)
    val InputPlaceholder = Color(0xFFF1E9FF)
    val Field = Color(0xFFD9D9D9) // login fields
    val Warning = Color(0xFFF5B85C)
    val Success = Color(0xFF6FD49B)
    val Scrim = Color(0x99000000)
}

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
)

/** Text styles of the Figma file (Poppins). lineHeight 100% = font size. */
object KonectoType {
    val H3 = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 1.0.em)
    val H4 = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 1.0.em)
    val Callout = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 1.0.em)
    val Body = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 1.4.em)
    val Body2 = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 1.4.em)
    val Metadata1 = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 1.2.em)
    val Metadata2 = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 1.2.em)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 1.35.em)
    /** Login labels (Inter Regular 12 in the mockup; the system sans is the closest bundled-free match). */
    val Label = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, color = Color.White)
}

private val colorScheme = darkColorScheme(
    primary = KonectoColors.Primary,
    onPrimary = Color.White,
    secondary = KonectoColors.Secondary,
    onSecondary = KonectoColors.Content,
    background = KonectoColors.Black,
    onBackground = KonectoColors.Content,
    surface = KonectoColors.Secondary,
    onSurface = KonectoColors.Content,
    surfaceVariant = KonectoColors.Black,
    onSurfaceVariant = KonectoColors.SubContent,
    outline = KonectoColors.Border,
    error = KonectoColors.Danger,
)

private val typography = Typography(
    titleLarge = KonectoType.H3,
    titleMedium = KonectoType.H4,
    bodyLarge = KonectoType.Body,
    bodyMedium = KonectoType.Body2,
    labelLarge = KonectoType.Callout,
    labelMedium = KonectoType.Metadata1,
    bodySmall = KonectoType.Metadata2,
)

@Composable
fun KonectoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, content = content)
}
