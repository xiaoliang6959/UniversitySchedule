package com.schedule.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 背景图的位置方案（**无量纲**，所以跨分辨率、转屏、小窗改大小都仍然有效）。
 *
 * - [zoom]：以「铺满（可能裁切）」为基准 1.0，只放大不缩小 —— 缩小会露出页面底色，看着像 bug；
 * - [dx] / [dy]：可平移余量的比例，范围 -1~1，1 表示推到该方向的最边缘。
 *
 * 注意 zoom = 1 时，**长边方向仍有可平移余量**（那就是"被裁掉的部分"），
 * 所以只靠拖动就能把想露出来的部分挪进画面，不必先放大。
 */
data class BgFit(val zoom: Float = 1f, val dx: Float = 0f, val dy: Float = 0f) {

    fun serialize(): String = "$zoom,$dx,$dy"

    companion object {
        /** 出厂方案 = 铺满并居中，效果与没有这个功能时完全一致 */
        val DEFAULT = BgFit()

        fun parse(s: String?): BgFit {
            if (s.isNullOrBlank()) return DEFAULT
            val f = s.split(',')
            if (f.size != 3) return DEFAULT
            val z = f[0].toFloatOrNull() ?: return DEFAULT
            val x = f[1].toFloatOrNull() ?: return DEFAULT
            val y = f[2].toFloatOrNull() ?: return DEFAULT
            if (!z.isFinite() || !x.isFinite() || !y.isFinite()) return DEFAULT
            return BgFit(
                zoom = z.coerceIn(BackgroundStore.MIN_ZOOM, BackgroundStore.MAX_ZOOM),
                dx = x.coerceIn(-1f, 1f),
                dy = y.coerceIn(-1f, 1f),
            )
        }
    }
}

/**
 * 背景图的几何：显示尺寸 + 两个方向上还能平移多少（px）。
 *
 * 「铺满」用 cover 规则（取较大的缩放系数），再乘 [BgFit.zoom]。
 * 渲染与编辑页的手势换算**共用这一份公式**，保证所见即所得；
 * 同时也是"像素位移 ↔ dx/dy 比例"的唯一换算口径（拖动时除以 slack）。
 */
data class BgGeometry(val dispW: Float, val dispH: Float, val slackX: Float, val slackY: Float) {
    val valid: Boolean get() = dispW > 0f && dispH > 0f
}

fun bgGeometry(viewW: Float, viewH: Float, imgW: Float, imgH: Float, zoom: Float): BgGeometry {
    if (viewW <= 0f || viewH <= 0f || imgW <= 0f || imgH <= 0f) return BgGeometry(0f, 0f, 0f, 0f)
    val cover = maxOf(viewW / imgW, viewH / imgH)
    val dispW = imgW * cover * zoom
    val dispH = imgH * cover * zoom
    return BgGeometry(dispW, dispH, (dispW - viewW) / 2f, (dispH - viewH) / 2f)
}

/**
 * 课表背景图片。
 *
 * ## 为什么把图片复制进 App 私有目录
 * SAF 返回的 content URI 权限是**临时**的（进程一死就失效），而且用户换手机/清理后可能彻底读不到。
 * 直接存 URI 会出现"过几天背景图没了"。所以选中后立刻把字节复制成 `filesDir/bg_image`，
 * 之后只读本地文件；URI 本身不持久化保存。
 *
 * ## 关掉开关不删图
 * 用户要求：关了背景再打开，应该还是原来那张图。所以 [setEnabled] 只翻标志位，
 * 只有用户重新选图才覆盖文件。
 */
object BackgroundStore {

    const val KEY_BG_ON = "bg_image_on"
    const val KEY_BG_SCRIM = "bg_scrim_pct"
    /** 课程色块透明度（%）：只在开了背景图时生效 */
    const val KEY_BG_BLOCK_ALPHA = "bg_block_alpha_pct"

    /** 三种形态各存一份位置方案（全屏 / 小窗 / 分屏），互不影响 */
    const val KEY_FIT_FULL = "bg_fit_full"
    const val KEY_FIT_SMALL = "bg_fit_small"
    const val KEY_FIT_SPLIT = "bg_fit_split"

    /** 缩放下限 = 1（= 刚好铺满，不允许露底色），上限 3（再大就明显糊了） */
    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 3f

