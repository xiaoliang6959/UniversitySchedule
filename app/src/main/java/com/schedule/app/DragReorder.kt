package com.schedule.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 长按拖动排序的共享状态。
 * index=当前被拖项所在的槽位号；offset=拖拽位移；heights=各槽位实测高度；
 * slotAnim=槽位号→补位动画（被交换顶替的卡片从旧位置滑入新位置，消除瞬移闪现）。
 */
class DragState {
    var index by mutableIntStateOf(-1)
    var offset by mutableFloatStateOf(0f)
    val heights = mutableStateMapOf<Int, Int>()
    val slotAnim = mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>()
    lateinit var scope: CoroutineScope
    // 注意：reset 不清 slotAnim —— 松手时可能仍有补位动画在飞，清掉会让卡片跳变；
    // 残留的 Animatable 归零后值为 0，无副作用，下次交换会被整体替换。
    fun reset() { index = -1; offset = 0f }
}

@Composable
fun rememberDragState(): DragState {
    val sc = rememberCoroutineScope()
    return remember { DragState().apply { scope = sc } }
}

/**
 * LazyColumn 列表项长按拖动排序（不依赖第三方库，按槽位索引记账）。
 *
 * 长按后本项跟随手指纵向偏移（graphicsLayer，不触发重组）；偏移越过一个行距时
 * 回调 onSwap(槽位, ±1) 让外层交换数据，被拖项的槽位号 +1/-1、偏移量同步扣减
 * 一个行距，支持连续跨多行拖动。被顶到空位的卡片用 Animatable 从旧位置补一个
 * 短滑入动画（160ms），不再瞬间闪现。
 *
 * 列表须用默认的按位复用（不传 key），这样交换数据后各项状态仍留在原槽位上。
 */
@Composable
fun Modifier.dragToReorder(
    state: DragState,
    index: Int,
    count: Int,
    extraPx: Float = 0f,
    onSwap: (Int, Int) -> Unit,
): Modifier {
    val swap by rememberUpdatedState(onSwap)
    val dragging = state.index == index
    return this
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer {
            if (dragging) {
                translationY = state.offset
                scaleX = 1.03f
                scaleY = 1.03f
            } else {
                translationY = state.slotAnim[index]?.value ?: 0f
            }
        }
        .onSizeChanged { state.heights[index] = it.height }
        .pointerInput(index) {
            detectDragGesturesAfterLongPress(
                onDragStart = { state.index = index; state.offset = 0f },
                onDrag = { change, amount ->
                    change.consume()
                    var i = state.index
                    var off = state.offset + amount.y
                    fun stepOf(k: Int) = ((state.heights[k] ?: 0) + extraPx).coerceAtLeast(1f)
                    // 交换后被顶到槽位 i 的卡片：从被拖项原来的位置滑回槽位 i。
                    // 关键：Animatable 必须"带着初始偏移"同步放进 map —— 若先建 0f
                    // 再在协程里 snapTo，交换后的第一帧会按 0f 把卡片画在新位置（闪现），
                    // 下一帧才跳回旧位置起滑，观感就是"突然置顶再上移"。
                    // 临界阻尼弹簧（dampingRatio=1 不回弹）：低刚度放慢整体节奏，
                    // 起步柔和、尾段自然减速滑行到位，观感更优雅
                    fun slideInto(slot: Int, fromPx: Float) {
                        val a = Animatable(fromPx)
                        state.slotAnim[slot] = a
                        state.scope.launch {
                            a.animateTo(0f, spring(dampingRatio = 1f, stiffness = 130f))
                        }
                    }
                    while (off <= -stepOf(i) && i > 0) {
                        val step = stepOf(i)
                        swap(i, -1)
                        slideInto(i, -step)
                        i--; state.index = i; off += stepOf(i)
                    }
                    while (off >= stepOf(i) && i < count - 1) {
                        val step = stepOf(i)
                        swap(i, 1)
                        slideInto(i, step)
                        i++; state.index = i; off -= stepOf(i)
                    }
                    state.offset = off
                },
                onDragEnd = { state.reset() },
                onDragCancel = { state.reset() },
            )
        }
}
