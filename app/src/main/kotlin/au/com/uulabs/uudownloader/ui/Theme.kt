package au.com.uulabs.uudownloader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Black, white, and the greys in between.
 *
 * The icon is a black tile with a white mark, and the app is the same idea at full size: one
 * lit thing per screen, everything else quiet. There is deliberately no accent colour to
 * compete with the content the user is actually here for — the videos.
 */
object Uu {
    val Ink = Color(0xFF050506)
    val InkLift = Color(0xFF0A0B0D)
    val Surface = Color(0xFF0C0D10)
    val SurfaceHigh = Color(0xFF15171B)
    val Hairline = Color(0xFF23262C)
    val Text = Color(0xFFF4F6F9)
    val Muted = Color(0xFF9AA3AE)
    val Faint = Color(0xFF646C77)
    val Danger = Color(0xFFE8706E)

    /** The one lit thing: primary actions and progress. */
    val Accent = Color(0xFFF4F6F9)
    val AccentDim = Color(0xFF8E959F)
}

private val UuColorScheme = darkColorScheme(
    primary = Uu.Accent,
    onPrimary = Uu.Ink,
    secondary = Uu.Muted,
    onSecondary = Uu.Ink,
    background = Uu.Ink,
    onBackground = Uu.Text,
    surface = Uu.Surface,
    onSurface = Uu.Text,
    surfaceVariant = Uu.SurfaceHigh,
    onSurfaceVariant = Uu.Muted,
    outline = Uu.Hairline,
    error = Uu.Danger,
)

private val UuTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.8.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)

@Composable
fun UuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UuColorScheme,
        typography = UuTypography,
        content = content,
    )
}
