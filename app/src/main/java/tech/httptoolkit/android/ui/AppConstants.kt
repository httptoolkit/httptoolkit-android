package tech.httptoolkit.android.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Assorted UI constants reused across multiple screens.
 * Screen-specific constants are defined locally in their respective files.
 */
object AppConstants {

    val spacingTiny: Dp = 4.dp
    val spacingSmall: Dp = 8.dp
    val spacingNormal: Dp = 10.dp
    val spacingMedium: Dp = 12.dp
    val spacingLarge: Dp = 16.dp

    val elevationNone: Dp = 0.dp
    val elevationDefault: Dp = 4.dp

    val buttonHeight: Dp = 48.dp

    val textSizeCaption: TextUnit = 14.sp
    val textSizeBodyLarge: TextUnit = 20.sp
    val textSizeHeading: TextUnit = 40.sp

    val letterSpacingNone: TextUnit = 0.sp
}
