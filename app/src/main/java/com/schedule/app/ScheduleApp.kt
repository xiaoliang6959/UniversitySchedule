package com.schedule.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val slotRows = mutableMapOf<String, Int>().apply {
    for (i in 1..12) this["第${i}节"] = i - 1
    for (i in 1..6) this["第${i * 2 - 1}-${i * 2}节"] = (i - 1) * 2
}

@Composable
fun FormField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) { Text(label, fontSize = 12.sp, color = Color.Gray); OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)) }
}
fun getWeekDates(config: ScheduleConfig, week: Int): List<LocalDate> { val start = LocalDate.parse(config.startDate); val mon = start.plusDays((week - 1) * 7L); return (0L..6L).map { mon.plusDays(it) } }
fun detectWeek(config: ScheduleConfig): Int { val today = LocalDate.now(); for (w in 1..config.totalWeeks) { val d = getWeekDates(config, w); if (!today.isBefore(d[0]) && !today.isAfter(d[6])) return w }; return 1 }
fun loadCoursesFromPrefs(prefs: android.content.SharedPreferences): List<Course> { val json = prefs.getString("courses_json", "") ?: ""; if (json.isEmpty()) return emptyList(); return json.split("|||").mapNotNull { p -> val f = p.split("###"); if (f.size >= 11) try { Course(f[0],f[1],f[2],f[3].toInt(),f[4],f[5].toInt(),f[6].toInt(),f[7],f[8].toDoubleOrNull()?:2.0,f[9],f[10],if(f.size>11)f[11] else "auto") } catch (_: Exception) { null } else null } }
fun saveCoursesToPrefs(prefs: android.content.SharedPreferences, courses: List<Course>) { val json = courses.joinToString("|||") { c -> listOf(c.name,c.teacher,c.room,c.day.toString(),c.slot,c.weekStart.toString(),c.weekEnd.toString(),c.type,c.credit.toString(),c.exam,c.unit,c.color).joinToString("###") }; prefs.edit().putString("courses_json", json).apply() }
fun loadConfigFromPrefs(prefs: android.content.SharedPreferences) = ScheduleConfig(prefs.getInt("totalWeeks", 5), prefs.getString("startDate", "2026-09-01") ?: "2026-09-01", prefs.getString("school", "某某大学") ?: "某某大学", prefs.getString("major", "某某专业") ?: "某某专业")
fun saveConfigToPrefs(prefs: android.content.SharedPreferences, config: ScheduleConfig) { prefs.edit().putInt("totalWeeks", config.totalWeeks).putString("startDate", config.startDate).putString("school", config.school).putString("major", config.major).apply() }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun getCourseColor(name: String, color: String, colorMap: MutableMap<String, Pair<Color, Color>>, index: Int): Pair<Color, Color> {
    val palette = listOf(
        Color(0xFFFF5252) to Color(0xFFFFFFFF),
        Color(0xFFFF9100) to Color(0xFFFFFFFF),
        Color(0xFFFFD54F) to Color(0xFF000000),
        Color(0xFF66BB6A) to Color(0xFFFFFFFF),
        Color(0xFF26C6DA) to Color(0xFFFFFFFF),
        Color(0xFF42A5F5) to Color(0xFFFFFFFF),
        Color(0xFFAB47BC) to Color(0xFFFFFFFF),
        Color(0xFFEC407A) to Color(0xFFFFFFFF),
        Color(0xFF8BC34A) to Color(0xFFFFFFFF),
        Color(0xFF26A69A) to Color(0xFFFFFFFF)
    )
    if (colorMap.containsKey(name)) return colorMap[name]!!
    val (bg, fg) = palette[index % palette.size]
    colorMap[name] = bg to fg; return bg to fg
}

