package com.schedule.app

import android.content.Context
import android.content.SharedPreferences
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * 测试课表（开发者模式）。
 *
 * 给测试人员用：打开后，学期设置 / 时间表 / 课程 / 节假日 全部换成一份专门构造的假课表，
 * 目的是把通知与提醒的各种边界情况都能在"今天/这几天"里撞见，不用干等真实课表排课。
 *
 * ## 真实数据怎么保证不被覆盖
 * 测试数据写在**另一个 SharedPreferences 文件**（`schedule_test`）里，真实的 `schedule` 文件
 * 全程只读不写（只在真实文件里存一个开/关标志位）。所有页面和排期逻辑统一通过 [dataStore]
 * 拿"该读哪个文件"：开着就给测试文件，关着就给真实文件。
 * 于是关闭 = 标志位翻回去，真实数据本来就一动没动，直接恢复原样。
 *
 * 开关用 commit() 而不是 apply()：紧接着就要 recreate Activity 重新读 prefs，
 * 异步落盘可能赶不上那次读取。
 */
object TestSchedule {

    /** 真实数据所在的 prefs 文件名 */
    const val REAL_PREFS = "schedule"
    /** 测试数据单独一个文件，与真实数据物理隔离 */
    private const val TEST_PREFS = "schedule_test"
    /** 开关标志位存在真实 prefs 里（只有这一个键，不碰课表数据） */
    private const val KEY_ON = "test_schedule_on"

    private fun realPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(REAL_PREFS, Context.MODE_PRIVATE)