    /**
     * 遮盖不透明度的默认值（0~1）。
     *
     * 出厂取 20% —— 用户偏好"看得清图"胜过"看得清小字"：这个强度下图还有八成存在感，
     * 而课程名本来就画在不透明色块上不受影响，只有时间列/表头的小字会淡一点，
     * 觉得花再自己往上调。
     */
    const val DEFAULT_SCRIM_PCT = 20
    const val SCRIM_MIN_PCT = 0
    const val SCRIM_MAX_PCT = 90

    private const val FILE_NAME = "bg_image"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BG_ON, false) && file(context).exists()

    fun hasImage(context: Context): Boolean = file(context).exists()

    fun scrimPct(context: Context): Int =
        prefs(context).getInt(KEY_BG_SCRIM, DEFAULT_SCRIM_PCT).coerceIn(SCRIM_MIN_PCT, SCRIM_MAX_PCT)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BG_ON, on).apply()
    }

    fun setScrimPct(context: Context, pct: Int) {
        prefs(context).edit()
            .putInt(KEY_BG_SCRIM, pct.coerceIn(SCRIM_MIN_PCT, SCRIM_MAX_PCT)).apply()
    }

    fun resetScrim(context: Context) = setScrimPct(context, DEFAULT_SCRIM_PCT)

    /**
     * 课程色块透明度（%）。
     * 出厂 0 = 完全不透明，也就是没有这个功能时的样子；
     * 上限 90%（再高就几乎看不见色块，只剩字浮在图上，反而更难用）。
     */
    const val DEFAULT_BLOCK_ALPHA_PCT = 0
    const val BLOCK_ALPHA_MAX_PCT = 90

    fun blockAlphaPct(context: Context): Int =
        prefs(context).getInt(KEY_BG_BLOCK_ALPHA, DEFAULT_BLOCK_ALPHA_PCT)
            .coerceIn(0, BLOCK_ALPHA_MAX_PCT)

    fun setBlockAlphaPct(context: Context, pct: Int) {
        prefs(context).edit()
            .putInt(KEY_BG_BLOCK_ALPHA, pct.coerceIn(0, BLOCK_ALPHA_MAX_PCT)).apply()
    }

    fun resetBlockAlpha(context: Context) = setBlockAlphaPct(context, DEFAULT_BLOCK_ALPHA_PCT)

    // ---- 图片位置（三种形态各一份）----

    private fun fitKey(mode: WindowMode): String = when (mode) {
        WindowMode.FULL -> KEY_FIT_FULL
        WindowMode.SMALL -> KEY_FIT_SMALL
        WindowMode.SPLIT -> KEY_FIT_SPLIT
    }

    /**
     * 读某一形态的位置方案。没有存过（或存坏了）= [BgFit.DEFAULT]，
     * 而 `zoom=1, dx=dy=0` 正好等于"铺满并居中"——也就是加这个功能之前的样子，所以老用户零迁移。
     */
    fun fit(context: Context, mode: WindowMode): BgFit =
        BgFit.parse(prefs(context).getString(fitKey(mode), null))

    fun setFit(context: Context, mode: WindowMode, fit: BgFit) {
        prefs(context).edit().putString(fitKey(mode), fit.serialize()).apply()
    }

    fun resetFit(context: Context, mode: WindowMode) = setFit(context, mode, BgFit.DEFAULT)

    /** 把用户选中的图片复制进私有目录（覆盖旧的）。成功返回 true。 */
    fun importImage(context: Context, uri: Uri): Boolean {
        val dst = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return false
            // 先确认能解码，再落位：坏文件不该把原来能用的背景覆盖掉
            if (decode(tmp, 64) == null) {
                tmp.delete(); return false
            }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true); tmp.delete()
            }
            cached = null      // 让 UI 重新解码新图
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    // ---- 位图缓存：只在图片变化时重新解码 ----
    @Volatile
    private var cached: Bitmap? = null
    @Volatile
    private var cachedPath: String? = null

    /**
     * 解码背景图。按屏幕宽度降采样（大图直接解码会 OOM，而且显示尺寸也就这么大）。
     * 返回 null 表示没有图或文件已损坏。
     */
    fun bitmap(context: Context): Bitmap? {
        val f = file(context)
        if (!f.exists()) return null
        val path = "${f.absolutePath}:${f.length()}"
        cached?.let { if (cachedPath == path && !it.isRecycled) return it }
        val bmp = decode(f, 1440) ?: return null
        cached = bmp
        cachedPath = path
        return bmp
    }

    private fun decode(f: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) > maxSide || bounds.outHeight / (sample * 2) > maxSide) {
                sample *= 2
            }
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }
}