val dayNames = listOf("","周一","周二","周三","周四","周五","周六","周日")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScheduleApp(onExport: () -> Unit, onImport: () -> Unit, reloadVersion: androidx.compose.runtime.MutableIntState = androidx.compose.runtime.mutableIntStateOf(0)) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
    var courses by remember { mutableStateOf<List<Course>>(loadCoursesFromPrefs(prefs)) }
    var config by remember { mutableStateOf<ScheduleConfig>(loadConfigFromPrefs(prefs)) }
    var currentWeek by remember { mutableIntStateOf(detectWeek(config)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showDetailCourses by remember { mutableStateOf<List<Course>?>(null) }
    val colorMap = remember { mutableMapOf<String, Pair<Color, Color>>() }
    val slots = remember(refreshKey) { loadSlots(prefs) }

    // 导入数据后从 prefs 重新加载，不跳转页面
    LaunchedEffect(reloadVersion.intValue) {
        if (reloadVersion.intValue > 0) {
            courses = loadCoursesFromPrefs(prefs)
            config = loadConfigFromPrefs(prefs)
            currentWeek = detectWeek(config)
            refreshKey++
        }
    }

    var page by remember { mutableStateOf("main") }
    var selectedCourseName by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        courses = loadCoursesFromPrefs(prefs)
        config = loadConfigFromPrefs(prefs)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    val reload = { refreshKey++ }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    // 系统返回键拦截
    BackHandler(enabled = drawerState.isOpen) {
        drawerScope.launch { drawerState.close() }
    }
    if (page != "main") {
        BackHandler {
            when (page) {
                "manage", "generalInfo", "settings", "timeTable" -> page = "main"
                "courseDetail" -> page = "manage"
                "about" -> page = "settings"
            }
            refreshKey++
        }
    }
    BackHandler(showDetailCourses != null) { showDetailCourses = null }

    var previousPage by remember { mutableStateOf("main") }
    val pageDepth = mapOf("main" to 0, "manage" to 1, "generalInfo" to 1, "settings" to 1, "timeTable" to 1, "courseDetail" to 2, "about" to 2)

    AnimatedContent(targetState = page, transitionSpec = {
        val depthDiff = (pageDepth[targetState] ?: 0) - (pageDepth[previousPage] ?: 0)
        if (depthDiff >= 0) {
            slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)) togetherWith
                slideOutHorizontally(tween(300)) { -it / 3 } + fadeOut(tween(150))
        } else {
            slideInHorizontally(tween(300)) { -it / 3 } + fadeIn(tween(150)) togetherWith
                slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300))
        }.using(SizeTransform(clip = false))
    }, label = "page") { target ->
        previousPage = target
        when (target) {
        "main" -> MainPage(courses, config, currentWeek, { currentWeek = it }, { page = "manage" }, { page = "generalInfo" }, colorMap, slots, { page = "timeTable" }, { page = "settings" }, { page = "about" }, onCourseClick = { showDetailCourses = it }, drawerState)
        "manage" -> ManagePage(courses, prefs, { page = "main"; reload() }, { selectedCourseName = it; page = "courseDetail" }, { reload() }, colorMap, refreshKey)
        "courseDetail" -> CourseDetailPage(courses, selectedCourseName, prefs, { page = "manage" }, { reload() }, colorMap, slots)
        "generalInfo" -> GeneralInfoPage(config, prefs, { page = "main"; reload() }, onExport, onImport, slots)
        "settings" -> SettingsPage({ page = "main" }, onExport, onImport, prefs, { page = "about" }, {
            courses = emptyList()
            config = ScheduleConfig()
            refreshKey++
        })
        "about" -> AboutPage({ page = "settings" })
        "timeTable" -> TimeTableSettingsPage(prefs, { page = "main"; refreshKey++ }, { refreshKey++ }, slots)
        }
    }

    // 课程详情弹窗（在 ScheduleApp 级别，覆盖所有页面）
    showDetailCourses?.let { list ->
        val isConflict = list.size > 1
        AlertDialog(
            onDismissRequest = { showDetailCourses = null },
            containerColor = Color.White,
            shape = RoundedCornerShape(16.dp),
            title = {
                Column {
                    Text("课程详情", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(if (isConflict) "有${list.size}节课程冲突" else "", fontSize = 12.sp, color = Color.Gray)
                }
            },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    list.forEachIndexed { idx, c ->
                        if (idx > 0) Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${c.type} · ${c.credit}学分 · ${c.exam}", fontSize = 12.sp, color = Color.Gray)
                        Text("教师: ${c.teacher}", fontSize = 13.sp)
                        Text("教室: ${c.room}", fontSize = 13.sp)
                        Text("时间: ${dayNames[c.day]} ${c.slot}", fontSize = 13.sp)
                        Text("周次: 第${c.weekStart}~${c.weekEnd}周", fontSize = 13.sp)
                        Text("单位: ${c.unit}", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDetailCourses = null }) { Text("关闭") } }
        )
    }
}

// ====== 主页 ======
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainPage(courses: List<Course>, config: ScheduleConfig, currentWeek: Int,
             onWeekChange: (Int) -> Unit, onManage: () -> Unit, onGeneralInfo: () -> Unit,
             colorMap: MutableMap<String, Pair<Color, Color>>, slots: MutableList<SlotItem>,
             onTimeTable: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit,
             onCourseClick: (List<Course>) -> Unit, drawerState: DrawerState) {
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(drawerState = drawerState, drawerContent = {
        ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
            Row(Modifier.fillMaxWidth().padding(20.dp, 24.dp, 20.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painter = painterResource(id = R.drawable.ic_about), contentDescription = "Logo",
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("大学课程表", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
                    Text("v1.0", fontSize = 11.sp, color = Color(0xFF888888))
                }
            }
            Spacer(Modifier.height(4.dp))
            DrawerMenuItem(icon = Icons.Default.Info, label = "总体信息", subtitle = "周期、学校、专业", onClick = { onGeneralInfo() })
            DrawerMenuItem(icon = Icons.Default.AccessTime, label = "时间表设置", subtitle = "当前${slots.size}节", onClick = { onTimeTable() })
            DrawerMenuItem(icon = Icons.Default.Edit, label = "课程管理", subtitle = "${courses.toSchedules().size}门课程", onClick = { onManage() })
            DrawerMenuItem(icon = Icons.Default.Settings, label = "设置", subtitle = "导入、导出、清空", onClick = { onSettings() })
        }
    }) {
        Column(Modifier.fillMaxSize().background(Color(0xFFFAFBFC))) {
            // 标题栏（带汉堡按钮）
            Surface(color = Color(0xFF29B6F6), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(4.dp, 6.dp, 16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                        scope.launch { drawerState.open() }
                    }) { Icon(Icons.Default.Menu, "菜单", tint = Color.White, modifier = Modifier.size(24.dp)) }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(config.school, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(config.major, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(48.dp))
                    }
                }
            }
            val barState = rememberScrollState()
            val density = LocalDensity.current
            val screenWidth = with(density) { LocalContext.current.resources.displayMetrics.widthPixels.toDp().roundToPx() }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(100)
                val halfScreen = screenWidth / 2
                val target = with(density) { ((currentWeek - 1) * 78).dp.roundToPx() }
                barState.animateScrollTo((target - halfScreen + 40).coerceAtLeast(0))
            }
            Row(Modifier.background(Color.White).fillMaxWidth().horizontalScroll(barState), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(6.dp))
                val maxWeek = config.totalWeeks.coerceAtMost(20)
                for (w in 1..maxWeek) {
                    val isActive = w == currentWeek
                    Surface(onClick = { onWeekChange(w) }, shape = RoundedCornerShape(16.dp), color = if (isActive) Color(0xFF3498DB) else Color.White, border = BorderStroke(1.5.dp, Color(0xFF3498DB))) {
                        Text("第${w}周", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = if (isActive) Color.White else Color(0xFF3498DB), fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(6.dp))
            }
            val dates = getWeekDates(config, currentWeek)
            val today = LocalDate.now()
            val todayDoW = if (!today.isBefore(dates[0]) && !today.isAfter(dates[6])) today.dayOfWeek.value else -1
            Row(Modifier.background(Color(0xFFF0F2F5)).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(50.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {}
                for (i in 0..6) {
                    val isToday = (i + 1) == todayDoW
                    val bgColor = if (isToday) Color(0xFFBBDEFB) else Color.Transparent
                    Column(modifier = Modifier.weight(1f).padding(2.dp, 6.dp).background(bgColor, RoundedCornerShape(6.dp)), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dayNames[i + 1], color = Color(0xFF333333), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(dates[i].format(DateTimeFormatter.ofPattern("MM/dd")), color = Color(0xFF888888), fontSize = 10.sp)
                    }
                }
            }
            Box(Modifier.weight(1f).windowInsetsPadding(WindowInsets.navigationBars)) { CourseTable(courses, currentWeek, colorMap, slots, onCourseClick = onCourseClick) }
        }
    }
}

