package com.schedule.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 开发者模式的总开关，以及"关掉它"该连带做什么。
 *
 * 规则（用户定的）：**关闭开发者模式后，页内的开关与设置全部恢复默认。**
 * 之所以要集中在这里，是因为这些子开关各自存自己的键 —— 以前各自为政就出过事故：
 * 开着测试课表时关掉开发者模式，测试课表仍在生效，而它的开关入口已经没了，
 * 用户既看不见也关不掉，只能以为"课表被改坏了"。
 *
 * 所以：页内**新增任何开关/设置，都要在 [resetDefaults] 里补一行恢复默认**，
 * 并且一律通过 [setEnabled] 改总开关，不要自己 putBoolean。
 */
object DeveloperSettings {

    /** 总开关的键（存在真实 prefs 里，和课表数据、提醒设置同文件） */
    const val KEY_DEV_MODE = "dev_mode"

    private fun realPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = realPrefs(context).getBoolean(KEY_DEV_MODE, false)

    /** 改总开关；关掉时连带把所有子设置恢复默认 */
    fun setEnabled(context: Context, on: Boolean) {
        realPrefs(context).edit().putBoolean(KEY_DEV_MODE, on).apply()
        AppC.devModeOn = on
        if (!on) resetDefaults(context)
    }

    /** 页内子设置恢复默认。新增开关记得在这里补一行。 */
    private fun resetDefaults(context: Context) {
        // 测试课表：翻回关闭并清掉测试数据文件（真实课表本来就没被写过）
        TestSchedule.disable(context)
    }

    /**
     * 启动时自愈：把"开发者模式已关、测试课表子开关却还存着 true"这种脏状态清掉。
     * 注意判断用的是 [TestSchedule.isStoredOn]（存储里的原始值）——
     * [TestSchedule.isEnabled] 已经以总开关为前提，用它判断永远不成立。
     * 只在真的不一致时才写盘，避免每次冷启动都白写一次 prefs。
     */
    fun healInconsistentState(context: Context) {
        if (!isEnabled(context) && TestSchedule.isStoredOn(context)) {
            TestSchedule.disable(context)
        }
    }
}
