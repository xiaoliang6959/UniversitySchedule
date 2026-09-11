package com.schedule.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 背景图本体（按位置方案绘制，不含遮盖）。
 *
 * ## 为什么自己算几何、而不是用 ContentScale.Crop
 * Crop 会把"超出部分"从**源图**上裁掉，之后无论怎么缩放/平移都再也看不到那部分了 ——
 * 而用户恰恰想通过拖动把被裁掉的部分挪进画面。这里改成始终把**整张图**按
 * "铺满并放大 zoom 倍"画出来，位置由 dx/dy 决定：图永远铺满（不会露底色），
 * 但超出画面的部分只是"没显示"，随时可以拖回来。
 *
 * 几何公式在 [bgGeometry] 里，与编辑页的手势换算共用同一份，保证所见即所得。
 * 绘制放在 `drawWithCache` 里：读的是绘制期，改方案不会触发重组。
 */
@Composable
fun BackgroundImageLayer(fit: BgFit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // 转屏/改窗口大小后要按新尺寸重新降采样解码，所以屏幕尺寸也进 key
    val cfg = LocalConfiguration.current
    val bmp = remember(AppC.bgVersion, cfg.screenWidthDp, cfg.screenHeightDp) {
        BackgroundStore.bitmap(ctx)
    }
    if (bmp == null) {
        // 没图/解码失败：退回不透明卡片色，绝不让文字裸在页面底色上
        Box(modifier.background(AppC.card))
        return
    }
    val img: ImageBitmap = remember(bmp) { bmp.asImageBitmap() }
    val imgW = bmp.width.toFloat()
    val imgH = bmp.height.toFloat()

    Box(
        modifier
            .clipToBounds()
            .drawWithCache {
                val g = bgGeometry(size.width, size.height, imgW, imgH, fit.zoom)
                if (!g.valid) return@drawWithCache onDrawBehind { }
                val left = (size.width - g.dispW) / 2f + fit.dx * g.slackX
                val top = (size.height - g.dispH) / 2f + fit.dy * g.slackY
                val dstOffset = IntOffset(left.roundToInt(), top.roundToInt())
                val dstSize = IntSize(g.dispW.roundToInt(), g.dispH.roundToInt())
                onDrawBehind { drawImage(img, dstOffset = dstOffset, dstSize = dstSize) }
            }
    )
}

/**
 * 课表区的背景图（表头 + 格子那一整块的底层）。
 *
 * ## 为什么画在最底下、而不是给每个格子铺图
 * 翻页时 WeekPager 里同时挂着三层课表（上周/本周/下周）。如果"图 + 半透明格子"跟着层走，
 * 两层交叠的那段动画里格子会叠着画、遮盖会叠着压，整块课表会突然变浓再变淡，很脏。
 * 现在图与遮盖都是**一份静态层**，翻页时只有前景的文字和课程色块在动 ——
 * 像一叠卡片从一张照片上滑过，图始终不动。
 *
 * ## 遮盖也放在这里
 * 同理，遮盖是整块一层，所以格子必须是全透明（见 AppC.tableCell），不然等于压了两遍。
 *
 * ## 位置按窗口形态分别取
 * 全屏 / 小窗 / 分屏各存一份（见 [BackgroundStore.fit]），互不影响 ——
 * 一处调好换到另一处往往就不适用了，分开存省得来回折腾。
 */
@Composable
fun ScheduleBackground() {
    if (!AppC.bgImageOn) return
    val ctx = LocalContext.current
    val mode = LocalWindowMode.current
    val fit = remember(AppC.bgFitVersion, mode) { BackgroundStore.fit(ctx, mode) }

    // 图没铺满的边角露出页面底色（正常情况下铺满，这里只是兜底）
    Box(Modifier.fillMaxSize().background(AppC.bg)) {
        BackgroundImageLayer(fit, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(AppC.bgScrimColor))
    }
}

/**
 * 外观设置里的迷你预览：真的把图 + 遮盖画出来，再压一行表头和几节课，
 * 拖滑杆时当场看清"图会不会吃掉课表"。画的规则和课表里完全一致
 * （格子/表头用 AppC.tableCell / tableHeader，开图时为透明），不是假的示意图。
 */
@Composable
fun BackgroundMiniPreview() {
    Column(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp))
            .background(AppC.bg),
    ) {
        Box(Modifier.fillMaxWidth()) {
            ScheduleBackground()
            Column {
                // 表头行
                Box(Modifier.fillMaxWidth().height(20.dp).background(AppC.tableHeader)) {
                    Row(Modifier.fillMaxSize()) {
                        Spacer(Modifier.width(28.dp))
                        for (d in listOf("一", "二", "三", "四", "五", "六", "日")) {
                            Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                                fontSize = 9.sp, color = AppC.textSecondary)
                        }
                    }
                }
                // 两行格子 + 一门课
                for (r in 0..1) {
                    Row(Modifier.fillMaxWidth().height(36.dp)) {
                        Box(Modifier.width(28.dp).fillMaxHeight().background(AppC.tableCell),
                            contentAlignment = Alignment.Center) {
                            Text(if (r == 0) "1" else "2", fontSize = 8.sp, color = AppC.textMuted)
                        }
                        for (c in 0..6) {
                            Box(Modifier.weight(1f).fillMaxHeight().background(AppC.tableCell)) {
                                if (r == 0 && c == 1) {
                                    Box(Modifier.padding(2.dp).fillMaxSize()
                                        .background(COURSE_SWATCHES[5].bg, RoundedCornerShape(4.dp)),
                                        contentAlignment = Alignment.Center) {
                                        Text("高等数学", fontSize = 8.sp, color = COURSE_SWATCHES[5].fg)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
