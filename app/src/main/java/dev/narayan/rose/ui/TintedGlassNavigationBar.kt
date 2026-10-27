package dev.narayan.rose.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Renders a frosted/tinted glass background behind the system navigation bar,
 * ONLY when button-based navigation (e.g. 3-button or 2-button navigation) is active.
 *
 * In gesture navigation, this composable renders nothing, keeping the system bar
 * completely transparent for an immersive edge-to-edge experience.
 */
@Composable
fun BoxScope.TintedGlassNavigationBar(
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val layoutDirection = LocalLayoutDirection.current
    val navBarInsets = WindowInsets.navigationBars.asPaddingValues()
    val navBarBottom = navBarInsets.calculateBottomPadding()
    val navBarEnd = navBarInsets.calculateEndPadding(layoutDirection)
    val navBarStart = navBarInsets.calculateStartPadding(layoutDirection)

    val tappableInsets = WindowInsets.tappableElement.asPaddingValues()
    val tappableBottom = tappableInsets.calculateBottomPadding()
    val tappableEnd = tappableInsets.calculateEndPadding(layoutDirection)
    val tappableStart = tappableInsets.calculateStartPadding(layoutDirection)

    val context = LocalContext.current
    val isButtonNavSetting = remember {
        try {
            val mode = Settings.Secure.getInt(
                context.contentResolver,
                "navigation_mode",
                -1
            )
            mode == 0 || mode == 1
        } catch (_: Exception) {
            false
        }
    }

    // Determine if navigation is button-based.
    // In Android:
    // - With gesture navigation, tappableElement insets are 0.dp and navigationBars is thin (<= 24.dp).
    // - With 3-button navigation, tappableElement insets match navigationBars (typically 48.dp, >= 40.dp) or navigation_mode == 0.
    val isButtonBottom = navBarBottom > 0.dp && (tappableBottom > 0.dp || navBarBottom > 32.dp || isButtonNavSetting)
    val isButtonEnd = navBarEnd > 0.dp && (tappableEnd > 0.dp || navBarEnd > 32.dp || isButtonNavSetting)
    val isButtonStart = navBarStart > 0.dp && (tappableStart > 0.dp || navBarStart > 32.dp || isButtonNavSetting)

    if (!isButtonBottom && !isButtonEnd && !isButtonStart) return

    val backgroundColor = MaterialTheme.colorScheme.background
    val isDark = backgroundColor.luminance() < 0.5f
    val glassTint = if (isDark) {
        backgroundColor.copy(alpha = 0.52f)
    } else {
        backgroundColor.copy(alpha = 0.70f)
    }

    val hazeStyle = HazeStyle(
        backgroundColor = backgroundColor,
        tint = HazeTint(glassTint),
        blurRadius = 24.dp,
        noiseFactor = 0.04f
    )

    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    if (isButtonBottom) {
        Box(
            modifier = modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBarBottom)
                .hazeEffect(
                    state = hazeState,
                    style = hazeStyle
                )
        ) {
            // Subtle frosted glass top border / specular highlight line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.75.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                outlineColor.copy(alpha = 0.12f),
                                outlineColor.copy(alpha = 0.35f),
                                outlineColor.copy(alpha = 0.12f)
                            )
                        )
                    )
            )
        }
    } else if (isButtonEnd) {
        Box(
            modifier = modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(navBarEnd)
                .hazeEffect(
                    state = hazeState,
                    style = hazeStyle
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(0.75.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                outlineColor.copy(alpha = 0.12f),
                                outlineColor.copy(alpha = 0.35f),
                                outlineColor.copy(alpha = 0.12f)
                            )
                        )
                    )
            )
        }
    } else if (isButtonStart) {
        Box(
            modifier = modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(navBarStart)
                .hazeEffect(
                    state = hazeState,
                    style = hazeStyle
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(0.75.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                outlineColor.copy(alpha = 0.12f),
                                outlineColor.copy(alpha = 0.35f),
                                outlineColor.copy(alpha = 0.12f)
                            )
                        )
                    )
            )
        }
    }
}
