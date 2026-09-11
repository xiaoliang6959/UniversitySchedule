package com.schedule.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 单个补课：date（补课日）那天，上 sourceDate（源上课日）那天的课。
 * 用具体日期而非"周几"：跨周调休（如国庆补课补到中秋前）时"周几"无法定位是哪一周。
 */
data class HolidayMakeup(
    var date: String = "",        // 补课日 YYYY-MM-DD
    var sourceDate: String = ""   // 被补的源上课日 YYYY-MM-DD
)

/** 一个节假日：放假区间 offStart..offEnd，以及若干补课日。 */
data class HolidayItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var offStart: String = "",  // YYYY-MM-DD
    var offEnd: String = "",    // YYYY-MM-DD
    var makeups: MutableList<HolidayMakeup> = mutableListOf()
)

/**
 * 存储沿用项目既有风格（### 分隔字段、||| 分隔条目），不引入序列化库。
 * 补课日列表内部用 ';' 分隔多条、每条 "补课日>源日期"。
 */
private fun HolidayMakeup.encode() = "$date>$sourceDate"
private fun decodeMakeup(s: String): HolidayMakeup? {
    val i = s.indexOf('>')
    if (i <= 0) return null
    val d = parseHolidayDate(s.substring(0, i)) ?: return null
    val src = parseHolidayDate(s.substring(i + 1)) ?: return null
    return HolidayMakeup(d.toString(), src.toString())
}

fun HolidayItem.encode(): String {
    val mk = makeups.joinToString(";") { it.encode() }
    return listOf(id, name, offStart, offEnd, mk).joinToString("###")
}

fun loadHolidays(prefs: android.content.SharedPreferences): MutableList<HolidayItem> {
    val json = prefs.getString("holidays_json", null) ?: return mutableListOf()
    if (json.isEmpty()) return mutableListOf()
    return json.split("|||").mapNotNull { part ->
        val f = part.split("###")
        if (f.size < 4) return@mapNotNull null
        val mk = if (f.size >= 5 && f[4].isNotBlank())
            f[4].split(";").mapNotNull { decodeMakeup(it.trim()) }.toMutableList()
        else mutableListOf()
        HolidayItem(
            id = f[0],
            name = f[1],
            offStart = normalizeTimeText(f[2]).trim(),
            offEnd = normalizeTimeText(f[3]).trim(),
            makeups = mk,
        )
    }.toMutableList()
}

fun saveHolidays(prefs: android.content.SharedPreferences, holidays: List<HolidayItem>) {
    val json = holidays.joinToString("|||") { it.encode() }
    prefs.edit().putString("holidays_json", json).apply()
}

/**
 * 宽松解析日期（先归一化全角字符）：
 *   2026-09-25 / 2026/9/5 / 2026.9.5 / 2026年9月25日 / 2026 - 09 - 25 都能吃。
 * 手写的导入文件里这几种写法都常见，以前只认 yyyy-MM-dd，格式一变整条节假日就被丢掉。
 */
fun parseHolidayDate(s: String): LocalDate? {
    var t = normalizeTimeText(s).trim()
    if (t.isEmpty()) return null
    t = t.replace('年', '-').replace('月', '-').replace("日", "")
        .replace('/', '-').replace('.', '-').replace('\uFF0D', '-')
        .replace(Regex("\\s+"), "")
    val m = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(t) ?: return null
    val (y, mo, d) = m.destructured
    return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
}

/** 某日期是否落在任一节假日的放假区间内。返回命中的节假日，未命中 null。 */
fun holidayOn(holidays: List<HolidayItem>, date: LocalDate): HolidayItem? {
    for (h in holidays) {
        val s = parseHolidayDate(h.offStart) ?: continue
        val e = parseHolidayDate(h.offEnd) ?: s
        if (!date.isBefore(s) && !date.isAfter(e)) return h
    }
    return null
}

/** 某日期是否是某节假日的补课日。返回 (节假日, 补课配置)，未命中 null。 */
fun makeupOn(holidays: List<HolidayItem>, date: LocalDate): Pair<HolidayItem, HolidayMakeup>? {
    val ds = date.toString()
    for (h in holidays) {
        for (m in h.makeups) {
            if (parseHolidayDate(m.date)?.toString() == ds) return h to m
        }
    }
    return null
}

/** 某日期在学期里是第几周（与 getWeekDates 同一套算法）；不在学期内返回 -1。 */
fun weekOfDate(config: ScheduleConfig, date: LocalDate): Int {
    val start = parseHolidayDate(config.startDate) ?: return -1
    if (date.isBefore(start)) return -1
    val w = ChronoUnit.DAYS.between(start, date).toInt() / 7 + 1
    return if (w in 1..config.totalWeeks) w else -1
}

/**
 * 计算课表 7 列各自"实际取哪一天的课"。返回长度 7 的列表（索引 0=周一列..6=周日列）：
 *   null      = 该列当天放假，清空不显示；
 *   某日期    = 该列显示这一天的课（正常列=当天自己；补课列=被补的源日期，
 *               连"第几周"也按源日期算，支持跨周补课）。
 * 补课优先于放假（同一天既是放假又是补课，按补课显示——用户显式设定）。
 */
fun computeColumnSourceDates(dates: List<LocalDate>, holidays: List<HolidayItem>): List<LocalDate?> {
    if (holidays.isEmpty()) return dates
    return dates.map { d ->
        val mk = makeupOn(holidays, d)
        when {
            mk != null -> parseHolidayDate(mk.second.sourceDate) ?: d
            holidayOn(holidays, d) != null -> null
            else -> d
        }
    }
}
