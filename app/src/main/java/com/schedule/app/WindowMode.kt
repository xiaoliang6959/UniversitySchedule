package com.schedule.app

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前窗口形态。
 *
 * 为什么要分成三种：背景图在不同形态下的可视区域不同（全屏整屏、小窗一小块、分屏半屏），
 * 用户在一处调好的位置换到另一处往往就不对了。**各形态的位置方案互相独立保存**，
 * 就不会出现"切回小窗发现图又跑偏了"的来回折腾。
 *
 * 判定由 MainActivity 统一做（OEM 差异大，散在各页面判会不一致），经 [LocalWindowMode] 下发。
 */
enum class WindowMode {
    /** 全屏 */
    FULL,

    /** 悬浮小窗（freeform） */
    SMALL,

    /** 分屏（上下或左右） */
    SPLIT;

    /** 给用户看的中文名 */
    val label: String
        get() = when (this) {
            FULL -> "全屏"
            SMALL -> "小窗"
            SPLIT -> "分屏"
        }
}

internal val LocalWindowMode = staticCompositionLocalOf { WindowMode.FULL }