@Composable
fun DrawerMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF555555), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(subtitle, fontSize = 11.sp, color = Color(0xFF888888)) }
        }
    }
}

// ====== 课程表 ======
@Composable
fun CourseTable(courses: List<Course>, currentWeek: Int, colorMap: MutableMap<String, Pair<Color, Color>>, slots: MutableList<SlotItem>, onCourseClick: (List<Course>) -> Unit) {
    val ROW_H = 72; val TIME_COL_W = 50; val density = LocalDensity.current
    val scrW = LocalContext.current.resources.displayMetrics.widthPixels
    val timePx = with(density) { TIME_COL_W.dp.roundToPx() }
    val colDp = with(density) { ((scrW - timePx) / 7).toDp() }
    val totalH = slots.size * ROW_H

    val slotIndexMap = mutableMapOf<String, Int>()
    for ((i, s) in slots.withIndex()) { slotIndexMap[s.name] = i }

    fun parseSlotSpan(slotStr: String): Pair<Int, Int> {
        val parts = if (slotStr.contains("\u3001")) slotStr.split("\u3001").map { it.trim() } else listOf(slotStr)
        val indices = parts.mapNotNull { slotIndexMap[it] }.sorted()
        if (indices.isEmpty()) return -1 to 0
        return indices.first() to indices.size
    }

    // Step 1: build cellMap[row][col] = list of courses
    val cellMap = Array(slots.size) { Array(7) { mutableListOf<Course>() } }
    courses.forEach { c ->
        if (currentWeek !in c.weekStart..c.weekEnd || c.room == "-") return@forEach
        val (sr, sp) = parseSlotSpan(c.slot)
        if (sr < 0) return@forEach
        for (rr in sr until minOf(sr + sp, slots.size)) cellMap[rr][c.day - 1].add(c)
    }

    // Step 2: find conflict rows for each col
    fun isConflictRow(r: Int, col: Int) = cellMap[r][col].size >= 2

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(totalH.dp)) {
            // Background + time column
            slots.forEachIndexed { r, si ->
                if (r % 6 == 0 && r > 0) Box(Modifier.fillMaxWidth().height(8.dp).offset(y = (r * ROW_H).dp).background(Color(0xFFEEF1F5)))
                Box(Modifier.width(TIME_COL_W.dp).offset(y = (r * ROW_H).dp).height(ROW_H.dp).background(Color(0xFFF8F9FA)).padding(2.dp), contentAlignment = Alignment.Center) {
                    Text(si.name + "\n" + si.startTime + "-" + si.endTime, fontSize = 8.sp, color = Color(0xFF555555), textAlign = TextAlign.Center, lineHeight = 10.sp)
                }
            }
            for (r in slots.indices) for (c in 0..6) {
                Box(Modifier.offset(x = (TIME_COL_W + c * with(density) { colDp.roundToPx() / density.density }).dp, y = (r * ROW_H).dp).size(colDp, ROW_H.dp).background(if ((r / 6) % 2 == 0) Color(0xFFF8F9FA) else Color(0xFFFAFBFC)))
            }

            // Step 3: render each cell
            val rendered = Array(slots.size) { BooleanArray(7) }
            for (r in slots.indices) for (col in 0..6) {
                if (rendered[r][col]) continue
                val list = cellMap[r][col]
                if (list.isEmpty()) continue

                val xOffset = (TIME_COL_W + col * with(density) { colDp.roundToPx() / density.density }).dp
                val yOffset = (r * ROW_H).dp

                if (list.size >= 2) {
                    // Conflict: find same-set consecutive rows
                    val unique = list.map { it.name }.toSet()
                    var endRow = r
                    while (endRow + 1 < slots.size) {
                        val nl = cellMap[endRow + 1][col]
                        if (nl.size >= 2 && nl.map { it.name }.toSet() == unique) endRow++ else break
                    }
                    val span = endRow - r + 1
                    val bg = Color(0xFFFFF3CD); val fg = Color(0xFF856404)
                    Box(Modifier.offset(x = xOffset, y = yOffset).width(colDp).height((ROW_H * span).dp)
                        .padding(2.dp).background(bg, RoundedCornerShape(8.dp)).padding(3.dp)
                        .clickable { onCourseClick(list) }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("\u8BFE\u7A0B\u51B2\u7A81", color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 13.sp)
                            Text("${unique.size}\u95E8\u51B2\u7A81", color = fg.copy(alpha = 0.8f), fontSize = 9.sp)
                        }
                    }
                    for (rr in r..endRow) rendered[rr][col] = true
                } else {
                    // Single course: render at current row r (not sr)
                    // r is the first unrendered row; sr may be in a conflict zone
                    val c = list.first()
                    val ci = courses.indexOf(c)
                    val (sr, sp) = parseSlotSpan(c.slot)
                    val (bg, fg) = getCourseColor(c.name, c.color, colorMap, ci)
                    var h = 0
                    for (rr in r until minOf(sr + sp, slots.size)) {
                        if (cellMap[rr][col].size >= 2) break
                        h++
                    }
                    if (h == 0) h = 1
                    Box(Modifier.offset(x = xOffset, y = (r * ROW_H).dp)
                        .width(colDp).height((ROW_H * h).dp).padding(2.dp).background(bg, RoundedCornerShape(8.dp)).padding(3.dp)
                        .clickable { onCourseClick(listOf(c)) }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(c.name, color = fg, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 11.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(c.room, color = fg.copy(alpha = 0.7f), fontSize = 8.sp)
                        }
                    }
                    for (rr in r until r + h) if (rr < slots.size) rendered[rr][col] = true
                }
            }

            val special = courses.filter { currentWeek in it.weekStart..it.weekEnd && it.room == "-" }
            if (special.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().offset(y = (totalH + 8).dp).background(Color(0xFFF8F9FA)).padding(12.dp, 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("其他安排", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF555555))
                            special.forEach { Text(it.name + "\uff08" + it.teacher + "\uff09", fontSize = 12.sp, color = Color(0xFF888888)) }
                        }
                    }
                }
            }
        }
    }
}

