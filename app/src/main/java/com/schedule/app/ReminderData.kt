package com.schedule.app

import android.content.SharedPreferences
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 通知方式：三选一、互斥 —— 从根上消掉"普通通知和焦点通知同时说话"的重复 */
const val MODE_OFF = 0      // 不启用通知
const val MODE_NORMAL = 1   // 普通通知：到点响铃（课前 / 准点 / 下课）
const val MODE_FOCUS = 2    // 焦点通知：常驻静默倒计时条，到点不响

/**
 * 上课提醒配置
 *
 * @param mode              通知方式，三选一（见 MODE_*）
 * @param advanceMinutes    普通模式：提前量列表（分钟，0 = 准点）。一节课可同时在多个时间点提醒
 * @param endReminder       普通模式：下课时也发一条"下课啦"
 * @param focusAdvanceMinutes 焦点模式：提前多久开始挂那条常驻倒计时
 */
data class ReminderConfig(
    val mode: Int = MODE_NORMAL,
    val advanceMinutes: List<Int> = listOf(0, 20),
    val endReminder: Boolean = false,
    val focusAdvanceMinutes: Int = 10
) {
    /** 去重、排序、限幅后的提前量（大 → 小，即最先触发的排前面） */
    val advances: List<Int>
        get() = advanceMinutes.map { it.coerceIn(0, ReminderNotifier.ADVANCE_MAX) }
            .distinct().sorted()

    /** 最小的提前量 = 最晚触发的那个，用来找"下一节要提醒的课" */
    val minAdvance: Int get() = advances.minOrNull() ?: 0

    /** 是否启用了任何一种通知（旧调用点沿用） */
    val enabled: Boolean get() = mode != MODE_OFF

    /** 是否处于焦点通知模式（旧调用点沿用） */
    val focusEnabled: Boolean get() = mode == MODE_FOCUS
}

private const val KEY_REMIND_ENABLED = "remind_enabled"
private const val KEY_REMIND_ADVANCE = "remind_advance"          // 旧版单值，只做迁移读取
private const val KEY_REMIND_ADVANCES = "remind_advance_list"    // 新版：逗号分隔
private const val KEY_REMIND_END = "remind_end"
private const val KEY_REMIND_FOCUS = "remind_focus"
private const val KEY_REMIND_MODE = "remind_mode"                // 三选一（新版）
private const val KEY_REMIND_FOCUS_ADVANCE = "remind_focus_advance"

fun loadReminderConfig(prefs: SharedPreferences): ReminderConfig {
    val advances = if (prefs.contains(KEY_REMIND_ADVANCES)) {
        (prefs.getString(KEY_REMIND_ADVANCES, "") ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }
    } else {
        // 迁移：老版本只有一个提前量，照搬成单元素列表
        listOf(prefs.getInt(KEY_REMIND_ADVANCE, 10))
    }
    // 迁移：老版本是"总开关 + 焦点开关"两个布尔，这里折算成三选一
    val legacyEnabled = prefs.getBoolean(KEY_REMIND_ENABLED, true)
    val legacyFocus = prefs.getBoolean(KEY_REMIND_FOCUS, true)
    val mode = if (prefs.contains(KEY_REMIND_MODE)) {
        prefs.getInt(KEY_REMIND_MODE, MODE_NORMAL)
    } else when {
        !legacyEnabled -> MODE_OFF
        legacyFocus -> MODE_FOCUS
        else -> MODE_NORMAL
    }
    val legacyMax = (advances.maxOrNull() ?: 10).coerceIn(0, FOCUS_ADVANCE_CAP)
    return ReminderConfig(
        mode = mode,
        advanceMinutes = advances.ifEmpty { listOf(0) },
        endReminder = prefs.getBoolean(KEY_REMIND_END, false),
        focusAdvanceMinutes = prefs.getInt(KEY_REMIND_FOCUS_ADVANCE, if (legacyMax == 0) 10 else legacyMax)
    )
}

