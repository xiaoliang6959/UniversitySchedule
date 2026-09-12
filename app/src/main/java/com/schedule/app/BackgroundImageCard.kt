package com.schedule.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 外观设置里的「课表背景图片」卡片。
 *
 * 交互约定：
 *  - 开关关掉时**不删图**，只停止显示 —— 再打开还是用户上次选的那张，不用重新找图。
 *  - 想换图直接点缩略图重选即可，新图覆盖旧图。
 *  - 没有图时开关一打开就自动弹选图，省一步操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundImageCard(onEditPosition: () -> Unit = {}) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(AppC.bgImageOn) }
    var scrim by remember { mutableStateOf(AppC.bgScrim * 100f) }
    // 课程色块透明度（%）：只在开背景图时生效，关掉图时 AppC.blockFill 会原样返回实心色
    var blockAlpha by remember { mutableStateOf(AppC.bgBlockAlpha * 100f) }
    var version by remember { mutableIntStateOf(0) }   // 换图后刷新缩略图

    fun turnOff() {
        on = false
        BackgroundStore.setEnabled(ctx, false)
        AppC.bgImageOn = false
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            // 取消选图：手里没有可用图片就必须把开关退回关闭 ——
            // 开着图又没有图，格子是透明的，底下空无一物，字会直接裸在页面底色上
            if (!BackgroundStore.hasImage(ctx)) turnOff()
            return@rememberLauncherForActivityResult
        }
        if (BackgroundStore.importImage(ctx, uri)) {
            AppC.bgVersion++
            version++
        } else {
            Toast.makeText(ctx, "这张图片无法读取，换一张试试", Toast.LENGTH_SHORT).show()
            if (!BackgroundStore.hasImage(ctx)) turnOff()
        }
    }
    fun pickImage() = picker.launch(
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    )

    Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("课表背景图片", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("给课表垫一张图。关掉开关图片会保留，下次打开还是这张。",
                        fontSize = 11.sp, color = AppC.textMuted)
                }
                Switch(
                    checked = on,
                    onCheckedChange = { checked ->
                        tickHaptic(ctx, HapticKind.TOGGLE)
                        on = checked
                        BackgroundStore.setEnabled(ctx, checked)
                        AppC.bgImageOn = checked
                        if (checked && !BackgroundStore.hasImage(ctx)) pickImage()
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = AppC.accentFill,
                        checkedThumbColor = readableOn(AppC.accentFill),
                    ),
                )
            }

            if (on) {
                Spacer(Modifier.height(14.dp))
                // 预览放最上面：调下面滑杆时眼睛正好看得到效果（不然只能猜）
                BackgroundMiniPreview()
                Spacer(Modifier.height(14.dp))
                // ---- 选图 ----
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(width = 96.dp, height = 64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppC.chipGray)
                            .border(1.dp, AppC.cardBorder, RoundedCornerShape(8.dp))
                            .clickable { tickHaptic(ctx, HapticKind.TAP); pickImage() },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = remember(version, AppC.bgVersion) { BackgroundStore.bitmap(ctx) }
                        if (bmp != null) {
                            Image(bitmap = bmp.asImageBitmap(), contentDescription = "当前背景图",
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Default.AddPhotoAlternate, "选择图片",
                                tint = AppC.textMuted, modifier = Modifier.size(22.dp))
                        }
                    }
                    Column {
                        TextButton(onClick = { tickHaptic(ctx, HapticKind.TAP); pickImage() }, contentPadding = PaddingValues(0.dp)) {
                            Text(if (BackgroundStore.hasImage(ctx)) "更换图片" else "选择图片",
                                fontSize = 13.sp, color = AppC.accent)
                        }
                        Text("图片会复制到应用内部保存，之后不依赖相册。",
                            fontSize = 11.sp, color = AppC.textMuted)
                    }
                }

                Spacer(Modifier.height(16.dp))
                // ---- 遮盖透明度 ----
                val pct = scrim.toInt().coerceIn(BackgroundStore.SCRIM_MIN_PCT, BackgroundStore.SCRIM_MAX_PCT)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("遮盖强度", fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Text("$pct%", fontSize = 13.sp, color = AppC.accent)
                }
                Text("在图片上压一层底色：图片太花、课表看不清时调高它。", fontSize = 11.sp,
                    color = AppC.textMuted)
                BgAlphaSlider(
                    value = scrim,
                    min = BackgroundStore.SCRIM_MIN_PCT.toFloat(),
                    max = BackgroundStore.SCRIM_MAX_PCT.toFloat(),
                    onChange = { v ->
                        scrim = v
                        AppC.bgScrim = scrim / 100f
                    },
                    onFinished = { tickHaptic(ctx, HapticKind.SLIDE); BackgroundStore.setScrimPct(ctx, scrim.toInt()) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            tickHaptic(ctx, HapticKind.TAP)
                            scrim = BackgroundStore.DEFAULT_SCRIM_PCT.toFloat()
                            AppC.bgScrim = scrim / 100f
                            BackgroundStore.resetScrim(ctx)
                        },
                        // 已经是默认值时按钮置灰，避免"点了没反应"的错觉
                        enabled = pct != BackgroundStore.DEFAULT_SCRIM_PCT,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("恢复默认（${BackgroundStore.DEFAULT_SCRIM_PCT}%）", fontSize = 13.sp,
                            color = if (pct != BackgroundStore.DEFAULT_SCRIM_PCT) AppC.accent else AppC.textDisabled)
                    }
                    if (pct == BackgroundStore.DEFAULT_SCRIM_PCT) {
                        Icon(Icons.Default.Check, "已是默认", tint = AppC.textMuted,
                            modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
                // ---- 课程色块透明度 ----
                val blockPct = blockAlpha.toInt().coerceIn(0, BackgroundStore.BLOCK_ALPHA_MAX_PCT)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("课程色块透明度", fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Text("$blockPct%", fontSize = 13.sp, color = AppC.accent)
                }
                Text("调高它课程色块会变淡，露出底下的背景图。关掉背景图时这项不生效（色块恢复实心）。",
                    fontSize = 11.sp, color = AppC.textMuted, lineHeight = 15.sp)
                BgAlphaSlider(
                    value = blockAlpha,
                    min = 0f,
                    max = BackgroundStore.BLOCK_ALPHA_MAX_PCT.toFloat(),
                    onChange = { v ->
                        blockAlpha = v
                        AppC.bgBlockAlpha = blockAlpha / 100f
                    },
                    onFinished = { tickHaptic(ctx, HapticKind.SLIDE); BackgroundStore.setBlockAlphaPct(ctx, blockAlpha.toInt()) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            tickHaptic(ctx, HapticKind.TAP)
                            blockAlpha = BackgroundStore.DEFAULT_BLOCK_ALPHA_PCT.toFloat()
                            AppC.bgBlockAlpha = 0f
                            BackgroundStore.resetBlockAlpha(ctx)
                        },
                        enabled = blockPct != BackgroundStore.DEFAULT_BLOCK_ALPHA_PCT,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text("恢复默认（${BackgroundStore.DEFAULT_BLOCK_ALPHA_PCT}%）", fontSize = 13.sp,
                            color = if (blockPct != BackgroundStore.DEFAULT_BLOCK_ALPHA_PCT) AppC.accent else AppC.textDisabled)
                    }
                    if (blockPct == BackgroundStore.DEFAULT_BLOCK_ALPHA_PCT) {
                        Icon(Icons.Default.Check, "已是默认", tint = AppC.textMuted,
                            modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = AppC.divider)
                Spacer(Modifier.height(4.dp))
                // ---- 图像位置编辑 ----
                // 入口上写明当前形态 + 三形态互相独立：不然用户不知道自己调的是哪一套，
                // 换了窗口发现位置不对还会以为是 bug。
                val mode = LocalWindowMode.current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = BackgroundStore.hasImage(ctx)) { tickHaptic(ctx, HapticKind.TAP); onEditPosition() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("图像位置编辑", fontWeight = FontWeight.Medium, fontSize = 14.sp,
                            color = if (BackgroundStore.hasImage(ctx)) AppC.textPrimary else AppC.textDisabled)
                        Text(
                            "当前形态：${mode.label} · 全屏/小窗/分屏的位置各自独立保存" +
                                if (BackgroundStore.hasImage(ctx)) "" else "（先选一张图片）",
                            fontSize = 11.sp, color = AppC.textMuted, lineHeight = 15.sp,
                        )
                    }
                    Icon(Icons.Default.ChevronRight, "打开", tint = AppC.iconIdle)
                }
            }
        }
    }
}

