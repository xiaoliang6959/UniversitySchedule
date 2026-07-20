package com.schedule.app

import java.util.UUID

data class SlotItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var startTime: String = "",
    var endTime: String = ""
) {
    fun label() = "$name\n$startTime-$endTime"
    fun shortLabel() = "$startTime-$endTime"
}

fun defaultSlots(): MutableList<SlotItem> = mutableListOf(
    SlotItem(name = "第1节", startTime = "08:30", endTime = "09:15"),
    SlotItem(name = "第2节", startTime = "09:20", endTime = "10:05"),
    SlotItem(name = "第3节", startTime = "10:25", endTime = "11:10"),
    SlotItem(name = "第4节", startTime = "11:15", endTime = "12:00"),
    SlotItem(name = "中午1", startTime = "12:00", endTime = "14:00"),
    SlotItem(name = "中午2", startTime = "14:00", endTime = "14:30"),
    SlotItem(name = "第5节", startTime = "14:30", endTime = "15:15"),
    SlotItem(name = "第6节", startTime = "15:20", endTime = "16:05"),
    SlotItem(name = "第7节", startTime = "16:25", endTime = "17:10"),
    SlotItem(name = "第8节", startTime = "17:15", endTime = "18:00"),
    SlotItem(name = "第9节", startTime = "18:30", endTime = "19:15"),
    SlotItem(name = "第10节", startTime = "19:20", endTime = "20:05"),
    SlotItem(name = "第11节", startTime = "20:15", endTime = "21:00"),
    SlotItem(name = "第12节", startTime = "21:05", endTime = "21:50"),
)

fun loadSlots(prefs: android.content.SharedPreferences): MutableList<SlotItem> {
    val json = prefs.getString("slots_json", null)
    if (json == null) {
        // 首次安装：写入默认时间表并返回
        val def = defaultSlots()
        saveSlots(prefs, def)
        return def
    }
    if (json.isEmpty()) return mutableListOf()
    return json.split("|||").mapNotNull { part ->
        val f = part.split("###")
        if (f.size >= 3) SlotItem(id = f[0], name = f[1], startTime = f[2], endTime = f[3])
        else null
    }.toMutableList()
}

fun saveSlots(prefs: android.content.SharedPreferences, slots: List<SlotItem>) {
    val json = slots.joinToString("|||") { "${it.id}###${it.name}###${it.startTime}###${it.endTime}" }
    prefs.edit().putString("slots_json", json).apply()
}