fun saveReminderConfig(prefs: SharedPreferences, c: ReminderConfig) {
    prefs.edit()
        .putInt(KEY_REMIND_MODE, c.mode)
        // 旧键一起写：万一用户回退到老版本，行为不会突然全变
        .putBoolean(KEY_REMIND_ENABLED, c.enabled)
        .putBoolean(KEY_REMIND_FOCUS, c.focusEnabled)
        .putString(KEY_REMIND_ADVANCES, c.advances.joinToString(","))
        .putInt(KEY_REMIND_FOCUS_ADVANCE, c.focusAdvanceMinutes)
        .remove(KEY_REMIND_ADVANCE)
        .remove("remind_weekend")   // 已废弃：不再区分周末，清掉老版本残留
        .putBoolean(KEY_REMIND_END, c.endReminder)
        .apply()
}

// ---------------- 提前量的中文描述 ----------------

/** "20分钟" / "1小时" / "1小时30分钟" —— 通知正文用（UI 已无"天"单位，超过 24 小时按小时显示） */
fun advanceDurationText(minutes: Int): String {
    val m = minutes.coerceIn(0, ReminderNotifier.ADVANCE_MAX)
    if (m < 60) return "${m}分钟"
    val h = m / 60
    val rest = m % 60
    return if (rest > 0) "${h}小时${rest}分钟" else "${h}小时"
}

/** 列表行标题："开始时" / "5分钟前" / "1天前" */
fun advanceRowLabel(minutes: Int): String =
    if (minutes == 0) "开始时" else "${advanceDurationText(minutes)}前"

/** 抽屉/提示条里的摘要："准点、提前20分钟" */
fun advanceSummary(advances: List<Int>): String =
    if (advances.isEmpty()) "未设置" else advances.sorted().joinToString("、") {
        if (it == 0) "准点" else "提前${advanceDurationText(it)}"
    }

/** 一次具体的上课事件：某月某日某节课上某门课 */
data class ClassEvent(
    val course: Course,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val slotLabel: String,
    /** 本节课在时间表里的起止索引（findSlotRange 的结果），用于判断"下一节是否紧挨着" */
    val slotStartIndex: Int = -1,
    val slotEndIndex: Int = -1,
) {
    val startDateTime: LocalDateTime get() = LocalDateTime.of(date, startTime)

    /** 通知触发时间 = 上课时间 - 提前量 */
    fun triggerAt(advanceMinutes: Int): LocalDateTime = startDateTime.minusMinutes(advanceMinutes.toLong())

    fun startText(): String = startTime.format(HHMM)
    fun endText(): String = endTime.format(HHMM)

    /** "今天" / "明天" / "周三 09/09" */
    fun dayLabel(): String {
        val today = LocalDate.now()
        return when (ChronoUnit.DAYS.between(today, date)) {
            0L -> "今天"
            1L -> "明天"
            else -> dayNames.getOrElse(course.day) { "" } + " " + date.format(MMDD)
        }
    }

    /** 通知栏里显示的时间段描述 */
    fun timeRangeText(): String = "${startText()}-${endText()}"

    /** "第3节-第4节" → "3-4节"，"第3节" → "3节"（通知横幅宽度有限，能省一个字是一个） */
    fun compactSlotLabel(): String =
        slotLabel.replace("第", "").let { if (it.contains("节")) it.replace("节-", "-") else it }

    /** 稳定且唯一的通知 ID（同一节课的同一提前量重复推送会覆盖而不是新增） */
    fun notificationId(advanceMinutes: Int = 0): Int {
        var h = date.toEpochDay().toInt()
        h = h * 31 + course.day
        h = h * 31 + startTime.toSecondOfDay() / 60
        h = h * 31 + advanceMinutes.coerceIn(0, ReminderNotifier.ADVANCE_MAX)
        h = h * 31 + course.name.hashCode()
        return h and 0x7FFFFFFF
    }

    fun key(): String = "${date}|${course.name}|${course.day}|$slotLabel|$startTime"

    /**
     * 下节课 [other] 是否"紧接着"本节课：同一天、且在时间表上正好是下一个时间段
     * （本节结束行 slotEndIndex 的下一行 slotEndIndex+1 就是下节开始行）。
     * 中间隔了课时、或下节在第二天，都返回 false。
     */
    fun backToBackWith(other: ClassEvent): Boolean =
        other.date == date && other.slotStartIndex == slotEndIndex + 1

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        private val MMDD = DateTimeFormatter.ofPattern("MM/dd")
    }
}