/**
 * 背景图卡片里用的滑杆：圆点滑块 + 细圆角轨道。
 *
 * Material3 默认的滑块是一根竖条，定位起来看不清边界，所以自绘。
 * 抽出来是因为卡片里有两处要用（遮盖强度、课程色块透明度），不能再各写一份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BgAlphaSlider(
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val hapticCtx = LocalContext.current
    // 拖动时的连续反馈：上次"哒"过的整数值。取值范围本身就是百分数（0~90 / 0~100），
    // 所以每变化 1 个单位 = 每 1% 震一次。只在跨过整数时才震 —— 同一格内来回蹭不重复响。
    var lastTick by remember { mutableIntStateOf(value.roundToInt()) }
    Slider(
        value = value,
        onValueChange = { v ->
            val cv = v.coerceIn(min, max)
            val iv = cv.roundToInt()
            if (iv != lastTick) {
                lastTick = iv
                tickHaptic(hapticCtx, HapticKind.DRAG)
            }
            onChange(cv)
        },
        onValueChangeFinished = onFinished,
        valueRange = min..max,
        modifier = Modifier.fillMaxWidth().height(30.dp),
        track = { st ->
            val frac = ((st.value - min) / (max - min)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(AppC.switchOff)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(AppC.accentFill, CircleShape))
            }
        },
        thumb = {
            Box(Modifier.size(22.dp).background(AppC.accentFill, CircleShape)
                .border(3.dp, AppC.card, CircleShape))
        },
    )
}
