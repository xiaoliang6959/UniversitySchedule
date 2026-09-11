package com.schedule.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自绘调色盘对话框（HSV）。
 *
 * 不用 Material3 的 ColorPicker：它带实验性注解、视觉风格偏重，
 * 而这里只要"挑一个颜色"这一件事，自己画两块面板反而干净可控。
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Color) -> Unit,
) {
    val start = remember(initial) { colorToHsv(initial) }
    var hue by remember(initial) { mutableFloatStateOf(start.first) }
    var sat by remember(initial) { mutableFloatStateOf(start.second) }
    var value by remember(initial) { mutableFloatStateOf(start.third) }
    val picked = hsvToColor(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppC.card,
        shape = RoundedCornerShape(16.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 饱和度 × 明度
                SatValPanel(hue, sat, value) { s, v -> sat = s; value = v }
                // 色相
                HuePanel(hue) { h -> hue = h }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                            .background(picked)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(hexOf(picked), fontSize = 14.sp, color = AppC.textSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text("确定", color = AppC.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = AppC.textMuted) }
        },
    )
}

@Composable
private fun SatValPanel(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(hsvToColor(hue, 1f, 1f))
            .dragInBox { nx, ny -> onChange(nx, 1f - ny) }
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cx = (sat * size.width).coerceIn(0f, size.width)
            val cy = ((1f - value) * size.height).coerceIn(0f, size.height)
            drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                Color.Black.copy(alpha = 0.35f), radius = 11.dp.toPx(),
                center = Offset(cx, cy),
            )
            drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(cx, cy))
        }
    }
}

@Composable
private fun HuePanel(hue: Float, onChange: (Float) -> Unit) {
    val spectrum = listOf(
        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
    )
    Box(
        Modifier.fillMaxWidth().height(26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Brush.horizontalGradient(spectrum))
            .dragInBox { nx, _ -> onChange(nx * 360f) }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val cx = (hue / 360f * size.width).coerceIn(0f, size.width)
            val cy = size.height / 2f
            drawCircle(Color.White, radius = 11.dp.toPx(), center = Offset(cx, cy))
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = 11.dp.toPx(), center = Offset(cx, cy))
            drawCircle(hsvToColor(hue, 1f, 1f), radius = 8.dp.toPx(), center = Offset(cx, cy))
        }
    }
}

/** 把指针位置换算成框内归一化坐标（0..1），按下即生效、拖拽持续跟随 */
private fun Modifier.dragInBox(onChange: (Float, Float) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        fun emit(p: Offset) {
            val w = size.width.toFloat(); val h = size.height.toFloat()
            onChange(
                (p.x / w).coerceIn(0f, 1f),
                (p.y / h).coerceIn(0f, 1f),
            )
        }
        emit(down.position)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.pressed } ?: break
            emit(change.position)
            change.consume()
        }
    }
}

// ---------------- HSV ↔ Color ----------------

private fun colorToHsv(color: Color): Triple<Float, Float, Float> {
    val r = color.red; val g = color.green; val b = color.blue
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val h = when {
        d == 0f -> 0f
        mx == r -> 60f * (((g - b) / d) % 2f)
        mx == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return Triple((h + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
}

private fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hh = (((h % 360f) + 360f) % 360f) / 60f
    val c = v * s
    val x = c * (1f - abs(hh % 2f - 1f))
    val m = v - c
    val (r, g, b) = when (hh.toInt().coerceIn(0, 5)) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}
