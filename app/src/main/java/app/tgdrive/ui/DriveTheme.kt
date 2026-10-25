package app.tgdrive.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Native, asset-free styling based on the supplied Muse Android screenshots. */
object DriveStyle {
    val Canvas = Color(0xFF1E1E1E)
    val SignInCanvas = Color(0xFF050505)
    val Card = Color(0xFF333536)
    val Blue = Color(0xFF0085FF)
    val PageInset = 16.dp
    val CardShape = RoundedCornerShape(24.dp)
    val PillShape = RoundedCornerShape(50)
    const val EnterMillis = 180
    const val ExitMillis = 100
}

private val DriveColors = darkColorScheme(
    primary = DriveStyle.Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFF123E65), onPrimaryContainer = Color(0xFFD6EBFF),
    inversePrimary = Color(0xFF0067CE),
    secondary = Color(0xFFB4B8BC), onSecondary = Color(0xFF161718),
    secondaryContainer = Color(0xFF183C5B), onSecondaryContainer = Color(0xFFE2F0FF),
    tertiary = Color(0xFFA8CAED), onTertiary = Color(0xFF102C45),
    tertiaryContainer = Color(0xFF243B50), onTertiaryContainer = Color(0xFFD8E9FA),
    background = DriveStyle.Canvas, onBackground = Color(0xFFF5F5F5),
    surface = DriveStyle.Card, onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF3B3D3F), onSurfaceVariant = Color(0xFFB3B5B8),
    surfaceDim = Color(0xFF191A1B), surfaceBright = Color(0xFF414345),
    surfaceContainerLowest = DriveStyle.SignInCanvas,
    surfaceContainerLow = Color(0xFF2B2D2F), surfaceContainer = DriveStyle.Card,
    surfaceContainerHigh = Color(0xFF393B3D), surfaceContainerHighest = Color(0xFF414345),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF777B7E), outlineVariant = Color(0xFF484A4C),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF59312E), onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF0F0F0), inverseOnSurface = Color(0xFF202020),
    scrim = Color.Black,
)

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = height.sp, letterSpacing = 0.sp,
)
private val DriveTypography = Typography(
    displayLarge = type(48, 54, FontWeight.Bold), displayMedium = type(40, 46, FontWeight.Bold),
    displaySmall = type(34, 40, FontWeight.Bold),
    headlineLarge = type(30, 36, FontWeight.Bold), headlineMedium = type(26, 32, FontWeight.Bold),
    headlineSmall = type(23, 29, FontWeight.Bold),
    titleLarge = type(20, 26, FontWeight.Bold), titleMedium = type(17, 23, FontWeight.Bold),
    titleSmall = type(16, 22, FontWeight.SemiBold),
    bodyLarge = type(16, 24), bodyMedium = type(14, 21), bodySmall = type(13, 19),
    labelLarge = type(16, 22, FontWeight.Bold), labelMedium = type(13, 18, FontWeight.SemiBold),
    labelSmall = type(12, 17, FontWeight.Medium),
)

@Composable
fun DriveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DriveColors, typography = DriveTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(16.dp),
            medium = DriveStyle.CardShape, large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ), content = content,
    )
}
