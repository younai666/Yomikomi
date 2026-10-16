package io.github.yomikomi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp

internal enum class Glyph { Back, Settings, Swap, Down, Next, Plus, Minus, Close, Check }

@Composable
internal fun LineIcon(glyph: Glyph, modifier: Modifier = Modifier, color: Color = yomiColors.ink) {
    Canvas(modifier.size(22.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
                color, androidx.compose.ui.geometry.Offset(x1, y1),
                androidx.compose.ui.geometry.Offset(x2, y2), 1.65f, StrokeCap.Round,
            )
            fun path(vararg points: Pair<Float, Float>) {
                drawPath(Path().apply {
                    points.forEachIndexed { index, (x, y) ->
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }, color, style = Stroke(1.65f, cap = StrokeCap.Round))
            }
            when (glyph) {
                Glyph.Back -> path(14f to 5f, 7f to 12f, 14f to 19f)
                Glyph.Settings -> {
                    line(3f, 7f, 8f, 7f); line(13f, 7f, 21f, 7f)
                    line(3f, 17f, 13f, 17f); line(18f, 17f, 21f, 17f)
                    drawCircle(color, 2.5f, androidx.compose.ui.geometry.Offset(10.5f, 7f), style = Stroke(1.65f))
                    drawCircle(color, 2.5f, androidx.compose.ui.geometry.Offset(15.5f, 17f), style = Stroke(1.65f))
                }
                Glyph.Swap -> {
                    path(3f to 7f, 20f to 7f, 16f to 3f)
                    path(21f to 17f, 4f to 17f, 8f to 21f)
                }
                Glyph.Down -> path(7f to 10f, 12f to 15f, 17f to 10f)
                Glyph.Next -> path(10f to 6f, 16f to 12f, 10f to 18f)
                Glyph.Plus -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
                Glyph.Minus -> line(5f, 12f, 19f, 12f)
                Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                Glyph.Check -> path(5f to 12f, 10f to 17f, 19f to 7f)
            }
        }
    }
}

@Composable
internal fun QuietIconButton(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier.size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .quietClickable(enabled = enabled, pressedScale = 0.96f, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(glyph, Modifier.size(20.dp))
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        color = yomiColors.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 0.5.dp, color = yomiColors.line)
}

@Composable
internal fun PreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.quietClickable(shape = RoundedCornerShape(8.dp), onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), color = yomiColors.ink, fontSize = 16.sp)
        if (trailing != null) trailing() else {
            value?.let { label -> QuietContent(label, Modifier.widthIn(max = 150.dp), Alignment.CenterEnd) {
                Text(it, color = yomiColors.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                LineIcon(Glyph.Next, Modifier.size(16.dp), yomiColors.muted)
            }
        }
    }
}

@Composable
internal fun QuietSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val track by animateColorAsState(if (checked) yomiColors.accent else yomiColors.muted,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "track")
    val offset = animateFloatAsState(if (checked) 19f else 3f,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "thumb")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp))
            .quietFeedback(interaction, shape = RoundedCornerShape(8.dp))
            .toggleable(checked, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = onCheckedChange).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = yomiColors.ink, fontSize = 16.sp)
        Box(Modifier.size(44.dp, 28.dp).clip(CircleShape).background(track)) {
            Box(Modifier.offset { IntOffset(offset.value.dp.roundToPx(), 3.dp.roundToPx()) }
                .size(22.dp).background(yomiColors.surface, CircleShape))
        }
    }
}

@Composable
internal fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, stateKey: Any = text) {
    val surface by animateColorAsState(if (primary) yomiColors.accent else yomiColors.inset,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "button_surface")
    val ink by animateColorAsState(if (primary) yomiColors.surface else yomiColors.ink,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "button_ink")
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .quietClickable(enabled, RoundedCornerShape(10.dp), pressedScale = 0.985f,
                tint = if (primary) yomiColors.background else yomiColors.ink, surface = surface, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { QuietContent(stateKey to text, alignment = Alignment.Center, contentKey = { it.first }) {
        Text(it.second, color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    } }
}

@Composable
internal fun Status(text: String, active: Boolean = false) {
    val color by animateColorAsState(if (active) yomiColors.accent else yomiColors.muted,
        tween(YomiMotion.change, easing = YomiMotion.ease), label = "status")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        QuietContent(text) { Text(it, color = color, fontSize = 12.sp) }
    }
}
