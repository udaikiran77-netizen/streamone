package com.streamone.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Shared Vega-style motion system. Vega (React Native + Reanimated) leans on
// in-out cubic timings around 300-650ms and springy selection feedback; these
// specs recreate that feel in Compose and are used across the whole UI so
// every animation moves the same way.
// ---------------------------------------------------------------------------

/** Bouncy spring for selection changes (tab icon, hero dots, chips). */
val VegaSelectionSpring: SpringSpec<Float> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/** Snappier spring for press feedback — quick dip, gentle settle. */
val VegaPressSpring: SpringSpec<Float> = spring(
    dampingRatio = 0.55f,
    stiffness = Spring.StiffnessMedium
)

/** Smooth spring for size changes (dot pill width etc.). */
val VegaDpSpring: SpringSpec<androidx.compose.ui.unit.Dp> = spring(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow
)

/** The standard Vega fade/slide timing: ~350ms in-out cubic. */
fun <T> vegaTweenSpec(durationMillis: Int = 350, delayMillis: Int = 0): TweenSpec<T> =
    tween(
        durationMillis = durationMillis,
        delayMillis = delayMillis,
        easing = FastOutSlowInEasing
    )

// ---------------------------------------------------------------------------
// Press feedback: a subtle scale dip while pressed, replacing the Material
// ripple (Vega/RN has no ripple — elements physically react instead).
// ---------------------------------------------------------------------------

/**
 * Clickable with Vega press feedback: the element scales down to
 * [pressedScale] on a spring while touched. Ripple is disabled so the scale
 * is the only feedback, like in Vega.
 */
@Composable
fun Modifier.pressClickable(
    pressedScale: Float = 0.97f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = VegaPressSpring,
        label = "pressScale"
    )
    return this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Just the animated press scale, for elements that manage their own click
 * handling (e.g. Material buttons with their own interaction source).
 */
@Composable
fun Modifier.animatedPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = VegaPressSpring,
        label = "pressScaleShared"
    )
    return this.scale(scale)
}

// ---------------------------------------------------------------------------
// Entrance: content fades in while rising a few dp, staggered by index —
// the way Vega's sections settle in when a screen loads.
// ---------------------------------------------------------------------------

@Composable
fun StaggeredIn(
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val delayMs = index.coerceIn(0, 10) * 55
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = vegaTweenSpec(380, delayMs),
        label = "staggerAlpha"
    )
    val shift by animateDpAsState(
        targetValue = if (entered) 0.dp else 18.dp,
        animationSpec = vegaTweenSpec(380, delayMs),
        label = "staggerShift"
    )
    Box(
        modifier = modifier
            .alpha(alpha)
            .offset(y = shift)
    ) {
        content()
    }
}
