package com.schedule.app

data class Course(
    val name: String,
    val teacher: String,
    val room: String,
    val day: Int,          // 1=周一 .. 7=周日
    val slot: String,      // "第1-2节" 等
    val weekStart: Int,
    val weekEnd: Int,
    val type: String = "必修",
    val credit: Double = 2.0,
    val exam: String = "考查",
    val unit: String = "",
    val color: String = "auto"
)

data class ScheduleConfig(
    val totalWeeks: Int = 18,
    val startDate: String = "2026-09-07",
    val school: String = "桂林理工大学",
    val major: String = "软件工程"
)

/**
 * 学期周数的合法上限：一年 52 周，再多也没有意义。
 * 引导页输入、学期设置页输入、Markdown 导入、课表周次条四处统一用它，
 * 保证「能填进来的」和「课表能显示出来的」始终一致（填更大也只按 52 处理）。
 */
const val TOTAL_WEEKS_MAX = 52

object MarkdownCodec {

    private val HEADER = """
# 大学课程表 — 课程数据
<!-- 此文件可手动编辑后导入APP，格式说明见文末 -->
<!-- 格式: | 课程名 | 教师 | 教室 | 星期(1-7) | 时间段 | 起始周 | 结束周 | 类型 | 学分 | 考核 | 开课单位 | 颜色(auto或#bg,#fg) | -->
<!-- 星期: 1=周一 2=周二 3=周三 4=周四 5=周五 6=周六 7=周日 -->
<!-- 时间段: 第1-2节 / 第3-4节 / 第5-6节 / 第7-8节 / 第9-10节 / 第11-12节 -->
<!-- 类型: 必修 / 限选 / 任选 -->
<!-- 考核: 考查 / 考试 -->
<!-- 颜色: auto=自动分配, 或填 #背景色RGB,#文字色RGB (如 #D6EAF8,#1A5276) -->

## 学期设置
| 本学期周数 | 学期开始日期 | 学校 | 专业 |
|--------|-------------|------|------|
| ${'$'}{totalWeeks} | ${'$'}{startDate} | ${'$'}{school} | ${'$'}{campus} | ${'$'}{major} |

## 课程
| 课程名 | 教师 | 教室 | 星期 | 时间段 | 起始周 | 结束周 | 类型 | 学分 | 考核 | 开课单位 | 颜色 |
|--------|------|------|------|--------|--------|--------|------|------|------|----------|------|
""".trimIndent()

    fun exportCourses(courses: List<Course>, config: ScheduleConfig, slots: List<SlotItem>, holidays: List<HolidayItem> = emptyList()): String {
        val schedules = courses.toSchedules()
        val sb = StringBuilder()
        sb.appendLine("# 大学课程表 — 完整数据")
        sb.appendLine("<!-- 此文件可手动编辑后导入APP -->")
        sb.appendLine()
        sb.appendLine("## 学期设置")
        sb.appendLine("| 本学期周数 | 学期开始日期 | 学校 | 专业 |")
        sb.appendLine("|--------|-------------|------|------|")
        sb.appendLine("| ${config.totalWeeks} | ${config.startDate} | ${config.school} | ${config.major} |")
        sb.appendLine()
        sb.appendLine("## 时间表")
        sb.appendLine("| 名称 | 开始时间 | 结束时间 |")
        sb.appendLine("|------|----------|----------|")
        for (s in slots) {
            sb.appendLine("| ${s.name} | ${s.startTime} | ${s.endTime} |")
        }
        if (holidays.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("## 节假日")
            sb.appendLine("| 名称 | 放假开始 | 放假结束 | 补课安排 |")
            sb.appendLine("|------|----------|----------|----------|")
            for (h in holidays) {
                val mk = h.makeups.joinToString(";") { "${it.date}→${it.sourceDate}" }
                sb.appendLine("| ${h.name} | ${h.offStart} | ${h.offEnd} | $mk |")
            }
        }
        sb.appendLine()
        sb.appendLine("## 课程")
        sb.appendLine("| 课程名 | 教师 | 教室 | 星期 | 时间段 | 起始周 | 结束周 | 类型 | 学分 | 考核 | 开课单位 | 颜色 |")
        sb.appendLine("|--------|------|------|------|--------|--------|--------|------|------|------|----------|------|")
        for (sch in schedules) {
            for (ts in sch.slots) {
                sb.appendLine("| ${sch.name} | ${sch.teacher} | ${ts.room} | ${ts.day} | ${ts.slot} | ${ts.weekStart} | ${ts.weekEnd} | ${sch.type} | ${sch.credit} | ${sch.exam} | ${sch.unit} | ${sch.color} |")
            }
        }
        return sb.toString()
    }

