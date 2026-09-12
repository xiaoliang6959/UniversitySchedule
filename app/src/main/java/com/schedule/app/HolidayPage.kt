package com.schedule.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.ui.platform.LocalContext

/**
 * 节假日设置页（雏形）：
 * 列表展示所有节假日，右下角加号新增，卡片上可编辑/删除。
 * 编辑弹窗里设置放假区间和若干补课日（补课日补哪个星期几的课）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolidayPage(prefs: android.content.SharedPreferences, onBack: () -> Unit) {
    val hapticCtx = LocalContext.current
    var holidays by remember { mutableStateOf(loadHolidays(prefs)) }
    var editing by remember { mutableStateOf<HolidayItem?>(null) }   // null=不显示弹窗
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HolidayItem?>(null) }

    val persist: (List<HolidayItem>) -> Unit = {
        holidays = it.toMutableList()
        saveHolidays(prefs, holidays)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("节假日设置（${holidays.size}个）") },
                navigationIcon = { IconButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); adding = true }, containerColor = AppC.success) {
                Icon(Icons.Default.Add, "添加节假日", tint = Color.White)
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            if (holidays.isEmpty()) {
                item { EmptyHint("还没有节假日", "点右下角 ＋ 添加，如国庆放假与调休补课") }
            }
            items(holidays.size) { i ->
                val h = holidays[i]
                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name.ifBlank { "未命名节假日" }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("放假 ${fmtShort(h.offStart)} ~ ${fmtShort(h.offEnd)}", fontSize = 13.sp, color = AppC.accent)
                            if (h.makeups.isNotEmpty()) {
                                Text(
                                    "补课: " + h.makeups.joinToString("、") { "${fmtShort(it.date)} 上${fmtShort(it.sourceDate)}的课" },
                                    fontSize = 12.sp, color = AppC.textMuted
                                )
                            } else {
                                Text("未设置补课日", fontSize = 12.sp, color = AppC.placeholder)
                            }
                        }
                        IconButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); editing = h }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp)) }
                        IconButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); pendingDelete = h }) { Icon(Icons.Default.Delete, null, tint = AppC.danger, modifier = Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }

    if (adding) {
        HolidayEditDialog(
            initial = null,
            onDismiss = { adding = false },
            onConfirm = { item -> tickHaptic(hapticCtx, HapticKind.TAP); persist(holidays + item); adding = false }
        )
    }
    editing?.let { cur ->
        HolidayEditDialog(
            initial = cur,
            onDismiss = { editing = null },
            onConfirm = { item -> tickHaptic(hapticCtx, HapticKind.TAP); persist(holidays.map { if (it.id == item.id) item else it }); editing = null }
        )
    }
    pendingDelete?.let { del ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = AppC.card, shape = RoundedCornerShape(16.dp),
            title = { Text("删除节假日") },
            text = { Text("确定删除「${del.name.ifBlank { "未命名节假日" }}」吗？课表将恢复显示这些日期的课程。") },
            confirmButton = { TextButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); persist(holidays.filter { it.id != del.id }); pendingDelete = null }) { Text("删除", color = AppC.danger) } },
            dismissButton = { TextButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); pendingDelete = null }) { Text("取消") } }
        )
    }
}

private fun fmtShort(iso: String): String =
    parseHolidayDate(iso)?.format(DateTimeFormatter.ofPattern("MM/dd")) ?: iso

/** 新增/编辑弹窗：放假区间 + 补课日列表。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HolidayEditDialog(
    initial: HolidayItem?,
    onDismiss: () -> Unit,
    onConfirm: (HolidayItem) -> Unit,
) {
    val hapticCtx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var offStart by remember { mutableStateOf(initial?.offStart ?: "") }
    var offEnd by remember { mutableStateOf(initial?.offEnd ?: "") }
    // 必须用不可变 List + 整体换引用：mutableStateOf 观察不到 MutableList 的原地
    // add/removeAt（不触发重组），会导致"点了没反应、UI 陈旧、按旧索引越界闪退"。
    var makeups by remember { mutableStateOf<List<HolidayMakeup>>(initial?.makeups?.map { it.copy() } ?: emptyList()) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppC.card,
        shape = RoundedCornerShape(16.dp),
        title = { Text(if (initial == null) "添加节假日" else "编辑节假日") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField("名称（如 国庆节）", name) { name = it }
                DateField("放假开始", offStart, "点击选择日期") { offStart = it }
                // 结束日历默认跳到开始那个月，省得手动翻月；开始没填则回到今天
                DateField("放假结束", offEnd, "点击选择日期", focus = offStart) { offEnd = it }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("补课安排（补课日当天显示所选日期的课）", fontSize = 12.sp, color = AppC.textMuted)
                makeups.forEachIndexed { idx, m ->
                    Surface(color = AppC.cardTint, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        // 两个日期框各占整行、标签在框上方；删除按钮放右侧、垂直居中
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                DateField("补课日", m.date, "点击选择日期") { v -> makeups = makeups.toMutableList().also { it[idx] = m.copy(date = v) } }
                                DateField("补哪天的课", m.sourceDate, "点击选择日期") { v -> makeups = makeups.toMutableList().also { it[idx] = m.copy(sourceDate = v) } }
                            }
                            IconButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); makeups = makeups.filterIndexed { i, _ -> i != idx } }) {
                                Icon(Icons.Default.Delete, "删除补课日", tint = AppC.danger, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); makeups = makeups + HolidayMakeup("", "") }, modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, AppC.accent)) { Text("＋ 添加补课日", color = AppC.accent, fontSize = 13.sp) }
                if (error.isNotEmpty()) Text(error, fontSize = 12.sp, color = AppC.danger)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tickHaptic(hapticCtx, HapticKind.TAP)
                val s = parseHolidayDate(offStart)
                var e = parseHolidayDate(offEnd)
                // 结束留空时默认等于开始（放一天）
                if (e == null && s != null) e = s
                val badMakeupDate = makeups.any { parseHolidayDate(it.date) == null }
                val badMakeupSrc = makeups.any { parseHolidayDate(it.sourceDate) == null }
                when {
                    name.isBlank() -> error = "请填写名称"
                    s == null -> error = "放假开始日期格式应为 YYYY-MM-DD"
                    e == null -> error = "放假结束日期格式应为 YYYY-MM-DD"
                    e.isBefore(s) -> error = "结束日期不能早于开始日期"
                    badMakeupDate -> error = "补课日期格式应为 YYYY-MM-DD"
                    badMakeupSrc -> error = "请为每个补课日填写\"补哪天的课\"（YYYY-MM-DD）"
                    else -> {
                        // 结束早于开始已在上面拦截；这里统一存标准 ISO 文本
                        onConfirm(
                            HolidayItem(
                                id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name.trim(),
                                offStart = s.toString(),
                                offEnd = e.toString(),
                                makeups = makeups.map { it.copy(date = parseHolidayDate(it.date)!!.toString(), sourceDate = parseHolidayDate(it.sourceDate)!!.toString()) }.toMutableList(),
                            )
                        )
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { tickHaptic(hapticCtx, HapticKind.TAP); onDismiss() }) { Text("取消") } }
    )
}
