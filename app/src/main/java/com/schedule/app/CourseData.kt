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

## 总体信息
| 总周数 | 学期开始日期 | 学校 | 专业 |
|--------|-------------|------|------|
| ${'$'}{totalWeeks} | ${'$'}{startDate} | ${'$'}{school} | ${'$'}{campus} | ${'$'}{major} |

## 课程
| 课程名 | 教师 | 教室 | 星期 | 时间段 | 起始周 | 结束周 | 类型 | 学分 | 考核 | 开课单位 | 颜色 |
|--------|------|------|------|--------|--------|--------|------|------|------|----------|------|
""".trimIndent()

    fun exportCourses(courses: List<Course>, config: ScheduleConfig, slots: List<SlotItem>): String {
        val schedules = courses.toSchedules()
        val sb = StringBuilder()
        sb.appendLine("# 大学课程表 — 完整数据")
        sb.appendLine("<!-- 此文件可手动编辑后导入APP -->")
        sb.appendLine()
        sb.appendLine("## 总体信息")
        sb.appendLine("| 总周数 | 学期开始日期 | 学校 | 专业 |")
        sb.appendLine("|--------|-------------|------|------|")
        sb.appendLine("| ${config.totalWeeks} | ${config.startDate} | ${config.school} | ${config.major} |")
        sb.appendLine()
        sb.appendLine("## 时间表")
        sb.appendLine("| 名称 | 开始时间 | 结束时间 |")
        sb.appendLine("|------|----------|----------|")
        for (s in slots) {
            sb.appendLine("| ${s.name} | ${s.startTime} | ${s.endTime} |")
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
        val errors: List<String>
    )

    fun importMarkdown(text: String): ParseResult {
        val errors = mutableListOf<String>()
        val courses = mutableListOf<Course>()
        var config = ScheduleConfig()
        val slots = mutableListOf<SlotItem>()

        val lines = text.lines()
        var inSettings = false
        var inCourses = false
        var inSlots = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("## 总体信息")) { inSettings = true; inCourses = false; inSlots = false; continue }
            if (trimmed.startsWith("## 时间表")) { inSlots = true; inSettings = false; inCourses = false; continue }
            if (trimmed.startsWith("## 课程")) { inCourses = true; inSettings = false; inSlots = false; continue }
            if (trimmed.startsWith("# ") || trimmed.startsWith("<!--") || trimmed.startsWith("---")) continue
            if (trimmed.isEmpty()) continue

            // 跳过分隔行
            if (trimmed.matches(Regex("^\\|[-|\\s]+\$"))) continue
            // 跳过表头行
            if (trimmed.contains("课程名") && trimmed.contains("教师")) continue
            if (trimmed.contains("名称") && trimmed.contains("开始时间")) continue

            if (inSettings && trimmed.startsWith("|")) {
                val cells = parseCells(trimmed)
                if (cells.size >= 5) {
                    try {
                        config = config.copy(
                            totalWeeks = cells[0].toIntOrNull() ?: 5,
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
            }
        }
        return ParseResult(courses, config, slots, errors)
    }

    private fun parseCells(line: String): List<String> {
        return line.split("|")
            .map { it.trim() }
            .drop(1)  // 去掉开头空元素
            .dropLast(1)  // 去掉结尾空元素
    }
}
