package com.schedule.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 抽屉宽度（dp） */
const val DRAWER_WIDTH_DP = 280f
/** 开关补间时长（ms）：与切周同一套对称曲线，观感统一 */
private const val DRAWER_ANIM_MS = 260
/** 遮罩不透明度：与 M3 的 ScrimTokens 一致（黑 32%） */
private const val SCRIM_ALPHA = 0.32f
/** 松手时"顺势打开"的速度门槛（px/s，向右为正） */
private const val FLING_OPEN = 320f

/**
 * 侧边栏（抽屉）状态。
 *
 * ## 为什么不用 material3 的 `DrawerState`
 * 它只暴露 `open() / close() / animateTo(DrawerValue)` 和**只读**的 offset —— 没有任何接口能连续设置
 * 偏移量（内部那颗 `anchoredDraggableState` 是 internal）。所以"跟手"在它身上做不到：边缘手势
 * 只能喊一声 `open()`，抽屉自己按固定时长滑完，手指划多快、划到哪儿都不作数。
 * 三大金刚键下尤其明显 —— 系统不会截走边缘滑动，手指刚动整块就弹出来了。
 *
 * ## 这里的约定（与切周那套完全同构）
 * `progress` 是**唯一渲染源**：拖拽期间由手势逐帧写，松手后由 Animatable 逐帧回写。
 * 绝不把进度展开成参数传进组合，避免"每帧重组整页"。
 */
@Stable
class EdgeDrawerState {
    /** 0 = 全关，1 = 全开 */
    var progress by mutableFloatStateOf(0f)

    /** 目标态：跨页面返回时靠它保持开合（子页面里没有抽屉，状态留在上层） */
    var targetOpen by mutableStateOf(false)

    /**
     * 遮罩是否该出现在组合树里。
     *
     * **不是**按 progress 判断的 —— 那样拖拽期间每帧都会重组 EdgeDrawer 整棵树
     * （progress 是逐帧写的）。这里只在"交互开始 / 关闭动画结束"这两个边界翻转；
     * 遮罩的浓淡由绘制期读 progress 决定（graphicsLayer.alpha），一帧都不抖。
     */
    var scrimVisible by mutableStateOf(false)

    internal val anim = Animatable(0f)

    val isOpen: Boolean get() = targetOpen

    /**
     * 开合**真的发生翻转**时回调（true = 打开，false = 关闭）。
     *
     * 由创建方注入，不在这里拿 Context —— 这个类只是个 UI 状态，不该依赖 Android 环境。
     * 放在这里是因为 [animateTo] 是唯一的开合入口：汉堡键、边缘手势、点遮罩全走它，
     * 挂一处就全覆盖，不会漏掉某条路径。
     */
    var onOpenStateChanged: ((Boolean) -> Unit)? = null

    /** 补间到目标（唯一的动画入口，调用方自己 launch） */
    suspend fun animateTo(target: Float) {
        val wasOpen = targetOpen
        targetOpen = target > 0.5f
        // 只有"关→开"或"开→关"才提示：已经在同一态里再补间一次（或拖到一半又弹回原态）不该响
        if (targetOpen != wasOpen) onOpenStateChanged?.invoke(targetOpen)
        scrimVisible = true
        anim.snapTo(progress)
        anim.animateTo(target, tween(DRAWER_ANIM_MS, easing = FlipEasing)) { progress = value }
        // 关到位之后才撤掉遮罩：否则收起动画跑到一半，背景会"啪"地一下不暗了
        if (!targetOpen) scrimVisible = false
    }

    /**
     * 松手后该去哪儿。顺序很重要：
     *  1. **手上的速度优先** —— 快速左滑即使只滑了很小一段也要收起（以前只写了"向右甩=开"，
     *     左滑没速度分支，位移又不到一半 → 被判成"留在过半"，于是又弹回打开状态，
     *     表现就是"能跟手慢慢收、但快速左滑收不回去"）；
     *  2. 没有明显速度时，才按位移过半来判。
     */
    fun targetFor(velocity: Float): Float = when {
        velocity > FLING_OPEN -> 1f
        velocity < -FLING_OPEN -> 0f
        progress > 0.5f -> 1f
        else -> 0f
    }
}

