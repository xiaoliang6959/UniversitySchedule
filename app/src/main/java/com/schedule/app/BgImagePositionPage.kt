package com.schedule.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「图像位置编辑」页。
 *
 * 页面照主页面版式搭：**额头（标题栏）→ 通知提醒 → 周次胶囊 → 星期日期行 → 图区**，
 * 图区里不画课程格子（免得色块挡住图），用户就是对着这套版式调背景图的位置。
 *
 * 两处刻意的处理：
 *  1. 额头**只有一条**、与主页面同款同高，只是把汉堡键换成返回键、标题换成"图像位置编辑" ——
 *     之前是"标题栏 + 一条模拟顶栏"叠着，白占一大截高度。
 *  2. 底部工具条**半透明**、并且图区一直延伸到窗口最底（含系统导航栏区域），
 *     这样用户能参考到图的底部：不然工具条和小横条/三按键那一条把图挡住了，只能瞎调。
 *
 * 交互：双指捏合缩放 + 单指拖动（拖动即平移）。编辑期间只改内存状态，点「完成」才落盘，
 * 且只写当前形态那一份 —— 全屏/小窗/分屏三套方案互不影响。
 */
@Composable
fun BgImagePositionPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val mode = LocalWindowMode.current
    val smallWindow = LocalSmallWindow.current
    var fit by remember(mode) { mutableStateOf(BackgroundStore.fit(context, mode)) }
    val bmp = remember(AppC.bgVersion) { BackgroundStore.bitmap(context) }
    val imgW = (bmp?.width ?: 1).toFloat()
    val imgH = (bmp?.height ?: 1).toFloat()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // 3 按键模式下系统默认给导航栏加一层"对比度底"（navigationBarContrastEnforced），
    // 会把 App 内容盖成半透明灰白 —— 编辑页就参考不到图的最底部了。
    // 本页临时关掉它，离开自动恢复；手势模式（小横条）本来就没有这层。
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        val prev = if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null
        if (android.os.Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= 29 && prev != null) {
                window?.isNavigationBarContrastEnforced = prev
            }
        }
    }

    // 模拟用的真实数据：学校/专业、当前周与日期
    val dataPrefs = remember(context) { TestSchedule.dataStore(context) }
    val config = remember(dataPrefs) { loadConfigFromPrefs(dataPrefs) }
    val maxWeek = config.totalWeeks.coerceIn(1, TOTAL_WEEKS_MAX)
    val week = remember(config) { detectWeek(config).coerceIn(1, maxWeek) }

    // 三行（通知提醒 / 周次 / 星期）直接用主页面**同一批组件**，不再自己仿制 ——
    // 之前自己画的那份内边距、字号、行高都和主页面不一样，星期行还被固定高度裁掉了字的上半截。
    // 喂的是真实数据（课程/节次/节假日/提醒配置），所以显示的就是主页面上那一行。
    val courses = remember(dataPrefs) { loadCoursesFromPrefs(dataPrefs) }
    val slots = remember(dataPrefs) { loadSlots(dataPrefs) }
    val holidays = remember(dataPrefs) { loadHolidays(dataPrefs) }
    val reminderCfg = remember(context) {
        loadReminderConfig(context.getSharedPreferences(TestSchedule.REAL_PREFS, android.content.Context.MODE_PRIVATE))
    }
    // 编辑器不切周，给周次胶囊喂一组"静止态"的假手势状态（外观与主页面落定后完全一致）
    val chipDrag = remember { mutableFloatStateOf(0f) }
    val chipTap = remember { Animatable(1f) }
    // 通知提醒那一栏可以临时收起：不启用通知时它是一条无内容的占位，挡住图不好调
    var showNotify by remember { mutableStateOf(true) }

    /** 把像素位移换算成 dx/dy（比例），并夹在 -1~1 —— 保证图始终铺满、不会露底色 */
    fun panBy(delta: Offset) {
        val g = bgGeometry(viewSize.width.toFloat(), viewSize.height.toFloat(), imgW, imgH, fit.zoom)
        if (!g.valid) return
        fit = fit.copy(
            dx = if (g.slackX > 0f) (fit.dx + delta.x / g.slackX).coerceIn(-1f, 1f) else 0f,
            dy = if (g.slackY > 0f) (fit.dy + delta.y / g.slackY).coerceIn(-1f, 1f) else 0f,
        )
    }

    /** 以 anchor（视图坐标）为锚点缩放：手指按住的那块内容缩放前后停在原地 */
    fun zoomAround(anchor: Offset, newZoom: Float) {
        val vw = viewSize.width.toFloat(); val vh = viewSize.height.toFloat()
        val g0 = bgGeometry(vw, vh, imgW, imgH, fit.zoom)
        val g1 = bgGeometry(vw, vh, imgW, imgH, newZoom)
        if (!g0.valid || !g1.valid) { fit = fit.copy(zoom = newZoom); return }
        val left0 = (vw - g0.dispW) / 2f + fit.dx * g0.slackX
        val top0 = (vh - g0.dispH) / 2f + fit.dy * g0.slackY
        val u = ((anchor.x - left0) / g0.dispW).coerceIn(0f, 1f)
        val v = ((anchor.y - top0) / g0.dispH).coerceIn(0f, 1f)
        val left1 = anchor.x - u * g1.dispW
        val top1 = anchor.y - v * g1.dispH
        fit = BgFit(
            zoom = newZoom,
            dx = if (g1.slackX > 0f) ((left1 - (vw - g1.dispW) / 2f) / g1.slackX).coerceIn(-1f, 1f) else 0f,
            dy = if (g1.slackY > 0f) ((top1 - (vh - g1.dispH) / 2f) / g1.slackY).coerceIn(-1f, 1f) else 0f,
        )
    }

    Column(Modifier.fillMaxSize()) {
        // ---- 额头：与主页面同款（同色/同内边距/同状态栏避让），汉堡换成返回、标题换成"图像位置编辑" ----
        Surface(color = AppC.headerBlue, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = 4.dp, end = 16.dp,
                        top = if (smallWindow) 28.dp else 6.dp, bottom = 6.dp,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { tickHaptic(context, HapticKind.TAP); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppC.headerText)
                    }
                    Text("图像位置编辑", color = AppC.headerText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- 下面三行与主页面同一份组件：下一节课条 → 周次胶囊 → （图区顶部的）星期日期行 ----
        if (showNotify) {
            NextClassBar(courses, config, slots, reminderCfg, holidays, onClick = {})
        }
        WeekChipBar(
            currentWeek = week,
            maxShownWeek = maxWeek,
            dragState = chipDrag,
            pageW = 0f,
            chipTap = chipTap,
            chipTapFrom = week,
            onPick = {},
        )

        // ---- 图区：一直延伸到窗口最底（含系统导航栏那条），底部工具条半透明浮在上面 ----
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInput(mode) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // 单指拖动 = 平移；双指 = 捏合缩放（锚点=两指中点）+ 中点位移也当作平移。
                        // lastCount 用来识别"手指数变了"的那一帧：那一帧只重置基准、不产生位移，
                        // 否则第二根手指落下的瞬间中点会跳一大截，图会跟着闪一下。
                        var lastCentroid: Offset? = null
                        var lastDist = 0f
                        var lastCount = 0
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val c = pressed.fold(Offset.Zero) { a, p -> a + p.position } /
                                pressed.size.toFloat()
                            val sameGesture = lastCentroid != null && lastCount == pressed.size
                            if (pressed.size >= 2) {
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                if (sameGesture) {
                                    if (d > 1f && lastDist > 0f) {
                                        zoomAround(c, (fit.zoom * (d / lastDist))
                                            .coerceIn(BackgroundStore.MIN_ZOOM, BackgroundStore.MAX_ZOOM))
                                    }
                                    panBy(c - lastCentroid!!)
                                }
                                lastDist = d
                            } else {
                                if (sameGesture) panBy(c - lastCentroid!!)
                                lastDist = 0f
                            }
                            lastCentroid = c
                            lastCount = pressed.size
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
        ) {
            BackgroundImageLayer(fit, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(AppC.bgScrimColor))

            // 星期/日期行：用主页面那份真表头（自适应高度、带休/补角标、今天高亮），
            // 它自带 transparent-when-图-on 的表头底色，直接压在图上就是真实效果。
            WeekHeaderView(config, holidays, week)

            // 底部工具条：半透明（能透出图，便于参考底部），导航栏区域也由它兜住
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(AppC.card.copy(alpha = 0.78f))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    "当前形态：${mode.label}",
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppC.titleDark,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "全屏 / 小窗 / 分屏的位置各自独立保存，改这里只影响「${mode.label}」。",
                    fontSize = 11.sp, color = AppC.textMuted, lineHeight = 15.sp,
                )
                Text(
                    "双指捏合缩放 · 单指拖动图片",
                    fontSize = 11.sp, color = AppC.textMuted,
                )
                // 通知栏显隐：用「设置名 + 开关」而不是按钮。
                // 按钮那种写法绕不开一个别扭：文案若是动作（收起/展开），看着像在陈述当前状态；
                // 若是状态（收起/展开的形容词），点下去又与实际行为相反。
                // 开关没有这个问题 —— 文字只是设置名，状态由开关本身表示（和下课时也提醒同款）。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "显示通知栏",
                        fontSize = 12.sp, color = AppC.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = showNotify,
                        onCheckedChange = { tickHaptic(context, HapticKind.TOGGLE); showNotify = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = AppC.accentFill,
                            checkedThumbColor = readableOn(AppC.accentFill),
                        ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { tickHaptic(context, HapticKind.TAP); fit = BgFit.DEFAULT },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppC.accent),
                    ) { Text("重置", fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                    Button(
                        onClick = {
                            tickHaptic(context, HapticKind.TAP)
                            BackgroundStore.setFit(context, mode, fit)
                            AppC.bgFitVersion++
                            onBack()
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppC.accentFill,
                            contentColor = readableOn(AppC.accentFill),
                        ),
                    ) { Text("完成", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
