package com.schedule.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 全局语义色板：所有页面禁止再写死 Color(0xFF...)，一律取 AppC.xxx。
 * 切换深浅色 = 换 Palette 实例，所有读取处自动重组。
 * 课表色块（getCourseColor 调色板）是用户自选色，深浅色下保持不变。
 */
internal data class Palette(
    val bg: Color, val card: Color, val cardTint: Color,
    val headerBlue: Color, val accent: Color, val pickerBlue: Color,
    val success: Color, val danger: Color, val orange: Color, val amber: Color,
    val titleDark: Color, val textPrimary: Color, val textBody: Color, val textSecondary: Color,
    val textMuted: Color, val textFaint: Color, val textFaint2: Color, val textDisabled: Color,
    val textHint: Color, val textSlate: Color, val textSlate2: Color,
    val iconIdle: Color, val iconMuted: Color, val placeholder: Color,
    val chipGray: Color, val divider: Color, val gridAlt: Color, val gridLine: Color,
    val wheelBg: Color, val switchOff: Color, val stepDotIdle: Color, val stepBorder: Color,
    val cardBorder: Color, val iconCircleBg: Color, val gradientTop: Color, val todayBg: Color,
    val warnBg: Color, val warnText: Color, val successBg: Color, val successText: Color,
    val tipBg: Color, val tipText: Color, val emptyIcon: Color,
)

private val LightBase = Palette(
    // 页面/空隙底色比卡片(纯白)深一档：浅色模式下"白卡片贴在白底上"分不出层次，
    // 卡片之间的缝隙要有颜色差才看得出是卡片（深色模式本来就是这个关系）。
    bg = Color(0xFFEDF0F3), card = Color(0xFFFFFFFF), cardTint = Color(0xFFF5F8FB),
    headerBlue = Color(0xFF29B6F6), accent = Color(0xFF3498DB), pickerBlue = Color(0xFF5B8DEF),
    success = Color(0xFF27AE60), danger = Color(0xFFE74C3C), orange = Color(0xFFE67E22), amber = Color(0xFFF0AD4E),
    titleDark = Color(0xFF2C3E50), textPrimary = Color(0xFF333333), textBody = Color(0xFF666666), textSecondary = Color(0xFF555555),
    textMuted = Color(0xFF888888), textFaint = Color(0xFF999999), textFaint2 = Color(0xFFAAAAAA), textDisabled = Color(0xFFBBBBBB),
    textHint = Color(0xFFAAB4BF), textSlate = Color(0xFF6B7885), textSlate2 = Color(0xFF7A8899),
    iconIdle = Color(0xFFBDBDBD), iconMuted = Color(0xFF8A97A5), placeholder = Color(0xFFB8C2CC),
    // 底色变深后这几个"浅灰件"要跟着调，否则跟背景糊在一起看不见：
    // chipGray=胶囊/弹窗底、wheelBg=滚轮底、divider=分隔线、gridAlt=左侧时间列格子
    // gridLine=每半天的分隔条：浅色下与课表底色相同=不显示（课表要整块统一，不含条带）；深色保留
    chipGray = Color(0xFFF4F6F8), divider = Color(0xFFE3E8ED), gridAlt = Color(0xFFE4E9EE), gridLine = Color(0xFFFFFFFF),
    wheelBg = Color(0xFFEAEDF1), switchOff = Color(0xFFE8EAED), stepDotIdle = Color(0xFFD6E4F0), stepBorder = Color(0xFFE3E8EE),
    cardBorder = Color(0xFFE8EEF4), iconCircleBg = Color(0xFFEAF4FC), gradientTop = Color(0xFFD9EEFC), todayBg = Color(0xFFBBDEFB),
    warnBg = Color(0xFFFFF3CD), warnText = Color(0xFF856404), successBg = Color(0xFFE8F6EC), successText = Color(0xFF1E7B45),
    tipBg = Color(0xFFFFF8E1), tipText = Color(0xFF7A6A45), emptyIcon = Color(0xFFB8C7D6),
)

private val DarkBase = Palette(
    bg = Color(0xFF171A1D), card = Color(0xFF1F2429), cardTint = Color(0xFF232A31),
    headerBlue = Color(0xFF1F7FB5), accent = Color(0xFF4DA6E8), pickerBlue = Color(0xFF6E9BF5),
    success = Color(0xFF35B56B), danger = Color(0xFFF0655A), orange = Color(0xFFEE8E45), amber = Color(0xFFF0AD4E),
    titleDark = Color(0xFFE7EEF4), textPrimary = Color(0xFFE6E8EA), textBody = Color(0xFFC2C8CE), textSecondary = Color(0xFFC8CDD2),
    textMuted = Color(0xFF9BA4AD), textFaint = Color(0xFF8A939C), textFaint2 = Color(0xFF7E8790), textDisabled = Color(0xFF5F686F),
    textHint = Color(0xFF8A949E), textSlate = Color(0xFF97A3AF), textSlate2 = Color(0xFF90A0B0),
    iconIdle = Color(0xFF6B747C), iconMuted = Color(0xFF8A97A5), placeholder = Color(0xFF66707A),
    chipGray = Color(0xFF262B30), divider = Color(0xFF2A2F34), gridAlt = Color(0xFF191D21), gridLine = Color(0xFF262B30),
    wheelBg = Color(0xFF232830), switchOff = Color(0xFF3A4046), stepDotIdle = Color(0xFF33404C), stepBorder = Color(0xFF333A42),
    cardBorder = Color(0xFF2C343C), iconCircleBg = Color(0xFF1C3446), gradientTop = Color(0xFF102C40), todayBg = Color(0xFF2E5F88),
    warnBg = Color(0xFF3B2F12), warnText = Color(0xFFE5C56B), successBg = Color(0xFF12331F), successText = Color(0xFF5FC983),
    tipBg = Color(0xFF332A0E), tipText = Color(0xFFD6BE85), emptyIcon = Color(0xFF46525E),
)