/**
 * 跟手侧边栏。
 *
 * 交互：
 *  - 左边缘右滑 → `progress` 跟指尖走（由 MainPage 的手势裁决层写），松手按进度/速度补间收尾；
 *  - 抽屉开着时左滑面板或左滑遮罩 → 同样跟手往回收；
 *  - 点遮罩 → 补间关闭（原 M3 行为）；
 *  - 汉堡按钮 → 补间打开。
 *
 * 注意：遮罩同时**吃掉水平拖拽**，否则抽屉开着时还能在后面划课表切周。
 */
@Composable
fun EdgeDrawer(
    state: EdgeDrawerState,
    drawer: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val widthPx = with(LocalDensity.current) { DRAWER_WIDTH_DP.dp.toPx() }

    // 进场（含从子页面返回）：进度直接落到目标态，不补间 —— 否则每次返回都滑一遍
    LaunchedEffect(Unit) {
        state.progress = if (state.targetOpen) 1f else 0f
        state.scrimVisible = state.targetOpen
        state.anim.snapTo(state.progress)
    }

    /** 手势/点按收尾：统一走这里，避免多处各写一套 */
    fun settle(velocity: Float) {
        scope.launch { state.animateTo(state.targetFor(velocity)) }
    }

    Box(Modifier.fillMaxSize()) {
        content()

        if (state.scrimVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 浓淡跟着进度走，但读的是绘制期（不触发重组）
                    .graphicsLayer { alpha = state.progress }
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    // 点空白 = 关；在空白上左滑 = 跟手收。
                    // 这两件事必须写在**同一个** pointerInput 里自己裁决：
                    // 之前是 detectTapGestures + draggable 两个修饰符叠着，draggable 会先拿到 down，
                    // 导致 detectTapGestures 永远等不到按下 → "点右边空白关不掉"。
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.isConsumed) return@awaitEachGesture   // 面板自己先接了，别抢
                            var last = down.position
                            var total = 0f
                            var vx = 0f
                            var lastT = down.uptimeMillis
                            var dragged = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                val d = ch.position - last
                                last = ch.position
                                total += d.getDistance()
                                val dt = (ch.uptimeMillis - lastT).coerceAtLeast(1L).toFloat()
                                lastT = ch.uptimeMillis
                                if (dt >= 8f && dragged) vx = vx * 0.5f + (d.x / dt * 1000f) * 0.5f
                                if (!dragged && total > slop) dragged = true
                                if (dragged) {
                                    state.progress = (state.progress + d.x / widthPx).coerceIn(0f, 1f)
                                    ch.consume()
                                }
                            }
                            if (!dragged) {
                                // 只是点了一下空白 → 直接收起
                                scope.launch { state.animateTo(0f) }
                            } else {
                                settle(vx)
                            }
                        }
                    }
            )
        }

        Surface(
            color = AppC.card,
            // M3 抽屉的形状：靠内侧两个角 16dp 圆角
            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
            modifier = Modifier
                .width(DRAWER_WIDTH_DP.dp)
                .fillMaxHeight()
                // 平移只看渲染结点（不重组、不重新布局）
                .graphicsLayer { translationX = -(1f - state.progress) * size.width }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d ->
                        state.progress = (state.progress + d / widthPx).coerceIn(0f, 1f)
                    },
                    onDragStopped = { v -> settle(v) },
                ),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    // 与 M3 的抽屉一致：系统栏内边距只留在"上/下/内侧"
                    .windowInsetsPadding(
                        WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)
                    ),
                content = drawer,
            )
        }
    }
}