    data class ParseResult(
        val courses: List<Course>,
        val config: ScheduleConfig,
        val slots: List<SlotItem>,
        val holidays: List<HolidayItem>,
        val errors: List<String>
    )

    fun importMarkdown(text: String): ParseResult {
        val errors = mutableListOf<String>()
        val courses = mutableListOf<Course>()
        var config = ScheduleConfig()
        val slots = mutableListOf<SlotItem>()
        val holidays = mutableListOf<HolidayItem>()

        val lines = text.lines()
        var inSettings = false
        var inCourses = false
        var inSlots = false
        var inHolidays = false

        for (line in lines) {
            val trimmed = line.trim()
            // 段标题兼容旧名：这一页原来叫「总体信息」，改名后老导出的文件也要能导入
            if (trimmed.startsWith("## 学期设置") || trimmed.startsWith("## 总体信息")) {
                inSettings = true; inCourses = false; inSlots = false; inHolidays = false; continue
            }
            if (trimmed.startsWith("## 时间表")) { inSlots = true; inSettings = false; inCourses = false; inHolidays = false; continue }
            if (trimmed.startsWith("## 节假日")) { inHolidays = true; inSettings = false; inCourses = false; inSlots = false; continue }
            if (trimmed.startsWith("## 课程")) { inCourses = true; inSettings = false; inSlots = false; inHolidays = false; continue }
            if (trimmed.startsWith("# ") || trimmed.startsWith("<!--") || trimmed.startsWith("---")) continue
            if (trimmed.isEmpty()) continue

            // 跳过分隔行
            if (trimmed.matches(Regex("^\\|[-|\\s]+\$"))) continue
            // 跳过表头行
            if (trimmed.contains("课程名") && trimmed.contains("教师")) continue
            if (trimmed.contains("名称") && trimmed.contains("开始时间")) continue
            if (trimmed.contains("放假开始") && trimmed.contains("放假结束")) continue
            if (trimmed.contains("学期开始日期")) continue   // 学期设置表头

            // 长得像节假日行（第 2、3 格都是日期）却不在节假日段里：手改文件把行挪出段落
            // 是最容易踩的坑，静默丢掉一整个节假日比报错更糟 —— 照样收下，并明确提示。
            // 其他段的行不会误判：学期设置第 3 格是学校名、时间表是 08:30、课程是教师名。
            if (!inHolidays && trimmed.startsWith("|")) {
                val cells = parseCells(trimmed)
                if (cells.size >= 3 && parseHolidayDate(cells[1]) != null && parseHolidayDate(cells[2]) != null) {
                    errors.add("「${cells[0]}」不在「## 节假日」段内，已按节假日导入")
                    parseHolidayRow(cells, errors)?.let { holidays.add(it) }
                    continue
                }
            }

            if (inSettings && trimmed.startsWith("|")) {
                val cells = parseCells(trimmed)
                // 4 格：周数 / 开始日期 / 学校 / 专业（导出就是这 4 列）。
                // 以前写的是 >= 5，比导出的列数多一格，导致这一段从来没被导入成功过
                // —— 手改文件里的学期设置会被静默丢掉。
                if (cells.size >= 4) {
                    try {
                        config = config.copy(
                            totalWeeks = (cells[0].toIntOrNull() ?: 5).coerceIn(1, TOTAL_WEEKS_MAX),
                            startDate = cells[1].ifEmpty { config.startDate },
                            school = cells[2].ifEmpty { config.school },
                            major = cells[3].ifEmpty { config.major }
                        )
                    } catch (e: Exception) {
                        errors.add("设置行解析失败: ${e.message}")
                    }
                }
                continue
            }

            if (inSlots && trimmed.startsWith("|")) {
                val cells = parseCells(trimmed)
                if (cells.size >= 3) {
                    try {
                        val name = cells[0]
                        if (name.isEmpty()) continue
                        slots.add(SlotItem(name = name, startTime = cells[1], endTime = cells[2]))
                    } catch (e: Exception) {
                        errors.add("时间表行解析失败: ${e.message}")
                    }
                }
                continue
            }

            if (inHolidays && trimmed.startsWith("|")) {
                // 表头行已在上面跳过。这里只要"像节假日行"就收，列数不够也能兜住
                parseHolidayRow(parseCells(trimmed), errors)?.let { holidays.add(it) }
                continue
            }

            if (inCourses && trimmed.startsWith("|")) {
                val cells = parseCells(trimmed)
                if (cells.size >= 11) {
                    try {
                        val name = cells[0]
                        if (name.isEmpty()) continue
                        val teacher = cells[1]
                        val room = cells[2]
                        val day = cells[3].toIntOrNull() ?: 1
                        val slot = cells[4].ifEmpty { "第1-2节" }
                        val ws = cells[5].toIntOrNull() ?: 1
                        val we = cells[6].toIntOrNull() ?: 18
                        val type = cells[7].ifEmpty { "必修" }
                        val credit = cells[8].toDoubleOrNull() ?: 2.0
                        val exam = cells[9].ifEmpty { "考查" }
                        val unit = cells[10]
                        val color = if (cells.size > 11) cells[11] else "auto"
                        courses.add(Course(name, teacher, room, day, slot, ws, we, type, credit, exam, unit, color))
                    } catch (e: Exception) {
                        errors.add("课程行解析失败: ${e.message}")
                    }
                }
                continue
            }
        }
        return ParseResult(courses, config, slots, holidays, errors)
    }