// ====== 课程管理页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagePage(courses: List<Course>, prefs: android.content.SharedPreferences, onBack: () -> Unit, onCourseClick: (String) -> Unit, onAddCourse: () -> Unit, colorMap: MutableMap<String, Pair<Color, Color>>, refreshKey: Int) {
    val schedules = remember(courses) { courses.toSchedules() }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CourseSchedule?>(null) }
    // Multi-select state
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf(setOf<String>()) }
    val allSelected = selectedNames.size == schedules.size && schedules.isNotEmpty()
    // Delete confirmation state
    var pendingDeleteNames by remember { mutableStateOf<List<String>>(emptyList()) }

    Scaffold(
        topBar = {
            if (multiSelectMode) {
                TopAppBar(
                    title = { Text("已选择 ${selectedNames.size} 项") },
                    navigationIcon = { IconButton(onClick = { multiSelectMode = false; selectedNames = emptySet() }) { Icon(Icons.Default.Close, "退出多选") } },
                    actions = {
                        IconButton(onClick = { selectedNames = if (allSelected) emptySet() else schedules.map { it.name }.toSet() }) { Icon(if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, "全选") }
                        IconButton(onClick = { pendingDeleteNames = selectedNames.toList() }) { Icon(Icons.Default.Delete, "删除选中", tint = Color(0xFFE74C3C)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF29B6F6), titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White)
                )
            } else {
                TopAppBar(title = { Text("课程管理（${schedules.size}门）") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF29B6F6), titleContentColor = Color.White, navigationIconContentColor = Color.White))
            }
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                if (!multiSelectMode) {
                    FloatingActionButton(onClick = { multiSelectMode = true }, containerColor = Color(0xFF3498DB)) {
                        Icon(Icons.Default.Checklist, "多选", tint = Color.White)
                    }
                }
                FloatingActionButton(onClick = { showAddDialog = true }, containerColor = Color(0xFF27AE60)) { Icon(Icons.Default.Add, "添加课程", tint = Color.White) }
            }
        }
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(padding)) {
            items(schedules.size) { i ->
                val sch = schedules[i]; val (bg, fg) = getCourseColor(sch.name, sch.color, colorMap, i)
                val checked = selectedNames.contains(sch.name)
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().clickable {
                    if (multiSelectMode) {
                        selectedNames = if (checked) selectedNames - sch.name else selectedNames + sch.name
                    } else {
                        onCourseClick(sch.name)
                    }
                }) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (multiSelectMode) {
                                Icon(if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                                    tint = if (checked) Color(0xFF3498DB) else Color(0xFFBDBDBD), modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Surface(shape = RoundedCornerShape(6.dp), color = bg) { Text(sch.name, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.weight(1f))
                            if (!multiSelectMode) {
                                IconButton(onClick = { editingCourse = sch }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp)) }
                                IconButton(onClick = { pendingDeleteNames = listOf(sch.name) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFE74C3C), modifier = Modifier.size(20.dp)) }
                            }
                        }
                        Spacer(Modifier.height(4.dp)); Text("${sch.teacher} · ${sch.credit}学分 · ${sch.type} · ${sch.exam}", fontSize = 12.sp, color = Color.Gray); Text("${sch.slots.size}个时间段", fontSize = 11.sp, color = Color(0xFF3498DB))
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (pendingDeleteNames.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteNames = emptyList() },
            containerColor = Color.White, shape = RoundedCornerShape(16.dp),
            title = { Text("确认删除") },
            text = { Text("确定要删除 ${pendingDeleteNames.size} 门课程吗？此操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                val u = courses.toMutableList()
                u.removeAll { c -> pendingDeleteNames.any { it == c.name } }
                saveCoursesToPrefs(prefs, u)
                pendingDeleteNames = emptyList()
                multiSelectMode = false; selectedNames = emptySet(); onAddCourse()
            }) { Text("删除", color = Color(0xFFE74C3C)) } },
            dismissButton = { TextButton(onClick = { pendingDeleteNames = emptyList() }) { Text("取消") } }
        )
    }

    if (showAddDialog) AddCourseDialog(onDismiss = { showAddDialog = false }, onConfirm = { n, t, cr, un ->
        val updated = courses.toMutableList()
        updated.add(Course(n, t, "-", 1, "第1节", 1, 1, "必修", cr, "考查", un, "auto"))
        saveCoursesToPrefs(prefs, updated)
        showAddDialog = false
        onAddCourse()
    })

    editingCourse?.let { sch ->
        EditCourseDialog(sch = sch, onDismiss = { editingCourse = null }, onConfirm = { n, t, cr, un, clr ->
            val updated = courses.toMutableList()
            updated.removeAll { it.name == sch.name }
            val newFlat = sch.copy(name = n, teacher = t, credit = cr, unit = un, color = clr).toFlatCourses()
            if (newFlat.isEmpty()) {
                updated.add(Course(n, t, "-", 1, "第1节", 1, 1, "必修", cr, "考查", un, clr))
            } else {
                updated.addAll(newFlat)
            }
            saveCoursesToPrefs(prefs, updated)
            editingCourse = null
            onAddCourse()
        })
    }
}

