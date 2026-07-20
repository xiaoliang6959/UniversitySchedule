package com.schedule.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.prefs.Preferences

class MainActivity : ComponentActivity() {

    var reloadVersion = mutableIntStateOf(0)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.let { text ->
                val result = MarkdownCodec.importMarkdown(text)
                if (result.courses.isEmpty() && result.slots.isEmpty()) {
                    Toast.makeText(this, "⚠️ 文件内无有效数据，无法导入", Toast.LENGTH_LONG).show()
                    return@let
                }
                val prefs = getPrefs()
                saveConfig(prefs, result.config)
                if (result.courses.isNotEmpty()) {
                    saveCourses(prefs, result.courses)
                }
                if (result.slots.isNotEmpty()) {
                    saveSlots(prefs, result.slots)
                }
                Toast.makeText(this, "✅ 导入成功：课程${result.courses.size}条，时间表${result.slots.size}条（已覆盖原有数据）", Toast.LENGTH_SHORT).show()
                if (result.errors.isNotEmpty()) {
                    Toast.makeText(this, "⚠️ 部分解析错误: ${result.errors.first()}", Toast.LENGTH_LONG).show()
                }
                reloadVersion.intValue++
            }
        }
    }

    private val fileSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let {
            contentResolver.openOutputStream(it)?.bufferedWriter()?.use { w ->
                val prefs = getPrefs()
                val courses = loadCourses(prefs)
                val config = loadConfig(prefs)
                val slots = loadSlots(prefs)
                w.write(MarkdownCodec.exportCourses(courses, config, slots))
            }
            Toast.makeText(this, "✅ 导出成功", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPrefs() = getSharedPreferences("schedule", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getPrefs()
        // 首次安装：写入默认时间表（课程和总体信息为空/默认值）
        if (!prefs.contains("slots_json")) {
            saveSlots(prefs, defaultSlots())
        }
        setContent {
            ScheduleApp(
                onExport = { fileSaver.launch("课程表.md") },
                onImport = { filePicker.launch(arrayOf("text/markdown", "text/*")) },
                reloadVersion = reloadVersion
            )
        }
    }

    private fun saveCourses(prefs: android.content.SharedPreferences, courses: List<Course>) {
        val json = courses.joinToString("|||") { c ->
            listOf(c.name,c.teacher,c.room,c.day.toString(),c.slot,c.weekStart.toString(),c.weekEnd.toString(),c.type,c.credit.toString(),c.exam,c.unit,c.color).joinToString("###")
        }
        prefs.edit().putString("courses_json", json).apply()
    }

    private fun loadCourses(prefs: android.content.SharedPreferences): List<Course> {
        val json = prefs.getString("courses_json", "") ?: ""
        if (json.isEmpty()) return emptyList()
        return json.split("|||").mapNotNull { part ->
            val f = part.split("###")
            if (f.size >= 11) {
                try { Course(f[0],f[1],f[2],f[3].toInt(),f[4],f[5].toInt(),f[6].toInt(),f[7],f[8].toDoubleOrNull()?:2.0,f[9],f[10],if(f.size>11)f[11] else "auto") }
                catch (e: Exception) { null }
            } else null
        }
    }

    private fun saveConfig(prefs: android.content.SharedPreferences, config: ScheduleConfig) {
        prefs.edit()
            .putInt("totalWeeks", config.totalWeeks)
            .putString("startDate", config.startDate)
            .putString("school", config.school)
            .putString("major", config.major)
            .apply()
    }

    private fun loadConfig(prefs: android.content.SharedPreferences) = ScheduleConfig(
        totalWeeks = prefs.getInt("totalWeeks", 5),
        startDate = prefs.getString("startDate", "2026-09-01") ?: "2026-09-01",
        school = prefs.getString("school", "某某大学") ?: "某某大学",
        major = prefs.getString("major", "某某专业") ?: "某某专业"
    )
}

// 扩展函数给 Compose 使用
fun Context.getSharedPreferences(key: String, mode: Int): android.content.SharedPreferences {
    return getSharedPreferences(key, mode)
}