    /** 补课列里这些写法都表示"没有补课"，不该当成格式错误 */
    private val NONE_CELLS = setOf("-", "--", "—", "——", "/", "\\", "无", "none", "null", "|", "｜")

    /**
     * 解析一行节假日。容错点（都是手写文件里真会出现的写法）：
     *  - 日期宽松：2026-09-25 / 2026/9/25 / 2026.9.25 / 2026年9月25日
     *  - 只写一个日期 → 单日放假（结束日补成开始日）
     *  - 补课列写 -、无、/ 等 → 就是"没有补课"，不再报错
     *  - 解析不出来的行必须进 errors（宁可吵，也别静默丢数据）
     */
    private fun parseHolidayRow(cells: List<String>, errors: MutableList<String>): HolidayItem? {
        if (cells.size < 2) return null
        val name = cells[0]
        if (name.isEmpty()) return null
        val rawStart = cells[1]
        val offS = parseHolidayDate(rawStart) ?: run {
            errors.add("节假日「$name」的日期认不出来：'$rawStart'（支持 2026-09-25 / 2026/9/25 / 2026年9月25日）")
            return null
        }
        val offE = parseHolidayDate(cells.getOrNull(2).orEmpty()) ?: offS
        val mk = cells.getOrNull(3).orEmpty().split(';', '；').map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in NONE_CELLS }
            .mapNotNull { seg ->
                val parts = Regex("→|->|⇒").split(seg).map { it.trim() }
                if (parts.size != 2) { errors.add("补课安排格式错误（应写成 补课日→源日期）: $seg"); return@mapNotNull null }
                val d = parseHolidayDate(parts[0]); val src = parseHolidayDate(parts[1])
                if (d == null || src == null) { errors.add("补课安排日期错误: $seg"); return@mapNotNull null }
                HolidayMakeup(d.toString(), src.toString())
            }.toMutableList()
        return HolidayItem(name = name, offStart = offS.toString(), offEnd = offE.toString(), makeups = mk)
    }

    private fun parseCells(line: String): List<String> {
        return line.split("|")
            .map { it.trim() }
            .drop(1)  // 去掉开头空元素
            .dropLast(1)  // 去掉结尾空元素
    }
}