object AppC {
    /** 0=跟随系统 1=浅色 2=深色（持久化到 prefs 的 dark_mode） */
    var mode by mutableIntStateOf(0)
    /** 开发者模式入口是否显示：关于页连点版本 8 次开启，页面内总开关可关（持久化 dev_mode） */
    var devModeOn by mutableStateOf(false)
    /** 主题色（外观设置里改，持久化到 prefs 的 theme_color） */
    var themeColor by mutableStateOf(DEFAULT_THEME)
    /** 课表背景图片是否启用（图片本身存在私有目录，关掉再开还是原来那张） */
    var bgImageOn by mutableStateOf(false)
    /** 背景图上那层"遮盖"的不透明度（0~1）：防止花哨图片把课表吃掉 */
    var bgScrim by mutableStateOf(BackgroundStore.DEFAULT_SCRIM_PCT / 100f)
    /** 换了背景图就 +1，让 UI 重新解码（文件路径固定，没法靠 key 观察变化） */
    var bgVersion by mutableIntStateOf(0)
    /** 背景图"位置方案"改了就 +1：图没变、只有位置变了，渲染层要重读方案 */
    var bgFitVersion by mutableIntStateOf(0)
    /**
     * 课程色块透明度（0~1）。**只在开了背景图时生效**；0 = 完全不透明（= 没有这个功能时的样子）。
     * 调高它可以让课程色块变淡，露出底下的背景图。
     */
    var bgBlockAlpha by mutableFloatStateOf(0f)
    internal var pal by mutableStateOf(LightBase)
    private var sysDark by mutableStateOf(false)

    val isDark: Boolean get() = if (mode == 0) sysDark else mode == 2

    internal fun syncSystemDark(dark: Boolean) { sysDark = dark }

    val bg get() = pal.bg
    val card get() = pal.card
    val cardTint get() = pal.cardTint
    val headerBlue get() = pal.headerBlue
    /** 顶栏文字/图标色：浅底自动黑字、深底自动白字（以前写死白字，才逼着把背景压暗） */
    val headerText: Color get() = readableOn(pal.headerBlue)
    /** 填充用强调色 = 主题色原样，跟色板上看到的那块完全一致 */
    val accentFill: Color get() = themeColor
    val accent get() = pal.accent
    /**
     * 课表格子底色：开了背景图就完全透明 —— 图片连同它上面那层"遮盖"由
     * [ScheduleBackground] 一次性画好，格子只负责文字。
     * 为什么不用"格子画半透明色"：翻页时三层课表在屏幕上重叠，半透明格子会**叠加**，
     * 动画中途整块课表突然变浓；透明格子没有这个问题，色带也不会在格子缝隙里断裂。
     */
    val tableCell: Color get() = if (bgImageOn) Color.Transparent else pal.card
    /** 表头（星期那行）底色，同上 */
    val tableHeader: Color get() = if (bgImageOn) Color.Transparent else pal.chipGray
    /** 每半天之间那条分隔条，同上 */
    val gridDivider: Color get() = if (bgImageOn) Color.Transparent else pal.gridLine
    /** 压在背景图上的那层遮盖：卡片色 + 遮盖不透明度 */
    val bgScrimColor: Color get() = pal.card.copy(alpha = bgScrim)

