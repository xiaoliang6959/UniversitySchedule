package com.schedule.app

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

/**
 * 外观：课程色板 + 主题色 + 颜色工具。
 *
 * 这里刻意做成**唯一数据源**：课程颜色的备选、外观设置里主题色的备选都从
 * [COURSE_SWATCHES] 生成。以前两处各写一份，改了一处另一处还是老样子。
 */

/** 一个课程色块：背景 + 前景（文字）色 */
data class CourseSwatch(val bg: Color, val fg: Color, val name: String)

/**
 * 课程颜色备选（10 色）。
 *
 * 原来第 4 个 #66BB6A（绿）和第 9 个 #8BC34A（草绿）色相只差十几度，
 * 摆在同一行里几乎分不出来，第 9 个换成荧光绿 #B2FF59 —— 高明度高饱和，
 * 与黄、绿、青都拉开明显差距。
 */
val COURSE_SWATCHES: List<CourseSwatch> = listOf(
    CourseSwatch(Color(0xFFFF5252), Color(0xFFFFFFFF), "红"),
    CourseSwatch(Color(0xFFFF9100), Color(0xFFFFFFFF), "橙"),
    CourseSwatch(Color(0xFFFFD54F), Color(0xFF000000), "黄"),
    CourseSwatch(Color(0xFF66BB6A), Color(0xFFFFFFFF), "绿"),
    CourseSwatch(Color(0xFF26C6DA), Color(0xFFFFFFFF), "青"),
    CourseSwatch(Color(0xFF29B6F6), Color(0xFFFFFFFF), "蓝"),
    CourseSwatch(Color(0xFFAB47BC), Color(0xFFFFFFFF), "紫"),
    CourseSwatch(Color(0xFFEC407A), Color(0xFFFFFFFF), "玫红"),
    CourseSwatch(Color(0xFFB2FF59), Color(0xFF000000), "荧绿"),
    CourseSwatch(Color(0xFF26A69A), Color(0xFFFFFFFF), "蓝绿"),
)

/**
 * 出厂主题色 = 顶栏那块蓝 #29B6F6（也就是色板上带勾的那一格）。
 *
 * 以前这里写的是 #3498DB —— 那是旧的**强调色**（文字/描边用），不是顶栏色。
 * 结果点「恢复默认」得到的顶栏和出厂时看到的不是一个蓝，等于恢复错了。
 * #29B6F6 本身就在课程色板里，所以默认态会正常显示成"选中的预设色"，
 * 不会莫名其妙落在最后那个自定义调色盘格上。
 */
val DEFAULT_THEME = Color(0xFF29B6F6)

const val KEY_THEME_COLOR = "theme_color"

fun loadThemeColor(prefs: SharedPreferences): Color {
    if (!prefs.contains(KEY_THEME_COLOR)) return DEFAULT_THEME
    return Color(prefs.getInt(KEY_THEME_COLOR, DEFAULT_THEME.toArgb()))
}

fun saveThemeColor(prefs: SharedPreferences, c: Color) {
    prefs.edit().putInt(KEY_THEME_COLOR, c.toArgb()).apply()
}

/** 恢复默认主题色 = 直接删键，以后改默认值也不用管老数据里存了什么 */
fun clearThemeColor(prefs: SharedPreferences) {
    prefs.edit().remove(KEY_THEME_COLOR).apply()
}

/**
 * 课程颜色字段的编码：`#RRGGBB,#RRGGBB`（背景,前景）。
 * 与 Markdown 导入导出格式一致，所以自定义颜色天然能随文件带走。
 */
fun encodeCourseColor(bg: Color, fg: Color): String = "${hexOf(bg)},${hexOf(fg)}"

fun hexOf(c: Color): String = "#" + (c.toArgb() and 0x00FFFFFF).toString(16).padStart(6, '0').uppercase()

/** 解析 `#RRGGBB,#RRGGBB`；不是这个格式（含 "auto"、空串）返回 null */
fun parseCourseColor(s: String): Pair<Color, Color>? {
    val parts = s.split(',')
    if (parts.size != 2) return null
    val bg = parseHexColor(parts[0]) ?: return null
    val fg = parseHexColor(parts[1]) ?: return null
    return bg to fg
}

private fun parseHexColor(s: String): Color? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    val v = t.toIntOrNull(16) ?: return null
    return Color(0xFF000000L or v.toLong())
}

/**
 * 放在某个底色上该用黑字还是白字。
 * 白优先：这套 UI 原本就是"彩色顶栏 + 白字"，所以只要白字还够用（1.85，大字号级别）
 * 就继续用白，避免换了个主题色结果文字突然变黑；只有像亮黄这种白字彻底撑不住的才转黑。
 */
fun readableOn(bg: Color): Color {
    val lb = bg.luminance()
    val whiteContrast = 1.05 / (lb + 0.05)
    return if (whiteContrast >= 1.85) Color.White else Color(0xFF1A1A1A)
}

/** 向白色混合 t=0..1（变浅），用于派生"主题色的极淡版" */
fun mixWhite(c: Color, t: Float): Color =
    Color(lerp(c.red, 1f, t), lerp(c.green, 1f, t), lerp(c.blue, 1f, t), c.alpha)

/** 向黑色混合 t=0..1（变深） */
fun mixBlack(c: Color, t: Float): Color =
    Color(lerp(c.red, 0f, t), lerp(c.green, 0f, t), lerp(c.blue, 0f, t), c.alpha)

/** 向某个目标色混合 */
fun mixTo(c: Color, target: Color, t: Float): Color =
    Color(lerp(c.red, target.red, t), lerp(c.green, target.green, t), lerp(c.blue, target.blue, t), c.alpha)

private fun lerp(a: Float, b: Float, t: Float): Float = (a + (b - a) * t).coerceIn(0f, 1f)

/**
 * 顶栏底色 = **主题色本身，一个字都不改**。
 *
 * 这里以前会"为了容纳白字"把颜色一路压暗，结果挑亮黄得到橄榄色、挑橙得到焦糖色，
 * 跟色板上那块颜色完全对不上。现在改成背景保持原色，顶栏的文字/图标改用
 * [readableOn] 自动挑黑或白（见 AppC.headerText）。
 */
fun headerVariant(theme: Color, dark: Boolean): Color = theme

/** 主题色的"极淡版"，用作圆形图标底、渐变底等大面积弱色背景 */
fun faintVariant(theme: Color, dark: Boolean, strength: Float): Color =
    if (dark) mixBlack(theme, strength) else mixWhite(theme, strength)

/** WCAG 对比度 */
fun contrastOf(a: Color, b: Color): Double {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/**
 * 填充用强调色（按钮底、开关轨道、选中胶囊底）= 主题色本身，
 * 保证"色块上看到什么，应用里就是什么"。文字色交给 onPrimary=[readableOn] 自适应。
 */
fun accentFor(theme: Color, bg: Color): Color = theme

/**
 * 文字 / 描边用强调色：浅黄文字画在白卡片上确实看不见，所以**只有这个用途**按需压暗。
 * 阈值取 3.0（大字、粗体够用），比原来的 4.5 温和得多，不会把颜色改到认不出来。
 */
fun accentTextFor(theme: Color, bg: Color): Color {
    var c = theme
    var guard = 0
    while (contrastOf(c, bg) < 3.0 && guard < 12) {
        c = mixBlack(c, 0.1f)
        guard++
    }
    return c
}
