package app.tgdrive.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun DriveTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF73D6C5), secondary = Color(0xFFB6CECA), background = Color(0xFF101916), surface = Color(0xFF15211D)
    ) else lightColorScheme(
        primary = Color(0xFF147D72), onPrimary = Color.White, primaryContainer = Color(0xFFD9F4EB),
        secondary = Color(0xFF526961), background = Color(0xFFF6F8F5), surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEAF0E9), outlineVariant = Color(0xFFDCE5DD)
    )
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
