package com.schedule.app

data class TimeSlot(
    val day: Int,
    val slot: String,
    val room: String,
    val weekStart: Int,
    val weekEnd: Int
) {
    fun dayName() = listOf("","周一","周二","周三","周四","周五","周六","周日").getOrElse(day) { "" }
    fun summary() = "周${dayName()} ${slot} · $room · 第${weekStart}~${weekEnd}周"
}

data class CourseSchedule(
    val name: String,
    val teacher: String,
    val credit: Double,
    val unit: String,
    val type: String = "必修",
    val exam: String = "考查",
    val color: String = "auto",
    val slots: MutableList<TimeSlot> = mutableListOf()
) {
    fun summary() = "${slots.size}个时间段 · ${credit}学分 · $type · $exam"
}

// 旧格式 Course -> 新格式 CourseSchedule
fun List<Course>.toSchedules(): MutableList<CourseSchedule> {
    val map = linkedMapOf<String, CourseSchedule>()
    this.forEach { c ->
        val sch = map.getOrPut(c.name) {
            CourseSchedule(c.name, c.teacher, c.credit, c.unit, c.type, c.exam, c.color)
        }
        sch.slots.add(TimeSlot(c.day, c.slot, c.room, c.weekStart, c.weekEnd))
    }
    return map.values.toMutableList()
}

// 新格式 -> 旧格式（扁平）
fun CourseSchedule.toFlatCourses(): List<Course> {
    val result = mutableListOf<Course>()
    this.slots.forEach { ts ->
        result.add(Course(this.name, this.teacher, ts.room, ts.day, ts.slot, ts.weekStart, ts.weekEnd, this.type, this.credit, this.exam, this.unit, this.color))
    }
    return result
}

/** 原地替换某课程的全部时段，保持它在扁平列表里的原位置（编辑后不再跳到最后）。 */
fun replaceCourse(list: MutableList<Course>, name: String, newCourses: List<Course>) {
    val idx = list.indexOfFirst { it.name == name }
    list.removeAll { it.name == name }
    if (idx >= 0) list.addAll(idx.coerceAtMost(list.size), newCourses) else list.addAll(newCourses)
}