@Composable
fun AddCourseDialog(onDismiss: () -> Unit, onConfirm: (String, String, Double, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var teacher by remember { mutableStateOf("") }; var credit by remember { mutableStateOf("2.0") }; var unit by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(16.dp), title = { Text("添加课程") },
        text = { Column { FormField("课程名称", name) { name = it }; FormField("任课教师", teacher) { teacher = it }; FormField("学分", credit) { credit = it }; FormField("开课单位", unit) { unit = it } } },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), teacher.trim(), credit.toDoubleOrNull() ?: 2.0, unit.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditCourseDialog(sch: CourseSchedule, onDismiss: () -> Unit, onConfirm: (String, String, Double, String, String) -> Unit) {
    var name by remember { mutableStateOf(sch.name) }
    var teacher by remember { mutableStateOf(sch.teacher) }
    var credit by remember { mutableStateOf(sch.credit.toString()) }
    var unit by remember { mutableStateOf(sch.unit) }
    var selectedColor by remember { mutableStateOf(sch.color) }

    val colorOptions = listOf(
        "auto" to null,
        "#FF5252,#FFFFFF" to Color(0xFFFF5252),
        "#FF9100,#FFFFFF" to Color(0xFFFF9100),
        "#FFD54F,#000000" to Color(0xFFFFD54F),
        "#66BB6A,#FFFFFF" to Color(0xFF66BB6A),
        "#26C6DA,#FFFFFF" to Color(0xFF26C6DA),
        "#42A5F5,#FFFFFF" to Color(0xFF42A5F5),
        "#AB47BC,#FFFFFF" to Color(0xFFAB47BC),
        "#EC407A,#FFFFFF" to Color(0xFFEC407A),
        "#8BC34A,#FFFFFF" to Color(0xFF8BC34A),
        "#26A69A,#FFFFFF" to Color(0xFF26A69A)
    )

    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(16.dp), title = { Text("编辑课程") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FormField("课程名称", name) { name = it }
                FormField("任课教师", teacher) { teacher = it }
                FormField("学分", credit) { credit = it }
                FormField("开课单位", unit) { unit = it }
                Spacer(Modifier.height(8.dp))
                Text("课程颜色", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(4.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    colorOptions.forEach { (colorStr, color) ->
                        val isSelected = selectedColor == colorStr
                        val bgColor = color ?: Color(0xFFF0F0F0)
                        Surface(
                            onClick = { selectedColor = colorStr },
                            shape = RoundedCornerShape(8.dp),
                            color = bgColor,
                            border = BorderStroke(2.dp, if (isSelected) Color(0xFF3498DB) else Color.Transparent),
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (color == null) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("自动", fontSize = 8.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), teacher.trim(), credit.toDoubleOrNull() ?: sch.credit, unit.trim(), selectedColor) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// ====== 课程详情页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailPage(courses: List<Course>, courseName: String, prefs: android.content.SharedPreferences, onBack: () -> Unit, onSlotAdded: () -> Unit, colorMap: MutableMap<String, Pair<Color, Color>>, slots: MutableList<SlotItem>) {
    val schedule = remember(courses, courseName) { courses.toSchedules().find { it.name == courseName } }
    var showAddSlot by remember { mutableStateOf(false) }
    var showEditSlot by remember { mutableIntStateOf(-1) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(courseName) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF29B6F6), titleContentColor = Color.White, navigationIconContentColor = Color.White)) },
        floatingActionButton = { FloatingActionButton(onClick = { showAddSlot = true }, containerColor = Color(0xFF27AE60)) { Icon(Icons.Default.Add, "添加时间段", tint = Color.White) } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            schedule?.let { sch ->
                Surface(color = Color.White, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp), shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp)) { Text("时间安排", fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("${sch.teacher} · ${sch.credit}学分 · ${sch.type} · ${sch.exam}", fontSize = 13.sp, color = Color.Gray); Text(sch.unit, fontSize = 12.sp, color = Color(0xFF888888)) } }
                LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(sch.slots.size) { i -> val ts = sch.slots[i]; Surface(color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${dayNames[ts.day]}  ${ts.slot}", fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(ts.room, fontSize = 12.sp, color = Color.Gray); Text("第${ts.weekStart}~${ts.weekEnd}周", fontSize = 11.sp, color = Color(0xFF888888)) }; IconButton(onClick = { showEditSlot = i }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp)) }; IconButton(onClick = { val u = courses.toMutableList(); u.removeAll { it.name == courseName }; sch.slots.removeAt(i); u.addAll(sch.toFlatCourses()); saveCoursesToPrefs(prefs, u); onSlotAdded() }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFE74C3C), modifier = Modifier.size(20.dp)) } } } } }
            } ?: Text("未找到课程", modifier = Modifier.padding(16.dp))
        }
    }
    if (showAddSlot && schedule != null) AddSlotDialog(onDismiss = { showAddSlot = false }, onConfirm = { d, s, ws, we, room -> val u = courses.toMutableList(); u.removeAll { it.name == courseName }; schedule.slots.add(TimeSlot(d, s, room, ws, we)); u.addAll(schedule.toFlatCourses()); saveCoursesToPrefs(prefs, u); showAddSlot = false; onSlotAdded() }, slots = slots)

    if (showEditSlot >= 0 && schedule != null) {
        val ts = schedule.slots[showEditSlot]
        var editDay by remember { mutableIntStateOf(ts.day) }
        var editRoom by remember { mutableStateOf(ts.room) }
        var editWS by remember { mutableStateOf(ts.weekStart.toString()) }
        var editWE by remember { mutableStateOf(ts.weekEnd.toString()) }
        val editSelIndices = remember { mutableStateOf(ts.slot.split("、").mapNotNull { name -> slots.indexOfFirst { it.name == name }.takeIf { it >= 0 } }.toMutableSet()) }
        var editSlotError by remember { mutableStateOf(false) }
        @OptIn(ExperimentalLayoutApi::class)
        AlertDialog(onDismissRequest = { showEditSlot = -1 }, containerColor = Color.White, shape = RoundedCornerShape(16.dp),
            title = { Text("编辑时间段") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("星期", fontSize = 12.sp, color = Color.Gray)
                    FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (d in 1..7) { val s = editDay == d; Surface(onClick = { editDay = d }, shape = RoundedCornerShape(8.dp), color = if (s) Color(0xFF3498DB) else Color(0xFFF0F2F5)) { Text(dayNames[d], modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 12.sp, color = if (s) Color.White else Color(0xFF555555)) } }
                    }
                    Text("时间段（可多选，且要连续）", fontSize = 12.sp, color = Color.Gray)
                    FlowRow(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (idx in slots.indices) { val s = editSelIndices.value.contains(idx); Surface(onClick = { val n = editSelIndices.value.toMutableSet(); if (s) n.remove(idx) else n.add(idx); editSelIndices.value = n; editSlotError = false }, shape = RoundedCornerShape(8.dp), color = if (s) Color(0xFF3498DB) else Color(0xFFF0F2F5)) { Text(slots[idx].name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 12.sp, color = if (s) Color.White else Color(0xFF555555)) } }
                    }
                    if (editSlotError) Text("*时间段必须连续", color = Color(0xFFE74C3C), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                    FormField("教室", editRoom) { editRoom = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.weight(1f)) { FormField("起始周", editWS) { editWS = it } }; Box(Modifier.weight(1f)) { FormField("结束周", editWE) { editWE = it } } }
                }
            },
            confirmButton = { TextButton(onClick = {
                val sorted = editSelIndices.value.sorted()
                if (sorted.size >= 2 && sorted.last() - sorted.first() + 1 != sorted.size) { editSlotError = true; return@TextButton }
                val newSlot = sorted.joinToString("、") { slots[it].name }
                val newTS = TimeSlot(editDay, newSlot, editRoom.trim(), editWS.toIntOrNull() ?: 1, editWE.toIntOrNull() ?: 18)
                val u = courses.toMutableList()
                u.removeAll { it.name == courseName }
                schedule.slots[showEditSlot] = newTS
                u.addAll(schedule.toFlatCourses())
                saveCoursesToPrefs(prefs, u)
                showEditSlot = -1
                onSlotAdded()
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showEditSlot = -1 }) { Text("取消") } }
        )
    }
}