    /**
     * 课程色块的填充色：**只有开了背景图**且透明度 > 0 时才变淡，其余情况原样返回。
     *
     * 放在这里而不是调用处，是为了保证"全站色块只有这一个口子"——
     * 课表格子里的课程块都走它，将来别处再画课程块也不会漏掉这条规则。
     * 注意：课程名/教室这些**文字不受影响**（始终不透明），否则会连字都看不清。
     */
    fun blockFill(c: Color): Color =
        if (bgImageOn && bgBlockAlpha > 0f) c.copy(alpha = (1f - bgBlockAlpha).coerceIn(0f, 1f)) else c
    val pickerBlue get() = pal.pickerBlue
    val success get() = pal.success
    val danger get() = pal.danger
    val orange get() = pal.orange
    val amber get() = pal.amber
    val titleDark get() = pal.titleDark
    val textPrimary get() = pal.textPrimary
    val textBody get() = pal.textBody
    val textSecondary get() = pal.textSecondary
    val textMuted get() = pal.textMuted
    val textFaint get() = pal.textFaint
    val textFaint2 get() = pal.textFaint2
    val textDisabled get() = pal.textDisabled
    val textHint get() = pal.textHint
    val textSlate get() = pal.textSlate
    val textSlate2 get() = pal.textSlate2
    val iconIdle get() = pal.iconIdle
    val iconMuted get() = pal.iconMuted
    val placeholder get() = pal.placeholder
    val chipGray get() = pal.chipGray
    val divider get() = pal.divider
    val gridAlt get() = pal.gridAlt
    val gridLine get() = pal.gridLine
    val wheelBg get() = pal.wheelBg
    val switchOff get() = pal.switchOff
    val stepDotIdle get() = pal.stepDotIdle
    val stepBorder get() = pal.stepBorder
    val cardBorder get() = pal.cardBorder
    val iconCircleBg get() = pal.iconCircleBg
    val gradientTop get() = pal.gradientTop
    val todayBg get() = pal.todayBg
    val warnBg get() = pal.warnBg
    val warnText get() = pal.warnText
    val successBg get() = pal.successBg
    val successText get() = pal.successText
    val tipBg get() = pal.tipBg
    val tipText get() = pal.tipText
    val emptyIcon get() = pal.emptyIcon
}

/**
 * 把基础色板 + 主题色合成实际使用的色板。
 *
 * 只替换"本来就是主题色"的那几个角色（顶栏、强调色、选中态、当天高亮、圆形图标底…），
 * 语义色（警告黄/成功绿/危险红）保持原样，否则"危险"操作会被染成用户随手挑的颜色。
 *
 * 主题色是用户从调色盘任意挑的，所以派生时都要过一遍对比度：
 * 挑个浅黄，顶栏会被压暗到白字仍然清楚，强调色会压暗到画在白卡片上仍可读。
 */
private fun derive(base: Palette, theme: Color, dark: Boolean): Palette {
    // 填充用（按钮底、开关轨道）走 accentFill=原色；这里的 accent 只服务文字/描边，才需要压暗
    val accent = accentTextFor(theme, base.card)
    val header = headerVariant(theme, dark)
    return base.copy(
        headerBlue = header,
        accent = accent,
        pickerBlue = accentTextFor(theme, base.chipGray),
        stepDotIdle = faintVariant(theme, dark, if (dark) 0.5f else 0.78f),
        stepBorder = faintVariant(theme, dark, if (dark) 0.3f else 0.86f),
        cardBorder = faintVariant(theme, dark, if (dark) 0.4f else 0.86f),
        iconCircleBg = faintVariant(theme, dark, if (dark) 0.62f else 0.88f),
        gradientTop = faintVariant(theme, dark, if (dark) 0.55f else 0.82f),
        todayBg = faintVariant(theme, dark, if (dark) 0.42f else 0.74f),
    )
}

private fun schemeOf(base: Palette, theme: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    val p = derive(base, theme, dark)
    return if (dark) {
        darkColorScheme(
            primary = p.headerBlue, onPrimary = readableOn(p.headerBlue),
            primaryContainer = p.iconCircleBg, onPrimaryContainer = p.titleDark,
            background = p.bg, onBackground = p.textPrimary,
            surface = p.card, onSurface = p.textPrimary,
            surfaceVariant = p.chipGray, onSurfaceVariant = p.textMuted,
            surfaceContainer = p.card, surfaceContainerHigh = p.cardTint,
            outline = p.iconIdle, outlineVariant = p.divider,
            error = p.danger,
            secondaryContainer = p.iconCircleBg, onSecondaryContainer = p.titleDark,
        )
    } else {
        lightColorScheme(
            primary = p.headerBlue, onPrimary = readableOn(p.headerBlue),
            primaryContainer = p.iconCircleBg, onPrimaryContainer = p.titleDark,
            // 必须与 Palette 对齐：Scaffold 默认拿 colorScheme.background 当底色，
            // 不对齐的话子页面还是纯白，白卡片贴白底分不出层次
            background = p.bg, onBackground = p.textPrimary,
            surface = p.card, onSurface = p.textPrimary,
            surfaceVariant = p.chipGray, onSurfaceVariant = p.textMuted,
            surfaceContainer = p.card, surfaceContainerHigh = p.cardTint,
            outline = p.iconIdle, outlineVariant = p.divider,
            error = p.danger,
            secondaryContainer = p.iconCircleBg, onSecondaryContainer = p.titleDark,
        )
    }
}

/** 根主题：算出当前深浅 + 主题色 → 写入全局色板 → 提供 Material3 配色 */
@Composable
fun ScheduleTheme(content: @Composable () -> Unit) {
    AppC.syncSystemDark(isSystemInDarkTheme())
    val dark = AppC.isDark
    val base = if (dark) DarkBase else LightBase
    // Palette 是 data class：主题色没变时派生结果结构相等，写进 mutableStateOf 不会触发多余重组
    AppC.pal = derive(base, AppC.themeColor, dark)
    MaterialTheme(colorScheme = schemeOf(base, AppC.themeColor, dark), content = content)
}
