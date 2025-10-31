package tech.httptoolkit.android.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Light theme colors from values/colors.xml
private val LightColors = lightColorScheme(
    primary = Color(0xFF2D4CBD),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFE1421F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1E2028),
    surface = Color(0xFFE4E8ED),
    onSurface = Color(0xFF1E2028),
    surfaceVariant = Color(0xFFFAFAFA),
    onSurfaceVariant = Color(0xFF1E2028),
    outline = Color(0xFF818490),
    error = Color(0xFFE1421F),
    onError = Color(0xFFFFFFFF)
)

// Dark theme colors from values-night/colors.xml
private val DarkColors = darkColorScheme(
    primary = Color(0xFF2D4CBD),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFE1421F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF32343B),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1E2028),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF32343B),
    onSurfaceVariant = Color(0xFFFFFFFF),
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

    // Use dynamic color on Android 12+ if desired, but fallback to our custom colors
    val finalColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
    } else {
        colorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme, // Use our custom colors, not dynamic
        typography = Typography(),
        content = content
    )
}
