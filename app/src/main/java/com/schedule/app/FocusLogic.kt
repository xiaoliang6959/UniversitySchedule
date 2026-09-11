package com.schedule.app

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 焦点通知的纯判定逻辑（不碰 Android API，可单独跑 JVM 测试）。
 * 通知的发送/闹钟见 FocusNotifier。
 */

/** 焦点通知最多提前多久出现（分钟）——再大的自定义提前量只影响弹窗提醒，不会让常驻条挂满一天 */
const val FOCUS_ADVANCE_CAP = 120

/** 上课/下课后的"刚打铃"窗口（秒）：这段时间常驻条显示「上课了」/「下课了」 */
const val FOCUS_START_ACK_SECONDS = 15L
const val FOCUS_END_ACK_SECONDS = 15L
/** 下课后的总尾巴（秒）：不管后面有没有连堂都保留这么久，到点才收条 */
const val FOCUS_TAIL_SECONDS = 30L

/**
 * 连堂块：同一天、在时间表上紧邻（[ClassEvent.backToBackWith]）的若干节课合并成一段。
 * 焦点通知的存活区间 = 第一节课上课时间 - 提前量 → 最后一节课下课时间。
 * 时间表上不相邻、或隔天的课各自成块（下课完就不常驻了）。
 */
data class ClassBlock(val date: LocalDate, val events: List<ClassEvent>) {
    init { require(events.isNotEmpty()) }

    val first: ClassEvent get() = events.first()
    val last: ClassEvent get() = events.last()

    /** 焦点通知该出现的最早时刻 */
    fun windowStart(advanceMinutes: Int): LocalDateTime =
        first.startDateTime.minusMinutes(advanceMinutes.toLong())

    /** 最后一节课下课时刻 */
    val endAt: LocalDateTime get() = LocalDateTime.of(date, last.endTime)

    /**
     * 是否在"该挂条"的时间范围内。注意尾巴：[endAt] 之后还留 [FOCUS_TAIL_SECONDS] 秒——
     * 用来显示"下课了 / 下一节"，到点才收条。
     */
    fun activeAt(now: LocalDateTime, advanceMinutes: Int): Boolean =
        !now.isBefore(windowStart(advanceMinutes)) && !now.isAfter(endAt.plusSeconds(FOCUS_TAIL_SECONDS))

    /** 去重/关闭记录用的稳定标识（以日期开头，方便按天清理） */
    fun key(): String =
        "$date|${first.course.name}|${first.startTime}|${last.endTime}|${events.size}"
}

/**
 * 五个阶段（时间顺序）：
 *   PRE       课前        「还有 X 分钟上课」
 *   START_ACK 上课后前15秒 「上课了」
 *   IN        课上        「还有 X 分钟下课」
 *   END_ACK   下课后前15秒 「下课了」
 *   END_NEXT  下课后15~30秒 有连堂 → 显示下一节课信息；没有 → 继续「下课了」，30 秒到点收条
 */
enum class FocusPhase { PRE, START_ACK, IN, END_ACK, END_NEXT }

/** 焦点通知在某时刻该显示什么 */
data class FocusState(
    val block: ClassBlock,
    val event: ClassEvent,
    val phase: FocusPhase,
    /** 剩余分钟（课前=距上课，课上=距下课），已向上取整 */
    val minutesLeft: Int,
    /** 当前这节在连堂块里的序号（1-based） */
    val indexInBlock: Int,
    /** 仅 [FocusPhase.END_NEXT] 用：连堂的下一节；null = 后面没有了 */
    val next: ClassEvent? = null,
)

/** 把课程事件按"时间表上紧邻"合并成连堂块 */
fun buildClassBlocks(events: List<ClassEvent>): List<ClassBlock> {
    if (events.isEmpty()) return emptyList()
    val sorted = events.sortedWith(compareBy({ it.date }, { it.startTime }, { it.slotStartIndex }))
    val blocks = ArrayList<ClassBlock>()
    var cur = ArrayList<ClassEvent>()
    for (e in sorted) {
        if (cur.isNotEmpty() && !cur.last().backToBackWith(e)) {
            blocks.add(ClassBlock(cur.first().date, cur.toList()))
            cur = ArrayList()
        }
        cur.add(e)
    }
    if (cur.isNotEmpty()) blocks.add(ClassBlock(cur.first().date, cur.toList()))
    return blocks
}

/** 剩余分钟数向上取整（4分59秒算5分钟），已过归零 */
fun ceilMinutesTo(target: LocalDateTime, now: LocalDateTime): Int {
    val sec = Duration.between(now, target).seconds
    return if (sec <= 0) 0 else ((sec + 59) / 60).toInt()
}

/**
 * 焦点通知的提前量：现在由焦点模式自己那个值决定（"提前多久开始挂"），
 * 仍旧封顶 [FOCUS_ADVANCE_CAP]，免得有人设成"提前几小时"，常驻条挂一整天。
 */
fun focusAdvance(reminder: ReminderConfig): Int =
    reminder.focusAdvanceMinutes.coerceIn(0, FOCUS_ADVANCE_CAP)

