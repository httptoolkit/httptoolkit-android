package tech.httptoolkit.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import tech.httptoolkit.android.R

val DmSansFontFamily = FontFamily(
    Font(R.font.dmsans, FontWeight.Normal),
    Font(R.font.dmsans_bold, FontWeight.Bold)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2D4CBD),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFE1421F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFE4E8ED), // Background = 'container' in UI themes
    onBackground = Color(0xFF1E2028),
    surface = Color(0xFFFAFAFA), // Surface = 'main' in UI themes
    onSurface = Color(0xFF1E2028),
    outline = Color(0xFF818490),
    error = Color(0xFFE1421F),
    onError = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF2D4CBD),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFE1421F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF1E2028),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF32343B),
    onSurface = Color(0xFFFFFFFF),
    outline = Color(0xFF9A9DA8),
    error = Color(0xFFE1421F),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun HttpToolkitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
