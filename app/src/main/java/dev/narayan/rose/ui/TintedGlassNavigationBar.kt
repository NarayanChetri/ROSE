package dev.narayan.rose.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Renders a frosted/tinted glass background behind the system navigation bar,
 * ONLY when button-based navigation (e.g. 3-button or 2-button navigation) is active.
 *
 * In gesture navigation, this composable renders nothing, keeping the system bar
 * completely transparent for an immersive edge-to-edge experience.
 */
@Composable
fun BoxScope.TintedGlassNavigationBar(
    modifier: Modifier = Modifier
) {
    val layoutDirection = LocalLayoutDirection.current
    val navBarInsets = WindowInsets.navigationBars.asPaddingValues()
    val navBarBottom = navBarInsets.calculateBottomPadding()
    val navBarEnd = navBarInsets.calculateEndPadding(layoutDirection)
    val navBarStart = navBarInsets.calculateStartPadding(layoutDirection)

    val hasBottom = navBarBottom > 0.dp
    val hasEnd = navBarEnd > 0.dp
    val hasStart = navBarStart > 0.dp

    if (!hasBottom && !hasEnd && !hasStart) return

    // Tint color: dark translucent smoke tint in dark/amoled mode, clean light translucent tint in light mode
    val backgroundColor = MaterialTheme.colorScheme.background
    val isDark = backgroundColor.luminance() < 0.5f

    val glassBackground = Brush.verticalGradient(
        colors = if (isDark) {
            listOf(
                backgroundColor.copy(alpha = 0.72f),
                backgroundColor.copy(alpha = 0.90f)
            )
        } else {
            listOf(
                backgroundColor.copy(alpha = 0.82f),
                backgroundColor.copy(alpha = 0.95f)
            )
        }
    )

    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    if (hasBottom) {
        Box(
            modifier = modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBarBottom)
                .background(glassBackground)
        ) {
            // Subtle frosted glass top border / specular highlight line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.75.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                outlineColor.copy(alpha = 0.10f),
                                outlineColor.copy(alpha = 0.32f),
                                outlineColor.copy(alpha = 0.10f)
                            )
                        )
                    )
            )
        }
    } else if (hasEnd) {
        Box(
            modifier = modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(navBarEnd)
                .background(
                    Brush.horizontalGradient(
                        colors = if (isDark) {
                            listOf(backgroundColor.copy(alpha = 0.72f), backgroundColor.copy(alpha = 0.90f))
                        } else {
                            listOf(backgroundColor.copy(alpha = 0.82f), backgroundColor.copy(alpha = 0.95f))
                        }
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(0.75.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                outlineColor.copy(alpha = 0.10f),
                                outlineColor.copy(alpha = 0.32f),
                                outlineColor.copy(alpha = 0.10f)
                            )
                        )
                    )
            )
        }
    } else if (hasStart) {
        Box(
            modifier = modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(navBarStart)
                .background(
                    Brush.horizontalGradient(
                        colors = if (isDark) {
                            listOf(backgroundColor.copy(alpha = 0.90f), backgroundColor.copy(alpha = 0.72f))
                        } else {
                            listOf(backgroundColor.copy(alpha = 0.95f), backgroundColor.copy(alpha = 0.82f))
                        }
                    )
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
                                outlineColor.copy(alpha = 0.10f),
                                outlineColor.copy(alpha = 0.32f),
                                outlineColor.copy(alpha = 0.10f)
                            )
                        )
                    )
            )
        }
    }
}
