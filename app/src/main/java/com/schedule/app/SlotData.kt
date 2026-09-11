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

/**
 * 默认时间表：空的。
 * 新装用户由引导流程（或时间表设置页）自己添加节次，老用户的数据存在 prefs 里不受影响。
 */
fun defaultSlots(): MutableList<SlotItem> = mutableListOf()

fun loadSlots(prefs: android.content.SharedPreferences): MutableList<SlotItem> {
    val json = prefs.getString("slots_json", null) ?: return defaultSlots()
    if (json.isEmpty()) return mutableListOf()
    return json.split("|||").mapNotNull { part ->
        val f = part.split("###")
        if (f.size >= 4) SlotItem(id = f[0], name = f[1], startTime = normalizeTimeText(f[2]), endTime = normalizeTimeText(f[3]))
        else null
    }.toMutableList()
}

fun saveSlots(prefs: android.content.SharedPreferences, slots: List<SlotItem>) {
    // 存盘前统一归一化，中文输入法的全角冒号/全角数字不会进库
    val json = slots.joinToString("|||") { "${it.id}###${it.name}###${normalizeTimeText(it.startTime)}###${normalizeTimeText(it.endTime)}" }
    prefs.edit().putString("slots_json", json).apply()
}
