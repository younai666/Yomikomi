package io.github.yomikomi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Short response, softer settling; no overshoot. Compose observes Android's animator duration scale. */
internal object YomiMotion {
    const val press = 90
    const val change = 180
    const val page = 240
    const val exit = 140
    const val progress = 240
    val ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

@Composable
internal fun Modifier.quietFeedback(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    pressedScale: Float = 1f,
    disabledAlpha: Float = 0.6f,
    tint: Color = yomiColors.ink,
    surface: Color = Color.Transparent,
): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val duration = if (pressed) YomiMotion.press else YomiMotion.change
    val scale = animateFloatAsState(if (enabled && pressed) pressedScale else 1f,
        tween(duration, easing = YomiMotion.ease), label = "press_scale")
    val opacity = animateFloatAsState(if (enabled) 1f else disabledAlpha,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "enabled")
    val wash by animateColorAsState(tint.copy(alpha = when {
        !enabled -> 0f
        pressed -> 0.06f
        hovered -> 0.035f
        else -> 0f
    }), tween(duration, easing = YomiMotion.ease), label = "press_surface")
    val outline by animateColorAsState(if (enabled && focused) yomiColors.accent else Color.Transparent,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "focus")
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = opacity.value }
        .background(surface, shape).background(wash, shape).border(1.5.dp, outline, shape)
}

@Composable
internal fun Modifier.quietClickable(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    pressedScale: Float = 1f,
    disabledAlpha: Float = 0.6f,
    tint: Color = yomiColors.ink,
    surface: Color = Color.Transparent,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return quietFeedback(interaction, enabled, shape, pressedScale, disabledAlpha, tint, surface)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
}

/** A stable content key lets numeric progress refresh without repeatedly fading its label. */
@Composable
internal fun <T> QuietContent(
    target: T,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.CenterStart,
    contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(targetState = target, modifier = modifier, contentAlignment = alignment,
        contentKey = contentKey, label = "content",
        transitionSpec = {
            (fadeIn(tween(YomiMotion.change, easing = YomiMotion.ease)) togetherWith
                fadeOut(tween(YomiMotion.press)))
                .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(YomiMotion.change, easing = YomiMotion.ease) }))
        }) { content(it) }
}