    private fun testPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)

    /** 设置类数据（深色模式、引导标志、提醒配置、渠道版本…）永远走这里，不随测试课表切换 */
    fun settingsStore(context: Context): SharedPreferences = realPrefs(context)

    /**
     * 测试课表是否生效 —— **以开发者模式开着为前提**。
     * 这样即使留下"测试课表开着但开发者模式已关"的脏状态，闹钟广播、开机恢复这些
     * 不经过 MainActivity 的路径也不会拿测试数据去排提醒（它们只查这个函数）。
     */
    fun isEnabled(context: Context): Boolean =
        DeveloperSettings.isEnabled(context) && realPrefs(context).getBoolean(KEY_ON, false)

    /** 只看子开关本身存了什么（供恢复默认时判断，不含前提） */
    fun isStoredOn(context: Context): Boolean = realPrefs(context).getBoolean(KEY_ON, false)

    /**
     * 课表类数据（学期设置 / 时间表 / 课程 / 节假日）+ 提醒去重记录 + 焦点"已关闭"记录
     * 该从哪个文件读写：开着测试课表 → 测试文件；否则 → 真实文件。
     *
     * 去重/已关闭记录也要跟着走：它们的 key 由课程信息拼成，混在一份文件里会让
     * "切换课表"时旧记录误判新课表为"已提醒过"（或反过来重复提醒）。
     */
    fun dataStore(context: Context): SharedPreferences =
        if (isEnabled(context)) testPrefs(context) else realPrefs(context)

    /** 打开：先按"今天"生成一份测试数据，再翻标志位（顺序不能反，seed 会清空测试文件） */
    fun enable(context: Context) {
        seed(context)
        realPrefs(context).edit().putBoolean(KEY_ON, true).commit()
    }

    /** 关闭：翻回标志位并清掉测试文件（真实数据从未被写过，无需恢复） */
    fun disable(context: Context) {
        realPrefs(context).edit().putBoolean(KEY_ON, false).commit()
        testPrefs(context).edit().clear().apply()
    }

    // ---------------- 测试数据 ----------------

    private data class TSlot(val name: String, val start: String, val end: String)

    /** 8 节课：上午 4 节 / 下午 3 节 / 晚上 1 节，正好能看出课表"每 6 节一条时段分隔线" */
    private val SLOTS = listOf(
        TSlot("第1节", "08:00", "08:45"),
        TSlot("第2节", "08:55", "09:40"),
        TSlot("第3节", "10:00", "10:45"),
        TSlot("第4节", "10:55", "11:40"),
        TSlot("第5节", "14:00", "14:45"),
        TSlot("第6节", "14:55", "15:40"),
        TSlot("第7节", "16:00", "16:45"),
        TSlot("第8节", "19:00", "19:45"),
    )

    /**
     * 生成测试数据。全部以"本周一"为锚点，保证打开后这几天就有课，
     * 而且额外塞一节"离现在最近"的课，让提前量提醒/焦点倒计时很快就能命中。
     */
    private fun seed(context: Context) {
        val tp = testPrefs(context)
        tp.edit().clear().commit()

        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        // ---- 学期设置 ----
        saveConfigToPrefs(
            tp,
            ScheduleConfig(
                totalWeeks = 18,
                startDate = monday.toString(),
                school = "测试大学",
                major = "通知测试专用"
            )
        )

        // ---- 时间表 ----
        saveSlots(tp, SLOTS.map { SlotItem(name = it.name, startTime = it.start, endTime = it.end) })

        // ---- 课程 ----
        val courses = mutableListOf<Course>()
        fun add(
            name: String, teacher: String, room: String, day: Int, slotStr: String,
            ws: Int, we: Int, type: String = "必修", credit: Double = 2.0,
            exam: String = "考查", unit: String = "测试学院",
        ) {
            courses += Course(name, teacher, room, day, slotStr, ws, we, type, credit, exam, unit)
        }

        // ① 超长课程名：验证通知标题/课表格子不会把关键信息挤掉
        add(
            "毛泽东思想和中国特色社会主义理论体系概论", "周涛", "教3-301", 2,
            "第1节、第2节", 1, 18, "必修", 3.0, "考试", "马克思主义学院"
        )
        // ② 超长教室名：验证正文一行放不下时的截断
        add(
            "人工智能前沿讲座", "刘芳", "逸夫楼多功能报告厅（含线上云课堂同步直播）", 5,
            "第8节", 1, 18, "限选", 1.0, "考查", "计算机学院"
        )
        // ③ 同一节次两门课 → 课程冲突黄色卡片 + 冲突详情
        add("高等数学A", "张伟", "教1-101", 1, "第5节", 1, 18, "必修", 5.0, "考试", "数学学院")
        add("线性代数", "郑华", "教2-205", 1, "第5节", 1, 18, "必修", 3.0, "考试", "数学学院")
        // ⑤ 多时间段（一次提醒里出现"第1-2节"这种跨节）
        add("数据结构与算法", "王强", "实验楼A-305", 3, "第1节、第2节、第3节", 1, 16, "必修", 4.0, "考试", "计算机学院")
        // ⑥ 连堂：焦点通知要挂到整段下课，并显示"连堂 x/y 节"
        add("大学英语四级", "李静", "教2-203", 2, "第3节、第4节", 1, 18, "必修", 2.0, "考试", "外国语学院")
        add("软件工程导论", "陈明", "教1-101", 2, "第6节、第7节", 1, 12, "必修", 2.0, "考查", "计算机学院")
        // ⑦ 周末课程：验证周六/周日也照常提醒
        add("创新创业实践", "赵磊", "众创空间201", 6, "第5节、第6节", 2, 14, "任选", 1.0, "考查", "创业学院")
        add("体育·篮球", "孙浩", "西区篮球场", 7, "第1节、第2节", 1, 18, "必修", 1.0, "考查", "体育学院")
        // ⑧ 只在部分周次有课：验证"周次范围外不提醒"（第9周起就没有这门课了）
        add("形势与政策", "胡斌", "大礼堂", 4, "第7节", 1, 8, "必修", 2.0, "考查", "马克思主义学院")
        // ⑨ 后半学期才开始：验证学期前几周不该冒出提醒
        add("毕业设计（论文）指导", "马俊", "指导教师办公室", 3, "第8节", 10, 18, "必修", 8.0, "考查", "计算机学院")
        // ⑩ 无教师 / 无教室：验证通知正文不会拼出"null"或空逗号
        add("自主自习", "", "", 4, "第5节、第6节", 1, 18, "任选", 0.0, "考查", "")

        // ⑪ 「离现在最近的一节课」：保证打开后很快就能撞见提前量提醒与焦点倒计时
        val nowTime = LocalTime.now()
        val usedOnTarget = courses
            .filter { it.day == targetDay(today, monday, nowTime).dayOfWeek.value }
            .flatMap { c -> c.slot.split('、').map { it.trim() } }
            .toSet()
        val soon = SLOTS.firstOrNull { LocalTime.parse(it.start).isAfter(nowTime) && it.name !in usedOnTarget }
        if (soon != null) {
            val d = targetDay(today, monday, nowTime)
            add(
                "今日就近测试课", "测试员", "T-001", d.dayOfWeek.value, soon.name,
                weekOf(monday, d, 18), weekOf(monday, d, 18), "必修", 1.0, "考查", "测试学院"
            )
        }

        saveCoursesToPrefs(tp, courses)

        // ---- 节假日 ----
        val nextMonday = monday.plusWeeks(1)
        val thisSunday = monday.plusDays(6)
        val thisFriday = monday.plusDays(4)
        saveHolidays(
            tp,
            listOf(
                // ⑫ 下周整天放假：那一列被清空、显示"休"角标
                HolidayItem(
                    name = "测试·放假", offStart = nextMonday.toString(), offEnd = nextMonday.toString()
                ),
                // ⑬ 周日补周五的课：周日那列显示周五的课、显示"补"角标（跨列取课）
                HolidayItem(
                    name = "测试·补课",
                    offStart = thisSunday.toString(), offEnd = thisSunday.toString(),
                    makeups = mutableListOf(HolidayMakeup(thisSunday.toString(), thisFriday.toString()))
                ),
            )
        )

        // seeded_on 记下基准日：界面上不显示，排查"就近测试课为什么没出现"时看它
        tp.edit().putString("seeded_on", today.toString()).commit()
    }

    /**
     * "离现在最近的一节课"该放在哪天：今天还有没上的课就放今天，
     * 今天全上完了就放明天（明天要是出了学期范围，就仍放今天，反正翻来覆去也就这几节）。
     */
    private fun targetDay(today: LocalDate, monday: LocalDate, nowTime: LocalTime): LocalDate {
        val hasLaterToday = SLOTS.any { LocalTime.parse(it.start).isAfter(nowTime) }
        if (hasLaterToday) return today
        val tomorrow = today.plusDays(1)
        return if (weekOf(monday, tomorrow, 18) > 0) tomorrow else today
    }

    /** 某日期在学期里是第几周；超出 1..totalWeeks 返回该周数本身或 -1（用于判断在不在学期内） */
    private fun weekOf(monday: LocalDate, date: LocalDate, totalWeeks: Int): Int {
        val w = java.time.temporal.ChronoUnit.DAYS.between(monday, date).toInt() / 7 + 1
        return if (w in 1..totalWeeks) w else -1
    }

    /** 开发者页展示：这份测试数据一共覆盖了哪些测试点 */
    val CHECKLIST: List<String> = listOf(
        "超长课程名 —— 通知标题/课表格子会不会挤掉关键信息",
        "超长教室名 —— 正文一行放不下时的截断",
        "同一节次两门课 —— 课程冲突黄卡与冲突详情",
        "多时间段（三节连排）—— 一次提醒里显示跨节",
        "连堂两节 —— 焦点条挂到整段下课、显示「连堂 x/y 节」",
        "周六、周日都有课 —— 周末照常提醒",
        "只在第1~8周有课 —— 第9周起不该再提醒",
        "第10周才开始 —— 学期前几周不该冒出提醒",
        "无教师无教室 —— 正文不会拼出 null 或空逗号",
        "「今日就近测试课」—— 打开后很快撞见提前量与焦点倒计时",
        "下周一放假 —— 该列清空并显示「休」角标",
        "周日补周五的课 —— 该列显示周五的课并显示「补」角标",
    )
}