// ====== 添加时间段弹窗（用自定义时间表） ======
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddSlotDialog(onDismiss: () -> Unit, onConfirm: (Int, String, Int, Int, String) -> Unit, slots: MutableList<SlotItem>) {
    var day by remember { mutableIntStateOf(1) }
    var selectedSlots by remember { mutableStateOf(mutableSetOf(1)) }
    var slotVer by remember { mutableIntStateOf(0) }
    var slotError by remember { mutableStateOf(false) }
    var ws by remember { mutableStateOf("1") }; var we by remember { mutableStateOf("18") }; var room by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(16.dp), title = { Text("添加时间段") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("星期", fontSize = 12.sp, color = Color.Gray)
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (d in 1..7) { val s = day == d; Surface(onClick = { day = d }, shape = RoundedCornerShape(8.dp), color = if (s) Color(0xFF3498DB) else Color(0xFFF0F2F5)) { Text(dayNames[d], modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 12.sp, color = if (s) Color.White else Color(0xFF555555)) } } }
            Text("时间段（可多选，且要连续）", fontSize = 12.sp, color = Color.Gray)
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (idx in slots.indices) { val s = selectedSlots.contains(idx); Surface(onClick = { val n = selectedSlots.toMutableSet(); if (s) n.remove(idx) else n.add(idx); selectedSlots = n; slotVer++; slotError = false }, shape = RoundedCornerShape(8.dp), color = if (s) Color(0xFF3498DB) else Color(0xFFF0F2F5)) { Text(slots[idx].name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 12.sp, color = if (s) Color.White else Color(0xFF555555)) } } }
            if (slotError) Text("*时间段必须连续", color = Color(0xFFE74C3C), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            FormField("教室", room) { room = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.weight(1f)) { FormField("起始周", ws) { ws = it } }; Box(Modifier.weight(1f)) { FormField("结束周", we) { we = it } } }
        } },
        confirmButton = { TextButton(onClick = {
            val sorted = selectedSlots.sorted()
            if (sorted.size >= 2 && sorted.last() - sorted.first() + 1 != sorted.size) { slotError = true; return@TextButton }
            val slotStr = sorted.joinToString("、") { slots[it].name }
            onConfirm(day, slotStr, ws.toIntOrNull() ?: 1, we.toIntOrNull() ?: 18, room.trim())
        }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// ====== 总体信息页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralInfoPage(config: ScheduleConfig, prefs: android.content.SharedPreferences, onBack: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, slots: MutableList<SlotItem>) {
    var tw by remember { mutableStateOf(config.totalWeeks.toString()) }; var sd by remember { mutableStateOf(config.startDate) }; var sch by remember { mutableStateOf(config.school) }; var maj by remember { mutableStateOf(config.major) }
    Scaffold(topBar = { TopAppBar(title = { Text("总体信息") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF29B6F6), titleContentColor = Color.White, navigationIconContentColor = Color.White)) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField("总周数", tw) { tw = it }
            FormField("学期开始日期 (YYYY-MM-DD)", sd) { sd = it }
            FormField("学校", sch) { sch = it }
            FormField("专业", maj) { maj = it }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { saveConfigToPrefs(prefs, ScheduleConfig(tw.toIntOrNull() ?: 18, sd, sch, maj)); onBack() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3498DB))) { Text("保存设置") }
        }
    }
}