/** 焦点通知内容；不在该块的窗口内（含 30 秒下课尾巴）返回 null */
fun focusStateFor(block: ClassBlock, now: LocalDateTime, advanceMinutes: Int): FocusState? {
    if (!block.activeAt(now, advanceMinutes)) return null
    for ((i, e) in block.events.withIndex()) {
        val start = e.startDateTime
        val end = LocalDateTime.of(e.date, e.endTime)
        val ackDone = start.plusSeconds(FOCUS_START_ACK_SECONDS)          // 上课+15s
        val endAckDone = end.plusSeconds(FOCUS_END_ACK_SECONDS)           // 下课+15s
        val tailDone = end.plusSeconds(FOCUS_TAIL_SECONDS)                // 下课+30s
        if (now.isBefore(start)) {
            return FocusState(block, e, FocusPhase.PRE, ceilMinutesTo(start, now), i + 1)
        }
        if (now.isBefore(ackDone)) {
            return FocusState(block, e, FocusPhase.START_ACK, 0, i + 1)
        }
        if (now.isBefore(end)) {
            return FocusState(block, e, FocusPhase.IN, ceilMinutesTo(end, now), i + 1)
        }
        // 刚下课：前 15 秒「下课了」，15~30 秒若有连堂就显示下一节（没有就继续「下课了」）
        if (now.isBefore(endAckDone)) {
            return FocusState(block, e, FocusPhase.END_ACK, 0, i + 1)
        }
        if (now.isBefore(tailDone)) {
            return FocusState(block, e, FocusPhase.END_NEXT, 0, i + 1, next = block.events.getOrNull(i + 1))
        }
        // 尾巴过完 → 看下一节（连堂的后半段会走到这里，变成"还有 X 分钟上课"）
    }
    return null
}

/**
 * 该块里 [now] 之后最近的一个"状态切换点"（上课+15s / 下课+15s / 下课+30s）。
 * 秒级边界靠它把闹钟排准：光靠整分心跳的话，「下课了」要挂到下一分钟才消失。
 */
fun ClassBlock.nextPhaseChangeAfter(now: LocalDateTime): LocalDateTime? {
    val times = ArrayList<LocalDateTime>()
    for (e in events) {
        val s = e.startDateTime
        val en = LocalDateTime.of(e.date, e.endTime)
        times.add(s.plusSeconds(FOCUS_START_ACK_SECONDS))
        times.add(en.plusSeconds(FOCUS_END_ACK_SECONDS))
        times.add(en.plusSeconds(FOCUS_TAIL_SECONDS))
    }
    return times.filter { it.isAfter(now) }.minOrNull()
}

/**
 * 连排 [count] 个心跳点（互不依赖）。以前只排"下一个"一个，整条链全靠它递归续命：
 * 熄屏被系统合并/限制时一断就全断（实测：锁屏几分钟后剩余时间不再刷新，只能等亮屏才更正）。
 */
fun nextFocusWakeTimes(
    blocks: List<ClassBlock>,
    now: LocalDateTime,
    advanceMinutes: Int,
    dismissed: Set<String>,
    count: Int,
): List<LocalDateTime> {
    val out = ArrayList<LocalDateTime>(count)
    var cursor = now
    repeat(count) {
        val t = nextFocusWakeAt(blocks, cursor, advanceMinutes, dismissed) ?: return out
        out.add(t)
        cursor = t
    }
    return out
}

/** 两个时间取较早者（可空） */
fun earlierOf(a: LocalDateTime?, b: LocalDateTime): LocalDateTime? =
    if (a == null || b.isBefore(a)) b else a

/**
 * 下一次值得被叫醒的时刻：
 * ① 有块正挂着（未结束、未被用户关闭）→ 下一个整分。灭屏时照样排：
 *    Doze 会自然把这些分钟级闹钟合并，醒着时才有真正频率；醒来若仍灭屏，update() 只续排不重发；
 *    退出 Doze（亮屏）那一刻系统会立即补发待处理的闹钟，通知内容瞬间追上真实时间。
 * ② 否则 → 最近一个还没开始的窗口开启时刻；
 * ③ 返回 null = 之后没有焦点通知了，心跳闹钟可以撤掉。
 * 注意：块已被用户"关闭"时不再逐分钟唤醒，直接排到下一块的窗口开启点。
 */
fun nextFocusWakeAt(
    blocks: List<ClassBlock>,
    now: LocalDateTime,
    advanceMinutes: Int,
    dismissed: Set<String>,
): LocalDateTime? {
    var best: LocalDateTime? = null
    for (b in blocks) {
        val ws = b.windowStart(advanceMinutes)
        if (ws.isAfter(now)) {
            best = earlierOf(best, ws)
            continue
        }
        if (now.isAfter(b.endAt.plusSeconds(FOCUS_TAIL_SECONDS))) continue   // 连 30 秒尾巴都过完了
        if (dismissed.contains(b.key())) continue                 // 用户点了"关闭"，本块不再打扰
        // 窗口内（含尾巴）：下一个整分（刷倒计时）与下一个"状态切换点"取较早者 ——
        // 后者保证「上课了 / 下课了 / 下一节」这三个 15/30 秒边界能准点切换
        val nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        return b.nextPhaseChangeAfter(now)?.let { if (it.isBefore(nextMinute)) it else nextMinute } ?: nextMinute
    }
    return best
}
