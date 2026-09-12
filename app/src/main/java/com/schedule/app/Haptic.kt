package com.schedule.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 触觉反馈的**分类**。每类对应一种交互，可在「设置 → 触觉反馈」里单独开关。
 *
 * 分类的意义：有人只想要"点一下有回应"，不希望拖滑块时一路哒哒哒；
 * 也有人反过来。拆开让用户自己选，比一个总开关更好用。
 */
enum class HapticKind(
    /** SharedPreferences 的键，改了会让老用户设置失效，别乱动 */
    val key: String,
    val label: String,
    val desc: String,
) {
    TAP("haptic_tap", "点击", "按钮、菜单项、列表项、返回"),
    TOGGLE("haptic_toggle", "开关切换", "开关、深色模式切换"),
    SELECT("haptic_select", "选择", "颜色、选项、周次、滚轮档位"),
    SLIDE("haptic_slide", "滑动与翻页", "滑块松手、左右翻周，以及在首/末周或课表顶/底继续滑的边界提示"),
    DRAG("haptic_drag", "拖动滑块", "左右拖动滑块时连续反馈（每 1% 一次）"),
    LONG_PRESS("haptic_long_press", "长按", "长按重排、长按删除"),
    ;

    companion object {
        /** 显式用 values()：enumEntries 要 Kotlin 1.9+，这里保持兼容 */
        fun all(): List<HapticKind> = values().toList()
    }
}

/** 取当前窗口的振动器（API 31 起必须走 VibratorManager） */
private fun vibratorOf(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

/**
 * 设备是否"细腻触感"（线性马达 / 谐振马达，能做出干脆的 tick 手感）。
 *
 * ## 为什么要判这个
 * 转子马达（ERM，老机器/低端机）做不出"哒"这种短促反馈：它要靠偏心块转起来，
 * 起停都有惯性，震起来是"嗡——"的一坨，密集的 tick 反而变成持续嗡嗡响，很难受。
 * 所以**线性马达默认开、转子马达默认关**，用户想开也能在设置里手动打开。
 *
 * ## 判据
 * 拿得到振动器，且它支持振幅控制 [Vibrator.hasAmplitudeControl]。
 * 线性/谐振马达能按强度分级振动故支持；转子马达只能开或关，一般不支持。
 * 这条从 API 26 起就有，不必分版本判断。
 *
 * 任何异常一律按"转子"处理（= 默认关闭），宁可没震动也不要嗡嗡响。
 */
fun hasRichHaptics(context: Context): Boolean {
    // 判据：马达支持"振幅控制"。
    // 线性马达 / 谐振马达能按强度分级振动，故支持；转子马达（ERM，靠偏心块转动）
    // 只能开或关，一般不支持 —— 这条足以把两类区分开，且 API 26 起就有，无需分版本。
    var rich = false
    try {
        val v = vibratorOf(context)
        if (v != null) rich = v.hasAmplitudeControl()
    } catch (e: Throwable) {
        rich = false
    }
    return rich
}

/**
 * 触觉反馈设置的唯一存储（跟着其它"设置类"一起放在 [TestSchedule.REAL_PREFS]）。
 *
 * ## 默认值随马达类型走
 * 从没设置过时，默认值 = [hasRichHaptics] 的结果（线性马达开、转子马达关）。
 * 检测结果缓存到进程变量里，避免每次点击都去查询系统。
 * 用户在设置页手动改过之后，以存下来的为准，不再受默认值影响。
 */
object HapticStore {

    private const val KEY_MASTER = "haptic_master"

    /** 进程内缓存：默认值只探测一次 */
    private var defaultMasterCache: Boolean? = null

    private fun defaultMaster(context: Context): Boolean =
        defaultMasterCache ?: hasRichHaptics(context).also { defaultMasterCache = it }

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)

    /** 总开关。关了之后所有分类一律不震（分类开关仍各自保留，重新打开总开关即恢复） */
    fun masterOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER, defaultMaster(context))

    fun setMaster(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER, on).apply()
    }

    fun kindOn(context: Context, kind: HapticKind): Boolean =
        prefs(context).getBoolean(kind.key, true)

    fun setKind(context: Context, kind: HapticKind, on: Boolean) {
        prefs(context).edit().putBoolean(kind.key, on).apply()
    }
}

/**
 * 轻量触觉反馈：短促"哒"一下，用线性马达的 tick 原语。
 *
 * ## 调用约定
 * 在任何 onClick 里 `tickHaptic(hapticCtx, HapticKind.X)`。**必须先检查开关**，
 * 所以这里第一行就是读 [HapticStore]，关掉时直接 return，连马达都不唤醒。
 *
 * ## 必须声明 VIBRATE 权限
 * `Vibrator.vibrate()` 无论播放自定义波形还是预定义效果，都会校验
 * android.permission.VIBRATE —— 清单里没声明就会抛 SecurityException 直接崩 App。
 * VIBRATE 是"普通权限"，安装即授予，不会弹窗骚扰用户。
 *
 * ## 为什么整个包在 runCatching 里
 * 触觉反馈只是锦上添花；国产 ROM（MIUI 等）可能额外拦截、设备也可能根本没马达。
 * 任何异常都在这里吞掉，**绝不能因为"想震一下"把点击流程打断**。
 *
 * ## [force] 的用途
 * 设置页里"打开 / 关闭某个开关"时的那一下确认震动要**无条件发出**：
 * 关掉总开关时若仍先查开关，这一下就会被自己拦掉，用户点"关"却没有任何回应。
 * 所以这类"对设置本身的反馈"传 force = true 跳过开关判断。
 *
 * ## 版本
 * - API 31+：VibratorManager.defaultVibrator
 * - API 29-30：VIBRATOR_SERVICE 拿 Vibrator（EFFECT_TICK 常量本身 29+）
 * - API 26-28：没有预定义 tick → 静默跳过
 */
fun tickHaptic(context: Context, kind: HapticKind = HapticKind.TAP, force: Boolean = false) {
    // 先过开关：关着的话什么都不做（force = true 例外，见上）
    if (!force) {
        if (!HapticStore.masterOn(context)) return
        if (!HapticStore.kindOn(context, kind)) return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    runCatching {
        @Suppress("MissingPermission")
        vibratorOf(context)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }
}