// ====== 设置页（导入、导出、清空） ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(onBack: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, prefs: android.content.SharedPreferences, onAbout: () -> Unit, onDeleteAll: () -> Unit) {
    var showClearDialog by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF29B6F6), titleContentColor = Color.White, navigationIconContentColor = Color.White)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DrawerMenuItem(icon = Icons.Default.Upload, label = "导出数据", subtitle = "导出为Markdown文件", onClick = onExport)
            DrawerMenuItem(icon = Icons.Default.Download, label = "导入数据", subtitle = "从Markdown文件导入", onClick = onImport)
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            DrawerMenuItem(icon = Icons.Default.Delete, label = "删除所有数据", subtitle = "删除总体信息、时间表、课程安排", onClick = { showClearDialog = true })
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            DrawerMenuItem(icon = Icons.Default.Info, label = "关于", subtitle = "大学课程表 v1.0", onClick = onAbout)
        }
    }
    if (showClearDialog) {
        AlertDialog(onDismissRequest = { showClearDialog = false },
            containerColor = Color.White, shape = RoundedCornerShape(16.dp),
            title = { Text("确认删除所有数据") },
            text = { Text("确定要删除所有数据吗？包括总体信息、时间表和课程安排，此操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                prefs.edit()
                    .remove("courses_json")
                    .remove("totalWeeks")
                    .remove("startDate")
                    .remove("school")
                    .remove("major")
                    .putString("slots_json", "")
                    .apply()
                onDeleteAll()
                showClearDialog = false
            }) { Text("确定删除", color = Color(0xFFE74C3C)) } },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }
}