/**
 * 下课通知该不该发（Receiver 补发路径）：
 * ① 已到下课时间且在 30 分钟补发窗口内；② 若今天后面还有课（[nextStart] 非空），下一节还没上课。
 * 条件②是关键守卫：闹钟迟到/去重记录丢失时，防止"下一节上课时刻"又补弹上一节的下课通知
 * （那时上课通知已经接管，两条同刻弹出就是重复）。无下节课时不需要这个守卫。
 */
fun shouldFireClassEnd(end: LocalDateTime, nextStart: LocalDateTime?, now: LocalDateTime): Boolean =
    !end.isAfter(now) && now.isBefore(end.plusMinutes(30)) && (nextStart == null || nextStart.isAfter(now))

/**
 * 下课通知要用的两件事（Receiver 与测试共用同一份判定，避免逻辑各写一遍走样）：
 * @param info      要写进通知的下节课 —— 仅当它"紧接着本节"（同天、时间表相邻）才非空；
 *                  中间隔课时或下节在第二天的，返回 null，通知只报"已下课"。
 * @param nextStart 本节之后最近一节的上课时间（今天最后一节则为 null），供 shouldFireClassEnd 守卫。
 */
data class ClassEndInfo(val info: ClassEvent?, val nextStart: LocalDateTime?)

fun classEndInfo(events: List<ClassEvent>, e: ClassEvent): ClassEndInfo {
    val end = LocalDateTime.of(e.date, e.endTime)
    // 找本节之后最近的下一节（不同教室或不同课名，避免同一门课多时段自我匹配）
    val next = events.firstOrNull {
        it.triggerAt(0).isAfter(end) &&
            (it.course.room != e.course.room || it.course.name != e.course.name)
    }
    return ClassEndInfo(
        info = if (next != null && e.backToBackWith(next)) next else null,
        nextStart = next?.startDateTime
    )
}

/**
 * 课前提醒是否被"前一节的下课通知"覆盖。
 *
 * 同一天存在紧邻的上一节 prev（prev 结束行的下一行就是本节开始行），两节之间只隔 gap 分钟时，
 * 提前量 >= gap 的课前触发点会落在下课通知时刻或更早 —— 而下课通知本身就写着"下一节：《XX》…"，
 * 再弹一条就是重复，所以抑制。提前量小于 gap（间隔 20 分、提前 19 分）仍在两节之间独立触发，照常提醒。
 * 仅当下课提醒开关打开时才有冲突可言，由调用方保证。
 */
fun advanceCoveredByPrevEnd(event: ClassEvent, advance: Int, events: List<ClassEvent>): Boolean {
    val prev = events.firstOrNull { it.backToBackWith(event) } ?: return false
    val gap = ChronoUnit.MINUTES.between(
        LocalDateTime.of(prev.date, prev.endTime), event.startDateTime
    )
    return advance >= gap
}

/**
 * 从课程的时间段字符串里定位它在时间表里的行范围。
 * 兼容两种写法："第1节、第2节"（名称逐个匹配）与 "第1-2节"（按节次编号回退）。
 */
fun findSlotRange(slotStr: String, slots: List<SlotItem>): Pair<Int, Int>? {
    if (slots.isEmpty()) return null
    val names = slots.map { it.name.trim() }
    val parts = slotStr.split('、', '，', ',').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null

    val matched = parts.mapNotNull { p -> names.indexOfFirst { it == p }.takeIf { it >= 0 } }
    if (matched.isNotEmpty()) return matched.min() to matched.max()

    // "第1-2节" / "第3节" 这类按编号回退
    val nums = parts.flatMap { Regex("""\d+""").findAll(it) }.mapNotNull { it.value.toIntOrNull() }
    if (nums.isEmpty()) return null
    val firstNum = nums.min()
    val lastNum = if (nums.size >= 2) nums.max() else firstNum
    val byName = names.indexOf("第${firstNum}节")
    if (byName >= 0) {
        val byNameEnd = names.indexOf("第${lastNum}节")
        return byName to maxOf(byName, if (byNameEnd >= 0) byNameEnd else byName)
    }
    val fi = (firstNum - 1).coerceIn(0, slots.size - 1)
    val li = (lastNum - 1).coerceIn(0, slots.size - 1)
    return fi to maxOf(fi, li)
}