// ====== 时间表设置页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeTableSettingsPage(prefs: android.content.SharedPreferences, onBack: () -> Unit, onRefresh: () -> Unit, slots: MutableList<SlotItem>) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<SlotItem?>(null) }
    // Multi-select state
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    val allSelected = selectedIndices.size == slots.size && slots.isNotEmpty()
    // Delete confirmation state
    var pendingDeleteIndices by remember { mutableStateOf(setOf<Int>()) }

    Scaffold(
        topBar = {
            if (multiSelectMode) {
                TopAppBar(
                    title = { Text("已选择 ${selectedIndices.size} 项") },
                    navigationIcon = { IconButton(onClick = { multiSelectMode = false; selectedIndices = emptySet() }) { Icon(Icons.Default.Close, "退出多选") } },
                    actions = {
                        IconButton(onClick = { selectedIndices = if (allSelected) emptySet() else slots.indices.toSet() }) { Icon(if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, "全选") }
                        IconButton(onClick = { pendingDeleteIndices = selectedIndices.toSet() }) { Icon(Icons.Default.Delete, "删除选中", tint = Color(0xFFE74C3C)) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF29B6F6), titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White)
                )
            } else {
                TopAppBar(title = { Text("时间表设置（${slots.size}节）") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF29B6F6), titleContentColor = Color.White, navigationIconContentColor = Color.White))
            }
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                if (!multiSelectMode) {
                    FloatingActionButton(onClick = { multiSelectMode = true }, containerColor = Color(0xFF3498DB)) {
                        Icon(Icons.Default.Checklist, "多选", tint = Color.White)
                    }
                }
                FloatingActionButton(onClick = { showAddDialog = true }, containerColor = Color(0xFF27AE60)) { Icon(Icons.Default.Add, "添加节次", tint = Color.White) }
            }
        }
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(padding)) {
            items(slots.size) { i ->
                val si = slots[i]
                val checked = selectedIndices.contains(i)
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().clickable {
                    if (multiSelectMode) {
                        selectedIndices = if (checked) selectedIndices - i else selectedIndices + i
                    }
                }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (multiSelectMode) {
                            Icon(if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                                tint = if (checked) Color(0xFF3498DB) else Color(0xFFBDBDBD), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(si.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${si.startTime} - ${si.endTime}", fontSize = 13.sp, color = Color(0xFF3498DB))
                        }
                        if (!multiSelectMode) {
                            IconButton(onClick = { editingSlot = si }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp)) }
                            IconButton(onClick = { pendingDeleteIndices = setOf(i) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFE74C3C), modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (pendingDeleteIndices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteIndices = emptySet() },
            containerColor = Color.White, shape = RoundedCornerShape(16.dp),
            title = { Text("确认删除") },
            text = { Text("确定要删除这 ${pendingDeleteIndices.size} 个节次吗？此操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                val toRemove = pendingDeleteIndices.sortedDescending()
                for (idx in toRemove) slots.removeAt(idx)
                saveSlots(prefs, slots)
                pendingDeleteIndices = emptySet()
                multiSelectMode = false; selectedIndices = emptySet(); onRefresh()
            }) { Text("删除", color = Color(0xFFE74C3C)) } },
            dismissButton = { TextButton(onClick = { pendingDeleteIndices = emptySet() }) { Text("取消") } }
        )
    }

    if (showAddDialog) SlotEditDialog(name = "", start = "", end = "", onDismiss = { showAddDialog = false }, onConfirm = { n, s, e -> slots.add(SlotItem(name = n, startTime = s, endTime = e)); slots.sortBy { it.startTime }; saveSlots(prefs, slots); showAddDialog = false; onRefresh() })

    editingSlot?.let { si ->
        SlotEditDialog(name = si.name, start = si.startTime, end = si.endTime, onDismiss = { editingSlot = null }, onConfirm = { n, s, e -> si.name = n; si.startTime = s; si.endTime = e; slots.sortBy { it.startTime }; saveSlots(prefs, slots); editingSlot = null; onRefresh() })
    }
}

@Composable
fun SlotEditDialog(name: String, start: String, end: String, onDismiss: () -> Unit, onConfirm: (String, String, String) -> Unit) {
    var n by remember { mutableStateOf(name) }; var s by remember { mutableStateOf(start) }; var e by remember { mutableStateOf(end) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(16.dp), title = { Text(if (name.isEmpty()) "添加节次" else "编辑节次") },
        text = { Column { FormField("节次名称", n) { n = it }; FormField("开始时间 (如 08:30)", s) { s = it }; FormField("结束时间 (如 09:15)", e) { e = it } } },
        confirmButton = { TextButton(onClick = { if (n.isNotBlank() && s.isNotBlank() && e.isNotBlank()) onConfirm(n.trim(), s.trim(), e.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// ====== 关于页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPage(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("关于") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF29B6F6), titleContentColor = Color.White, navigationIconContentColor = Color.White)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(Color(0xFFF0F2F5)), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Image(
                painter = painterResource(id = R.drawable.ic_about),
                contentDescription = "App Icon",
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(20.dp))
            )
            Spacer(Modifier.height(16.dp))
            Text("大学课程表", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
            Text("版本 v1.0", fontSize = 13.sp, color = Color(0xFF888888))
            Spacer(Modifier.height(32.dp))
            Surface(color = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Column(Modifier.padding(20.dp)) {
                    AboutItem("功能", "自定义课程时间表")
                    AboutItem("平台", "Android")
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Power by xiaoliang6959", fontSize = 12.sp, color = Color(0xFFBBBBBB))
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun AboutItem(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF555555), modifier = Modifier.width(60.dp))
        Text(value, fontSize = 13.sp, color = Color(0xFF888888), modifier = Modifier.weight(1f))
    }
}