private val TIME_PATTERN = Regex("""^(\d{1,2}):(\d{1,2})$""")

/**
 * 把中文输入法容易打出的全角字符归一化成半角：
 * 全角冒号「：」、全角数字「０-９」、全角空格。
 * 存盘前和解析前都过一次，避免"08：30"这种看着对、其实解析不出来的数据。
 */
fun normalizeTimeText(text: String): String = buildString(text.length) {
    for (ch in text) {
        when {
            ch == '\uFF1A' -> append(':')                       // ：
            ch in '\uFF10'..'\uFF19' -> append('0' + (ch - '\uFF10'))  // ０-９
            ch == '\u3000' -> append(' ')                       // 全角空格
            else -> append(ch)
        }
    }
}.trim()

/** 兼容 "08:30" / "8:30" / "8:3" 这类写法 */
private fun parseTime(text: String): LocalTime? {
    val m = TIME_PATTERN.matchEntire(normalizeTimeText(text)) ?: return null
    val h = m.groupValues[1].toIntOrNull() ?: return null
    val mi = m.groupValues[2].toIntOrNull() ?: return null
    if (h > 23 || mi > 59) return null
    return LocalTime.of(h, mi)
}

/**
 * 列出从现在起 [horizonDays] 天内、落在学期周次范围内的所有上课事件（按时间升序）。
 *
 * [holidays] 与课表列显示同一套规则：
 *   放假区间内的日期整天无课（不生成事件、不排闹钟）；
 *   补课日按"被补源日期"的周几+周次取课（事件日期仍是补课日，通知显示照常）。
 */
fun upcomingClassEvents(
    courses: List<Course>,
    config: ScheduleConfig,
    slots: List<SlotItem>,
    reminder: ReminderConfig,
    now: LocalDateTime = LocalDateTime.now(),
    horizonDays: Int = 37,
    holidays: List<HolidayItem> = emptyList(),
): List<ClassEvent> {
    if (courses.isEmpty() || slots.isEmpty()) return emptyList()
    val start = runCatching { LocalDate.parse(config.startDate.trim()) }.getOrElse { return emptyList() }
    val result = ArrayList<ClassEvent>()
    val today = now.toLocalDate()

    for (i in 0 until horizonDays) {
        val date = today.plusDays(i.toLong())
        if (date.isBefore(start)) continue
        // 节假日过滤/改道（补课优先于放假，与 computeColumnSourceDates 一致）
        val src = if (holidays.isEmpty()) null else makeupOn(holidays, date)?.let { parseHolidayDate(it.second.sourceDate) }
        if (holidays.isNotEmpty() && src == null && holidayOn(holidays, date) != null) continue
        val dow = (src ?: date).dayOfWeek.value
        val week = if (src != null) weekOfDate(config, src)
        else (ChronoUnit.DAYS.between(start, date).toInt() / 7 + 1)
        if (week !in 1..config.totalWeeks) continue

        for (c in courses) {
            if (c.room == "-" || c.day != dow) continue
            if (week !in c.weekStart..c.weekEnd) continue
            val (si, ei) = findSlotRange(c.slot, slots) ?: continue
            val st = parseTime(slots[si].startTime) ?: continue
            val et = parseTime(slots[ei].endTime) ?: st
            val label = if (si == ei) slots[si].name else "${slots[si].name}-${slots[ei].name}"
            result.add(ClassEvent(c, date, st, et, label, slotStartIndex = si, slotEndIndex = ei))
        }
    }
    return result.sortedWith(compareBy({ it.date }, { it.startTime }))
}

/** 下一次要提醒的课（触发时间仍在未来）；主页与调度都用得上 */
fun nextClassEvent(
    courses: List<Course>,
    config: ScheduleConfig,
    slots: List<SlotItem>,
    reminder: ReminderConfig,
    now: LocalDateTime = LocalDateTime.now()
): ClassEvent? =
    upcomingClassEvents(courses, config, slots, reminder, now)
        .firstOrNull { it.triggerAt(reminder.minAdvance).isAfter(now) }
