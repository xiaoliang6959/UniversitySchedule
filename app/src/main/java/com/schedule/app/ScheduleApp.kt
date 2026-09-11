package com.schedule.app

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
// 颜色插值：周次胶囊选中态淡入淡出用（显式导入，避免和 animation.core 的通配 lerp 重载打架）
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource

// 显式导入本模块 R：通配符 import 会把 androidx 库里空的 R 类带入作用域，
// 导致 K2 分析器把 R.drawable 解析到库的 R 上（Unresolved reference 'drawable'）。
import com.schedule.app.R
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
fun FormField(label: String, value: String, placeholder: String = "", onValueChange: (String) -> Unit) {
    // placeholder 一律传 lambda：空串时渲染出空文本，等于无提示。
    // 用 if/else 返回 null 会让类型推断丢掉 @Composable，编译不过。
    // 注意 onValueChange 必须是最后一个参数，否则全项目的尾随 lambda 写法会错绑到 placeholder。
    Column(Modifier.fillMaxWidth()) { Text(label, fontSize = 12.sp, color = AppC.textMuted); OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp), placeholder = { Text(placeholder, fontSize = 13.sp, color = AppC.placeholder) }) }
}
/** App 版本号：跟着 build.gradle 的 versionName 走，避免写死多处不同步 */
fun appVersionName(context: android.content.Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "2.0"

/**
 * 这一版**装到手机上的时间**（"版本号"永远都是 2.0，光看它分不清手里是哪个包）。
 * 报了 bug 先用它确认一下测的是不是刚发的那一版，省得改了半天其实在测旧包。
 */
fun appInstallTime(context: android.content.Context): String = runCatching {
    val t = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .format(java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()))
}.getOrNull() ?: "-"

fun getWeekDates(config: ScheduleConfig, week: Int): List<LocalDate> { val start = LocalDate.parse(config.startDate); val mon = start.plusDays((week - 1) * 7L); return (0L..6L).map { mon.plusDays(it) } }
fun detectWeek(config: ScheduleConfig): Int { val today = LocalDate.now(); for (w in 1..config.totalWeeks) { val d = getWeekDates(config, w); if (!today.isBefore(d[0]) && !today.isAfter(d[6])) return w }; return 1 }
fun loadCoursesFromPrefs(prefs: android.content.SharedPreferences): List<Course> { val json = prefs.getString("courses_json", "") ?: ""; if (json.isEmpty()) return emptyList(); return json.split("|||").mapNotNull { p -> val f = p.split("###"); if (f.size >= 11) try { Course(f[0],f[1],f[2],f[3].toInt(),f[4],f[5].toInt(),f[6].toInt(),f[7],f[8].toDoubleOrNull()?:2.0,f[9],f[10],if(f.size>11)f[11] else "auto") } catch (_: Exception) { null } else null } }
fun saveCoursesToPrefs(prefs: android.content.SharedPreferences, courses: List<Course>) { val json = courses.joinToString("|||") { c -> listOf(c.name,c.teacher,c.room,c.day.toString(),c.slot,c.weekStart.toString(),c.weekEnd.toString(),c.type,c.credit.toString(),c.exam,c.unit,c.color).joinToString("###") }; prefs.edit().putString("courses_json", json).apply() }
fun loadConfigFromPrefs(prefs: android.content.SharedPreferences) = ScheduleConfig(prefs.getInt("totalWeeks", 5), prefs.getString("startDate", "2026-09-01") ?: "2026-09-01", prefs.getString("school", "某某大学") ?: "某某大学", prefs.getString("major", "某某专业") ?: "某某专业")
fun saveConfigToPrefs(prefs: android.content.SharedPreferences, config: ScheduleConfig) { prefs.edit().putInt("totalWeeks", config.totalWeeks).putString("startDate", config.startDate).putString("school", config.school).putString("major", config.major).apply() }

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun getCourseColor(name: String, color: String, colorMap: MutableMap<String, Pair<Color, Color>>, index: Int): Pair<Color, Color> {
    // 用户显式选过颜色（含自定义）就用它 —— 以前这里只按 index 轮询色板，
    // color 参数压根没用上，等于"选了颜色不生效"。
    parseCourseColor(color)?.let { (bg, fg) -> return bg to fg }
    if (colorMap.containsKey(name)) return colorMap[name]!!
    val (bg, fg) = COURSE_SWATCHES[index % COURSE_SWATCHES.size].let { it.bg to it.fg }
    colorMap[name] = bg to fg; return bg to fg
}

val dayNames = listOf("","周一","周二","周三","周四","周五","周六","周日")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScheduleApp(
    onExport: () -> Unit,
    onImport: () -> Unit,
    reloadVersion: androidx.compose.runtime.MutableIntState = androidx.compose.runtime.mutableIntStateOf(0),
) {
    val context = LocalContext.current
    // prefs = 设置类（提醒配置、深色模式…）永远在真实文件；
    // dataPrefs = 课表类（学期设置/时间表/课程/节假日），开着"测试课表"时指向另一个文件，
    // 真实课表因此绝不会被覆盖。两者分开是这个功能能安全回退的关键。
    val prefs = context.getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)
    val dataPrefs = TestSchedule.dataStore(context)
    var courses by remember { mutableStateOf<List<Course>>(loadCoursesFromPrefs(dataPrefs)) }
    var config by remember { mutableStateOf<ScheduleConfig>(loadConfigFromPrefs(dataPrefs)) }
    var currentWeek by remember { mutableIntStateOf(detectWeek(config)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showDetailCourses by remember { mutableStateOf<List<Course>?>(null) }
    val colorMap = remember { mutableMapOf<String, Pair<Color, Color>>() }
    val slots = remember(refreshKey) { loadSlots(dataPrefs) }
    // 节假日：随 refreshKey 重载（节假日页保存后 onBack 会 refreshKey++）
    val holidays = remember(refreshKey) { loadHolidays(dataPrefs) }

    // 导入数据后从 prefs 重新加载，不跳转页面
    LaunchedEffect(reloadVersion.intValue) {
        if (reloadVersion.intValue > 0) {
            courses = loadCoursesFromPrefs(dataPrefs)
            config = loadConfigFromPrefs(dataPrefs)
            currentWeek = detectWeek(config)
            refreshKey++
        }
    }

    var page by remember { mutableStateOf("main") }
    var selectedCourseName by remember { mutableStateOf("") }

    // 抽屉的"开合状态"记在这一层，好让它跨页面保留；但抽屉本身只画在 MainPage
    // 内部（见 MainPage 的 ModalNavigationDrawer）。抽屉不在子页面的组件树里，
    // 所以子页面既滑不出抽屉、也压不住返回键。
    val drawerState = remember { EdgeDrawerState() }
    val drawerScope = rememberCoroutineScope()

    // 导航栈：进页 push、返回 pop，返回目标永远是来源页
    // （开发者模式从"设置"进就回"设置"，上课提醒只从抽屉进、从主页进则回主页）
    val navStack = remember { mutableListOf("main") }
    var navDepth by remember { mutableIntStateOf(0) }
    // 本次转场是否为"返回"：返回时让离场的子页画在父页之上（右移露出下层）
    var slideBack by remember { mutableStateOf(false) }
    fun navigateTo(target: String) {
        // 这里刻意不去动抽屉：抽屉算主页面的一部分，进子页时它随主页面一起离场，
        // 但状态仍是"开着"。于是返回主页面时是"一进来就开着"，而不是先关再补一个
        // 打开动画（后者正是之前那种把主页面当新页面加载的观感）。
        if (navStack.last() == target) return
        slideBack = false
        navStack.add(target); navDepth++; page = target
    }
    fun backFromCurrent(refresh: Boolean = false) {
        if (navStack.size > 1) { navStack.removeAt(navStack.lastIndex); navDepth-- }
        slideBack = true
        page = navStack.last()
        if (refresh) refreshKey++
        // 抽屉状态刻意不做任何干预：开着进子页 → 返回时主页面依旧带着开着的抽屉，
        // 想关就自己手动关；关着进子页 → 返回时保持关着。全程跟随用户的手动操作。
    }

    LaunchedEffect(refreshKey) {
        courses = loadCoursesFromPrefs(dataPrefs)
        config = loadConfigFromPrefs(dataPrefs)
    }

    // 课程 / 时间表 / 配置 / 提醒设置变化后重新排期闹钟
    // 只 bump 版本号，实际排期交给下面的 LaunchedEffect（组合后异步执行）——
    // 若在点击帧里同步跑 schedule()，返回动画起手会掉帧。
    var reminderVersion by remember { mutableIntStateOf(0) }
    val rearm: () -> Unit = { reminderVersion++ }
    LaunchedEffect(reminderVersion, refreshKey, reloadVersion.intValue, courses, config, slots) {
        // schedule() 是一串 binder 调用（排闹钟、建渠道），放 IO 线程别占主线程
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ReminderNotifier.schedule(context)
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }

    val reload = { refreshKey++ }

    /**
     * 测试课表开/关后原地把课表数据重读一遍。
     * 不能用 activity recreate()：那会把导航栈重置回主页，用户从开发者模式被弹到课程表。
     * 注意必须重新取 dataStore —— 闭包里捕获的 dataPrefs 是"翻转开关之前"算的，
     * 指向的还是旧文件。
     */
    val reloadData: () -> Unit = {
        val dp = TestSchedule.dataStore(context)
        val newConfig = loadConfigFromPrefs(dp)
        courses = loadCoursesFromPrefs(dp)
        config = newConfig
        // 测试课表的学期起点/周数都和真实数据不同，当前周要重新定位
        currentWeek = detectWeek(newConfig).coerceIn(1, newConfig.totalWeeks.coerceAtLeast(1))
        refreshKey++   // slots/holidays 跟着重载，并触发下面的重新排期
    }

    // 系统返回键拦截（后注册的优先响应）：弹窗 > 抽屉 > 页面返回。
    if (page != "main") {
        BackHandler {
            backFromCurrent(refresh = true)
        }
    }
    BackHandler(enabled = drawerState.isOpen && page == "main") {
        drawerScope.launch { drawerState.animateTo(0f) }
    }
    BackHandler(showDetailCourses != null) { showDetailCourses = null }

    AnimatedContent(targetState = page, transitionSpec = {
        // 方向由 slideBack 决定：它在点击回调里与 page 同一帧同步设置，任何时机读取都可靠；
        // 之前用 navDepth-lastNavDepth 差值判断，transitionSpec 求值时机不稳定会被误判成前进。
        // 原生 Activity 转场 = 位移 + 渐变叠加：
        //   前进 新页从右全宽滑入并淡入，旧页左移 1/3 屏宽并淡出；返回方向完全相反。
        // 曲线：进场 decelerate（冲进来急刹停）、退场 accelerate（起步即加速甩离）。
        val decel = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
        val accel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
        // tween 是泛型函数，提成 val 时必须显式指定类型（否则 T 无法推导）
        val slideIn = tween<IntOffset>(300, easing = decel)
        val slideOut = tween<IntOffset>(300, easing = accel)
        // 淡出刻意延迟 100ms 再开始：旧页在"还没被新页盖住"的前半程保持不透明，
        // 否则它一淡就露出窗口底色，就是之前那个"父页面先变白"的观感。
        val fadeSpec = tween<Float>(200, delayMillis = 100, easing = accel)
        (if (!slideBack) {
            (slideInHorizontally(slideIn) { w: Int -> w } + fadeIn(tween(300)))
                .togetherWith(slideOutHorizontally(slideOut) { w: Int -> -w / 3 } + fadeOut(fadeSpec))
        } else {
            (slideInHorizontally(slideIn) { w: Int -> -w / 3 } + fadeIn(tween(300)))
                .togetherWith(slideOutHorizontally(slideOut) { w: Int -> w } + fadeOut(fadeSpec))
        }).apply {
            // zIndex 按栈深度赋值（官方 NestedMenu 示例方案）：越深的页面越靠上。
            // 前进时进场子页更深→盖住父页；返回时离场子页更深→压在静止父页之上右移。
            // 不能按"进场/离场角色"写死 ±1：那样连续返回会让同一页两次拿到不同 zIndex，
            // 出现"页面2当返回进场目标拿了-1，下次它离场时和父页-1打平"→ 动画消失。
            targetContentZIndex = navDepth.toFloat()
        }.using(SizeTransform(clip = false))
    }, label = "page") { target ->
        when (target) {
        "main" -> MainPage(courses, config, currentWeek, { currentWeek = it }, { navigateTo("manage") }, { navigateTo("generalInfo") }, colorMap, slots, { navigateTo("timeTable") }, { navigateTo("settings") }, { navigateTo("about") }, onCourseClick = { showDetailCourses = it }, drawerState, onReminder = { navigateTo("reminder") }, onHoliday = { navigateTo("holiday") }, holidays = holidays,
            onManual = { navigateTo("manual") }, onAppearance = { navigateTo("appearance") })
        "manage" -> ManagePage(courses, dataPrefs, { backFromCurrent(refresh = true) }, { selectedCourseName = it; navigateTo("courseDetail") }, { reload() }, colorMap, refreshKey, onSwapCourses = { from, delta ->
            // 与拖拽视觉同一帧交换：直接换 courses 状态（同步重组），prefs 只落盘不触发重载
            val groups = courses.toSchedules()
            val to = from + delta
            if (from in groups.indices && to in groups.indices) {
                val g = groups.toMutableList()
                val tmp = g[from]; g[from] = g[to]; g[to] = tmp
                val flat = g.flatMap { it.toFlatCourses() }
                courses = flat
                saveCoursesToPrefs(dataPrefs, flat)
            }
        })
        "courseDetail" -> CourseDetailPage(courses, selectedCourseName, dataPrefs, { backFromCurrent() }, { reload() }, colorMap, slots, onSwapSlots = { name, from, delta ->
            // 扁平列表里该课程第 from 行与第 from+delta 行互换（行序=slots 序），同步写回
            val u = courses.toMutableList()
            val idxs = u.mapIndexed { i, c -> if (c.name == name) i else -1 }.filter { it >= 0 }
            val to = from + delta
            if (from in idxs.indices && to in idxs.indices) {
                val a = idxs[from]; val b = idxs[to]
                val tmp = u[a]; u[a] = u[b]; u[b] = tmp
                courses = u
                saveCoursesToPrefs(dataPrefs, u)
            }
        })
        "generalInfo" -> GeneralInfoPage(config, dataPrefs, { backFromCurrent(refresh = true) }, onExport, onImport, slots)
        "settings" -> SettingsPage({ backFromCurrent() }, onExport, onImport, prefs, dataPrefs, { navigateTo("about") }, {
            courses = emptyList()
            config = ScheduleConfig()
            refreshKey++
        }, onDeveloper = { navigateTo("developer") })
        "about" -> AboutPage({ backFromCurrent() })
        "timeTable" -> TimeTableSettingsPage(dataPrefs, { backFromCurrent(refresh = true) }, { refreshKey++ }, slots)
        "reminder" -> ReminderPage(prefs, dataPrefs, courses, config, slots, { backFromCurrent(); rearm() }, rearm, reminderVersion)
        "holiday" -> HolidayPage(dataPrefs, { backFromCurrent(refresh = true) })
        "manual" -> ManualPage({ backFromCurrent() })
        "appearance" -> AppearancePage(prefs, { backFromCurrent() }, onEditBgPosition = { navigateTo("bgFit") })
        "bgFit" -> BgImagePositionPage({ backFromCurrent() })
        "developer" -> DeveloperPage({ backFromCurrent() }, onDataReload = reloadData)
        }
    }

    // 课程详情弹窗（在 ScheduleApp 级别，覆盖所有页面）
    showDetailCourses?.let { list ->
        val isConflict = list.size > 1
        AlertDialog(
            onDismissRequest = { showDetailCourses = null },
            containerColor = AppC.card,
            shape = RoundedCornerShape(16.dp),
            title = {
                Column {
                    Text("课程详情", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(if (isConflict) "有${list.size}节课程冲突" else "", fontSize = 12.sp, color = AppC.textMuted)
                }
            },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    list.forEachIndexed { idx, c ->
                        if (idx > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(c.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${c.type} · ${c.credit}学分 · ${c.exam}", fontSize = 12.sp, color = AppC.textMuted)
                        Text("教师: ${c.teacher}", fontSize = 13.sp)
                        Text("教室: ${c.room}", fontSize = 13.sp)
                        Text("时间: ${dayNames[c.day]} ${c.slot}", fontSize = 13.sp)
                        Text("周次: 第${c.weekStart}~${c.weekEnd}周", fontSize = 13.sp)
                        Text("单位: ${c.unit}", fontSize = 12.sp, color = AppC.textMuted)
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
             onCourseClick: (List<Course>) -> Unit, drawerState: EdgeDrawerState,
             onReminder: () -> Unit,
             onHoliday: () -> Unit = {}, holidays: List<HolidayItem> = emptyList(),
             onManual: () -> Unit = {}, onAppearance: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    // 这里只读提醒配置（属于"设置类"），所以固定用真实文件，不随测试课表切换
    val prefs = LocalContext.current.getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)
    val reminderConfig = loadReminderConfig(prefs)
    // 抽屉只属于主页面：它是"主页"整体的一部分，进出子页时抽屉跟着主页一起滑走/滑回，
    // 开合状态不变——所以返回主页面"一打开就带着抽屉"，不用先关再补一个打开动画。
    // 子页面的组件树里没有抽屉，右滑唤不出来。
    //
    // 用自研的 EdgeDrawer 而不是 ModalNavigationDrawer：后者没有"连续设置偏移"的公开接口
    // （只有只读的 offset 和整档的 open/close），边缘手势只能喊一声 open()，
    // 手指划多快都不作数 —— 三大金刚键下一滑就整块弹出来，很不跟手。
    // 现在 progress（0~1）由手势逐帧写，见下面手势裁决里的 mode == 2。
    EdgeDrawer(
        state = drawerState,
        drawer = {
            Row(Modifier.fillMaxWidth().padding(20.dp, 24.dp, 20.dp, 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painter = painterResource(id = R.drawable.ic_about), contentDescription = "Logo",
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("大学课程表", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppC.textPrimary)
                    Text("v${appVersionName(LocalContext.current)}", fontSize = 11.sp, color = AppC.textMuted)
                }
            }
            Spacer(Modifier.height(4.dp))
            // 菜单列表单独滚动：分屏上下被压得很矮时，八项菜单一屏放不下，
            // 以前整列排布、超出的部分既看不见也滚不动（用户直接够不到下面的项）。
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // 一律只留标题：解释性文字和数量统计都收进「使用手册」/各自页面里看
            DrawerMenuItem(icon = Icons.Default.Info, label = "学期设置", onClick = { onGeneralInfo() })
            DrawerMenuItem(icon = Icons.Default.AccessTime, label = "时间表设置", onClick = { onTimeTable() })
            DrawerMenuItem(icon = Icons.Default.Edit, label = "课程管理", onClick = { onManage() })
            DrawerMenuItem(icon = Icons.Default.EventAvailable, label = "节假日设置", onClick = { onHoliday() })
            DrawerMenuItem(icon = Icons.Default.NotificationsActive, label = "上课提醒", onClick = { onReminder() })
            // 侧边菜单里叫「外观」（页面标题仍是「外观设置」）：菜单项都取短词，与「设置」并列更整齐
            DrawerMenuItem(icon = Icons.Default.Palette, label = "外观", onClick = { onAppearance() })
            DrawerMenuItem(icon = Icons.Default.Settings, label = "设置", onClick = { onSettings() })
            DrawerMenuItem(icon = Icons.AutoMirrored.Filled.MenuBook, label = "使用手册", onClick = { onManual() })
            }
            // 左下角快捷切换深色模式：点一下按 跟随系统 → 深色 → 浅色 循环。
            // 图标即当前状态：手机=跟随系统、月亮=深色、太阳=浅色（不必进设置页就能确认/切换）
            // 这一行**常驻左下角**：不跟着菜单列表滚，分屏只剩一条缝时也在。
            HorizontalDivider(color = AppC.divider)
            Row(
                Modifier.fillMaxWidth().padding(8.dp, 8.dp, 16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val nextMode = when (AppC.mode) {
                    0 -> 2      // 跟随系统 → 深色
                    2 -> 1      // 深色 → 浅色
                    else -> 0   // 浅色 → 跟随系统
                }
                val (modeIcon, modeDesc) = when (AppC.mode) {
                    0 -> Icons.Default.PhoneAndroid to "深色模式：跟随系统（点按切换）"
                    2 -> Icons.Default.DarkMode to "深色模式：深色（点按切换）"
                    else -> Icons.Default.LightMode to "深色模式：浅色（点按切换）"
                }
                IconButton(onClick = {
                    AppC.mode = nextMode
                    prefs.edit().putInt("dark_mode", nextMode).apply()
                }) {
                    Icon(modeIcon, modeDesc, tint = AppC.textMuted, modifier = Modifier.size(22.dp))
                }
            }
        },
    ) {
        // 分屏/小窗时，系统在窗口顶部中央画一个"⋯"拖动把手，会压住居中的标题。
        // 小窗状态由 MainActivity 统一检测并经 LocalSmallWindow 下发，这里只消费。
        val inMultiWindow = LocalSmallWindow.current
        // ---- 手势：左边缘右滑开抽屉 / 其余区域左右滑切周次 ----
        // 关闭态下抽屉自带手势是关掉的（见上面 gesturesEnabled），右滑由这里裁决，
        // 否则"随便在哪右滑都把菜单拉出来"、而且和切周打架。
        //
        // 之前用的是 detectHorizontalDragGestures：它要等 ViewConfiguration 的判向阈值
        // 出来才回调，而课表里的竖向列表优先级更高，斜着划经常被整段判给竖向 →
        // 表现就是"偶尔触发不了、偏一点就和上下滑打架"。
        // 现在改成自己用 awaitEachGesture 裁决：按下后第一个有意义的移动立刻按主轴定性
        // （横向分量占优就算切周），定性后把事件 consume 掉不让列表抢，竖向则整段交还。
        // 横向位移全程实时喂给 dragX，画面跟着手指走（见 WeekSlide）。
        val latestWeek = rememberUpdatedState(currentWeek)
        // 上限跟随「本学期周数」设置，但最多到 TOTAL_WEEKS_MAX（52 周＝一年）：
        // 原来这里写死 coerceAtMost(20)，学期设成 22 周时第 21、22 周在周次条和
        // 翻页里都到不了（1.0 遗留 bug）；现在填多少显示多少，填超了按 52 封顶。
        val maxShownWeek = config.totalWeeks.coerceIn(1, TOTAL_WEEKS_MAX)
        val edgePx = with(LocalDensity.current) { 32.dp.toPx() }
        // 抽屉宽度（px）：边缘手势的位移按它换算成 0~1 进度，所以必须与 EdgeDrawer 里的宽度一致
        val drawerW = with(LocalDensity.current) { DRAWER_WIDTH_DP.dp.toPx() }
        // 抽屉是否"关着"。注意这是个**函数不是 val**：手势裁决发生在拖拽期间，必须每次现读，
        // 缓存成 val 就会拿着组合期的旧值去判断（抽屉已经开了还当成关着 → 又去拉开一遍）。
        fun drawerIdle() = drawerState.progress <= 0.001f && !drawerState.isOpen
        // 一屏的翻页距离 & 触发阈值（原来写死 80dp，小屏偏严大屏偏松，改成按屏宽比例）
        var pageW by remember { mutableFloatStateOf(0f) }        // 内容宽度，实测
        val switchPx = if (pageW > 0f) pageW * 0.22f else with(LocalDensity.current) { 80.dp.toPx() }
        // 手势分区用：整页 Column 与"课程显示区域"在窗口里的 Y。左右滑切周只认从课程区起手的手势，
        // 日期行及以上（标题栏/下一节课条/周次胶囊行）都不参与 —— 胶囊行得留着自己左右滚。
        var columnTopY by remember { mutableFloatStateOf(0f) }
        var gridTopY by remember { mutableFloatStateOf(0f) }
        // dragOffset = 唯一的横向偏移状态（px，负=向左），WeekPager 读它渲染。
        // 为什么不用 Animatable 直接当状态源：awaitEachGesture 的 lambda 是「受限挂起作用域」，
        // 里面只能调 PointerInputScope 自己的成员，dragX.snapTo() 会编译不过；所以拖拽期间
        // 每帧只写普通快照状态 dragOffset，dragX 专职做松手后的补间驱动器，逐帧回写 dragOffset。
        val dragX = remember { Animatable(0f) }
        // 横向偏移用「显式的 FloatState 对象」往下传，绝不展开成 Float 局部变量。
        // 展开成 Float 就意味着组合期必须读它 → 每帧重组整个 MainPage：课表里是
        // 几百个 Box/Text（每个格子还要重建 cellMap 数组），手势层每帧写一次偏移
        // 就要把它们全部重组一遍 —— 这正是"响应及时但卡卡的、不够丝滑"的根因。
        // 现在只有 WeekLayer 的 graphicsLayer 更新 lambda 读它（延后到绘制期），
        // 拖拽/滑行全程只改渲染结点的 translationX/alpha：不重组、不重新布局。
        val dragState = remember { mutableFloatStateOf(0f) }
        // 手势层与补间协程都是"非组合代码"，用这两个小函数读写，免得满屏 floatValue
        fun dragNow() = dragState.floatValue
        fun setDragNow(v: Float) { dragState.floatValue = v }
        var dragActive by remember { mutableStateOf(false) }      // 拖拽中 → 补间不许抢写偏移
        var settleJob by remember { mutableStateOf<Job?>(null) }  // 松手后的落定补间，重新按下/再次切周要取消
        // 跨周过场用的两个值：只在 WeekPager 外层 graphicsLayer 的 lambda 里读（延后读），
        // 逐帧变化只改渲染结点的 translationX/alpha，不会重组课表。
        val jumpTx = remember { Animatable(0f) }
        val jumpAlpha = remember { Animatable(1f) }
        var jumpJob by remember { mutableStateOf<Job?>(null) }
        // 周次胶囊"点选跳周"时的选中态交叉淡变（0=旧周实心，1=新周实心）。
        // 以前这套状态住在 WeekChipBar 里、靠 LaunchedEffect(currentWeek) 启动 —— 那个 effect
        // 总要等下一帧才跑，于是换周那一帧会先用旧进度画一次：目标胶囊先亮满一帧、下一帧又
        // 被拉回 0 重新淡入（或者干脆看起来是硬切），就是用户说的"胶囊闪"。
        // 现在由 goToWeek 在换周前后显式驱动，一步都不落帧。
        // 注意只把 Animatable 对象传进胶囊栏（值在它内部读）：逐帧变化只重组胶囊栏，
        // 不会把挂着三层课表的 MainPage 拖进来重组。
        val chipTap = remember { Animatable(1f) }
        var chipTapFrom by remember { mutableIntStateOf(currentWeek) }
        // ---- 周次胶囊 ----
        // 整条胶囊栏（含选中态的交叉淡变）已抽成独立的 WeekChipBar。
        // 原因：拖拽时它每帧都要按当前偏移重算浓度 → 这一处注定要逐帧重组，
        // 那就把它关进自己的组合作用域，别让 MainPage 跟着每帧重组
        // （MainPage 里挂着课表，一重组就是几百个结点 + 重建 cellMap）。
        fun weekAt(delta: Int) = (latestWeek.value + delta).coerceIn(1, maxShownWeek)
        // 把 dragOffset 从 from 补间回 0（唯一的写入口，避免多处各写一套）。
        // 必须先于 goToWeek 声明：Kotlin 局部函数不能前向引用。
        fun settle(from: Float, durMs: Int, easing: Easing) {
            settleJob?.cancel()
            settleJob = scope.launch {
                setDragNow(from)
                dragX.snapTo(from)
                // 尾随 lambda 是 Animatable 的 receiver 块，里面读 value 拿当前帧
                dragX.animateTo(0f, tween(durMs, easing = easing)) {
                    if (!dragActive) setDragNow(value)
                }
                if (!dragActive) setDragNow(0f)
            }
        }
        // 正式切到目标周。
        // 关键：先提交周次、再把"残余偏移"补间回 0，而不是先补间到位再提交。
        // 旧写法（补间→提交→归零）在快速甩动时会跨出坏帧：那一帧 week 已经换、
        // dragOffset 还是 ±一屏，两层同时被推出屏幕 → 中间露出纯背景（用户截图的断层）；
        // 更糟的是补间协程里带着 onWeekChange，手指第二次按下时它会迟到提交，
        // 把手指底下的周次偷偷换掉 → 表现就是"滑不动、周次不跟"。
        // 换算关系：提交前邻居层画在 d0 ± pageW，提交后它变成当前层，偏移正好是
        // d0 + step*pageW，所以画面在提交那一帧完全连续，之后只需滑行到 0。
        fun goToWeek(to: Int) {
            val from = latestWeek.value
            settleJob?.cancel()
            if (to == from) { settle(dragNow(), SNAP_BACK_MS, SnapBackEasing); return }
            val step = to - from
            if (kotlin.math.abs(step) != 1 || pageW <= 0f) {
                // 跨周（点胶囊跳很远）：走"滑出去 → 换周 → 从另一侧滑回来"的过场。
                // 直接落位会在换周那一帧露出空白页，看着就是硬切一下。
                jumpJob?.cancel()
                setDragNow(0f)
                val dir = if (step > 0) 1 else -1
                // 胶囊淡变的基准先摆到"出发那一周"（起点值 0 由作业在换周前才写，
                // 此前 chipTapFrom 仍等于当前周，所以画面不变）
                chipTapFrom = from
                jumpJob = scope.launch {
                    // 交变起点先归零：此时还没换周（chipTapFrom == currentWeek），画面不变，
                    // 目的是让换周后那一帧就是"旧周实心、新周空"的干净起点。
                    chipTap.snapTo(0f)
                    // 退出：朝行进方向滑出 + 淡出（淡到几乎看不见，换周那一帧正好藏在里面）
                    kotlinx.coroutines.coroutineScope {
                        launch { jumpTx.animateTo(-dir * pageW * JUMP_SLIDE, tween(JUMP_EXIT_MS, easing = FlipEasing)) }
                        launch { jumpAlpha.animateTo(JUMP_ALPHA_MIN, tween(JUMP_EXIT_MS, easing = FlipEasing)) }
                    }
                    onWeekChange(to)
                    // 换周后把胶囊淡过去（相邻周那条路由 settle 的残余偏移逐帧驱动，同款观感）
                    chipTap.animateTo(1f, tween(CHIP_FADE_MS, easing = FlipEasing))
                    chipTapFrom = to
                    // 进场：换到另一侧，再滑回来 + 淡入
                    jumpTx.snapTo(dir * pageW * JUMP_SLIDE)
                    kotlinx.coroutines.coroutineScope {
                        launch { jumpTx.animateTo(0f, tween(JUMP_ENTER_MS, easing = FlipEasing)) }
                        launch { jumpAlpha.animateTo(1f, tween(JUMP_ENTER_MS, easing = FlipEasing)) }
                    }
                }
                return
            }
            // ±1 翻页：滑行期间的淡变由 dragState 逐帧公式接管（提交前后公式连续），
            // 这里只把 chipTapFrom 落到新周 —— 落定后偏移归 0，公式会切回"chipTapFrom 分支"，
            // 归好位就直接亮新周，不会再经过一次淡变（也就不会有第二段闪）。
            val start = (dragNow() + step * pageW).coerceIn(-pageW, pageW)
            onWeekChange(to)
            chipTapFrom = to
            // 滑行时长按剩余距离给：甩得越远剩得越多、稍慢一点走完；但给个下限 280ms，
            // 否则"刚过阈值就松手/长距离拖到边"会被压成一瞬间，ease-in-out 的首尾慢段全被吃掉、
            // 曲线就白改了 —— 这一版用户要的正是能看得出来的"首尾慢、中间快"。
            val frac = kotlin.math.abs(start) / pageW
            settle(start, (FLIP_MS * frac).toInt().coerceIn(280, FLIP_MS), FlipEasing)
        }
        Column(
            Modifier.fillMaxSize().background(AppC.bg)
                .onSizeChanged { if (it.width > 0) pageW = it.width.toFloat() }
                .onGloballyPositioned { columnTopY = it.localToRoot(Offset.Zero).y }
                .pointerInput(maxShownWeek, switchPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // 手指重新按下 → 从当前位置继续拖（连滑），并锁住补间不让它抢写
                        dragActive = true
                        settleJob?.cancel()      // 落定补间到此为止，否则它会迟到地把偏移写回来
                        // 跨周过场也一并收掉：它会把整块课表滑出去，手指一按下就该恢复成正常显示
                        // （手势层是"受限挂起作用域"不能直接调 snapTo，扔回普通 scope 里做）
                        jumpJob?.cancel()
                        scope.launch {
                            jumpTx.snapTo(0f)
                            jumpAlpha.snapTo(1f)
                        }
                        var accX = dragNow()
                        val baseX = dragNow()   // 本次手势的起点偏移（accX 会被逐帧累加覆盖）
                        var accY = 0f
                        var mode = 0            // 0=未判定 1=切周 2=左缘开抽屉 -1=交还竖向
                        val slop = viewConfiguration.touchSlop
                        val startX = down.position.x
                        // 只有从"课程显示区域"起手的横滑才用来切周；日期行及以上（标题栏、下一节课条、
                        // 周次胶囊行）一律不参与 —— 胶囊行得留着自己左右滚动。
                        val inGrid = down.position.y >= gridTopY - columnTopY
                        var lastT = down.uptimeMillis
                        var velocity = 0f       // px/s，正=向右
                        while (true) {
                            // Initial：先于课表里的竖向列表拿到事件，所以斜着划也能第一时间定性成横向
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) {
                                // 抬手。极快的甩动（一帧内划出大半屏）可能一个 move 都没送到，
                                // mode 还停在 0 → 以前直接 break，子组件就把这一下当点击，
                                // 表现是"滑快了切不动、反而弹出课程详情"。
                                // 这里用「按下点 → 抬手点」的总位移补判一次。
                                if (mode == 0) {
                                    val tdx = ch.position.x - startX
                                    val tdy = kotlin.math.abs(ch.position.y - down.position.y)
                                    if (kotlin.math.abs(tdx) > slop && kotlin.math.abs(tdx) > tdy * 1.15f) {
                                        // 一帧 move 都没送到（超快甩动）。分区判定同样适用：
                                        // 左缘向右 → 抽屉；课程区 → 切周；其它区域 → 不掺和。
                                        mode = when {
                                            drawerIdle() && startX <= edgePx && tdx > 0f -> 2
                                            drawerIdle() && inGrid -> 1
                                            else -> -1
                                        }
                                        // mode 2：一帧 move 都没送到也照样跟手 —— 直接把总位移换算成进度，
                                        // 松手后的补间统一由循环外那段处理（和 mode 1 共用同一处收尾）。
                                        if (mode == 2) {
                                            drawerState.scrimVisible = true
                                            drawerState.progress = (tdx / drawerW).coerceIn(0f, 1f)
                                        }
                                        if (mode == 1) {
                                            velocity = tdx / (ch.uptimeMillis - down.uptimeMillis).coerceAtLeast(1L) * 1000f
                                            val lim = if (pageW > 0f) pageW else Float.MAX_VALUE
                                            setDragNow((baseX + tdx).coerceIn(-lim, lim))
                                        }
                                        if (mode > 0) ch.consume()
                                    }
                                } else if (mode > 0) {
                                    // 只有已经判成横向手势时才吃掉 up，否则留给子组件识别点击
                                    ch.consume()
                                }
                                break
                            }
                            val dt = (ch.uptimeMillis - lastT).coerceAtLeast(1L).toFloat()
                            lastT = ch.uptimeMillis
                            val dx = ch.position.x - ch.previousPosition.x
                            accX += dx
                            accY += kotlin.math.abs(ch.position.y - ch.previousPosition.y)
                            // 速度只采信 ≥8ms 的帧并做平滑。快速划动时偶尔会挤进一两个
                            // 间隔极小的帧（20px / 1ms = 20000px/s），旧写法直接覆盖会让
                            // "划出去又拖回来"被误判成猛甩 → 周次乱跳、不跟手。
                            if (dt >= 8f) {
                                val inst = dx / dt * 1000f
                                velocity = if (velocity == 0f) inst else velocity * 0.5f + inst * 0.5f
                            }
                            if (mode == 0) {
                                if (kotlin.math.abs(accX) > slop || accY > slop) {
                                    if (kotlin.math.abs(accX) > accY * 1.15f) {
                                        // 横向占优 → 左缘右滑开抽屉（整页有效，系统级习惯）；
                                        // 其余只有落在课程显示区域里的才归切周，之外的一律不掺和：
                                        // mode=-1 不 consume，事件原样留给子控件 —— 周次胶囊就是靠这个
                                        // 恢复"自己能左右滚动"的（以前外层在 Initial 阶段就吃掉了，划不动）。
                                        mode = when {
                                            drawerIdle() && startX <= edgePx && accX > 0f -> 2
                                            drawerIdle() && inGrid -> 1
                                            else -> -1
                                        }
                                        // 注意这里**不**再调 open()：抽屉的进度由下面的逐帧分支写，
                                        // open() 那种"喊一声就自己滑完"的做法正是"不跟手"的来源。
                                        if (mode == 2) drawerState.scrimVisible = true
                                    } else {
                                        mode = -1        // 竖向 → 完全不掺和，交还给列表
                                        break
                                    }
                                }
                            }
                            if (mode > 0) {
                                ch.consume()            // 吃掉后竖向列表会自动放弃这次拖拽
                                if (mode == 1) {
                                    // 位移硬限幅在一屏之内。WeekPager 只画「当前周 + 相邻周」两层
                                    // （位置 d 与 d±pageW），|d| 一旦超过一屏，两层会同时被推出
                                    // 屏幕，中间露出纯背景 —— 就是快速甩动时那道"断层"。
                                    // 夹的是累加值而不是只显示值：反向拖时立刻跟手，不会粘在边界。
                                    val lim = if (pageW > 0f) pageW else Float.MAX_VALUE
                                    accX = accX.coerceIn(-lim, lim)
                                    // 到头了就给个阻尼，让"下一周没有了"有手感反馈
                                    val atEdge = (accX < 0f && weekAt(1) == latestWeek.value) ||
                                        (accX > 0f && weekAt(-1) == latestWeek.value)
                                    setDragNow(if (atEdge) (accX * 0.25f).coerceIn(-lim, lim) else accX)
                                } else if (mode == 2) {
                                    // 抽屉跟手：手指移到哪，抽屉就开到哪（左边缘起手，位移即进度）。
                                    // 只写这一颗状态，EdgeDrawer 用 graphicsLayer 平移渲染
                                    // —— 不重组、不重新布局，和切周同一套"零重组"打法。
                                    drawerState.progress = (accX / drawerW).coerceIn(0f, 1f)
                                }
                            }
                        }
                        dragActive = false
                        if (mode == 2) {
                            // 松手收尾：按"当前进度 + 手上残余速度"决定开还是关，再补间过去。
                            // 和切周同一套判法（先看位移过半、再看速度方向），所以甩一下能顺势打开，
                            // 拖到一半松手会退回原位。
                            scope.launch { drawerState.animateTo(drawerState.targetFor(velocity)) }
                        } else if (mode == 1) {
                            // 松手判定用的是「本次手势的行程」moveX，不是绝对偏移 dragOffset。
                            // 因为上一次翻页可能还带着残余偏移在回收（例如刚提交完还剩 +700），
                            // 这一次手指实实在在划了 800px，绝对偏移却只从 +700 走到 -100，
                            // 按绝对值判就够不到阈值 → 表现是"连滑时滑不动、周次不更新"。
                            val moveX = (dragNow() - baseX).coerceIn(-pageW, pageW)
                            // 甩动只在「方向和拖拽一致」时加成。手指划出去又拖回来时
                            // moveX≈0 但残留速度可能很大，否则会凭空翻一页。
                            val fling = if (moveX == 0f || velocity * moveX > 0f) velocity * 0.08f else 0f
                            val projected = (moveX + fling).coerceIn(-pageW, pageW)
                            // 只用「位移 + 甩动预测」一条通道判定。
                            // 之前另加了一条"拖开一点 + 速度够猛就翻页"的纯速度通道，
                            // 结果课表上斜着快划、或轻轻一划都会莫名翻页 —— 太容易误触。
                            val to = when {
                                projected <= -switchPx -> weekAt(1)
                                projected >= switchPx -> weekAt(-1)
                                else -> latestWeek.value
                            }
                            if (to == latestWeek.value) {
                                // 没翻页 → 连本次行程加上之前残留的偏移一起回弹到 0
                                settle(dragNow(), SNAP_BACK_MS, SnapBackEasing)
                            } else {
                                goToWeek(to)
                            }
                        } else if (dragNow() != 0f) {
                            // 这一下最终没当成切周（判成竖向 / 在课程区外起手 / 走了开抽屉），
                            // 但按下时可能带着上一次没落完的偏移。不管它就会被永久卡在半偏移 ——
                            // 页面看着像"卡住滑不动"、两个周次胶囊同时半亮。补一次回弹收尾。
                            settle(dragNow(), SNAP_BACK_MS, SnapBackEasing)
                        }
                    }
                }
        ) {
            // 标题栏（带汉堡按钮）
            Surface(color = AppC.headerBlue, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(4.dp, if (inMultiWindow) 28.dp else 6.dp, 16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            // 汉堡键走补间打开（手势那条路子才需要跟手）
                            scope.launch { drawerState.animateTo(1f) }
                        }) { Icon(Icons.Default.Menu, "菜单", tint = AppC.headerText, modifier = Modifier.size(24.dp)) }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 引导里可以不填学校/专业，留空时标题栏退回应用名
                            // 文字色必须跟着主题色走：亮黄顶栏上白字几乎看不见（以前写死白）
                            Text(config.school.ifBlank { "大学课程表" }, color = AppC.headerText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            if (config.major.isNotBlank()) {
                                Text(config.major, color = AppC.headerText.copy(alpha = 0.8f), fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(48.dp))
                    }
                }
            }
            // 下一节课提示条：放在周次栏上方
            NextClassBar(courses, config, slots, reminderConfig, holidays, onReminder)
            // 周次胶囊栏：抽成独立组合作用域 —— 拖拽时要逐帧按偏移算浓度，
            // 这份"逐帧重组"只发生在它自己身上，不会把挂着课表的 MainPage 拖下水。
            WeekChipBar(
                currentWeek = currentWeek,
                maxShownWeek = maxShownWeek,
                dragState = dragState,
                pageW = pageW,
                chipTap = chipTap,
                chipTapFrom = chipTapFrom,
                onPick = { goToWeek(it) },
            )
            // 表头 + 课表作为整体跟手翻页：上周/本周/下周三层常驻组合（key 保住内容，
            // 切周时是"移动"而不是重建），横向偏移只在 graphicsLayer 的更新 lambda 里读
            // dragState（延后到绘制期）→ 拖拽和滑行全程只动渲染结点，零重组、零重新布局。
            // 表头 / 课表这两个内容 lambda 必须固定住实例：
            // 翻页的那一帧 MainPage 会因为 currentWeek 变化而重组，普通 lambda 每次都生成新实例
            // → WeekLayer 的参数变了 → 三张课表全部重组，正好卡在落定动画起步那一帧。
            // 这里连"捕获项"都不直接进 lambda，而是各套一层 rememberUpdatedState：
            // lambda 实例用无 key 的 remember 永久固定（不依赖编译器的 lambda 记忆化），
            // 数据变化时靠 lambda 体内的状态读取把内容刷新 —— 各层的**内容**只跟自己的周次有关，
            // 切周那一帧本来就该一动不动。
            val coursesNow = rememberUpdatedState(courses)
            val configNow = rememberUpdatedState(config)
            val slotsNow = rememberUpdatedState(slots)
            val colorMapNow = rememberUpdatedState(colorMap)
            val holidaysNow = rememberUpdatedState(holidays)
            val clickNow = rememberUpdatedState(onCourseClick)
            val headerContent: @Composable (Int) -> Unit = remember {
                { w -> WeekHeaderView(configNow.value, holidaysNow.value, w) }
            }
            val tableContent: @Composable (Int) -> Unit = remember {
                { w ->
                    Box(
                        Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            // 课程显示区域的顶边（表头日期行以下）→ 切周手势的分界线
                            .onGloballyPositioned { gridTopY = it.localToRoot(Offset.Zero).y }
                    ) {
                        CourseTable(coursesNow.value, w, colorMapNow.value, slotsNow.value, onCourseClick = clickNow.value, columnSourceDates = computeColumnSourceDates(getWeekDates(configNow.value, w), holidaysNow.value), config = configNow.value)
                    }
                }
            }
            // 课表背景图：整个课表区（表头 + 格子）的最底下一层静态图层。
            // 不跟着翻页动 —— 前景的格子/表头是半透明的，翻周次时像卡片从图上滑过，
            // 图本身只绘制一次，不参与逐帧合成。
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ScheduleBackground()
                WeekPager(
                    week = currentWeek, maxWeek = maxShownWeek, dragState = dragState, pageW = pageW,
                    // 跨周过场：整块（表头日期行 + 课表）一起滑出/滑回并淡入淡出。
                    // 这两个值只在 graphicsLayer 的 lambda 里读 → 过场期间零重组。
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        translationX = jumpTx.value
                        alpha = jumpAlpha.value
                    },
                    header = headerContent,
                    table = tableContent,
                )
            }
        }
    }
}

// ---- 切周松手后的补间参数 ----
// 曲线统一用对称的 ease-in-out（≈ cubic-bezier(0.42, 0, 0.58, 1)）：起步柔和加速、
// 中段最快、落位前平滑减速，也就是"首尾慢、中间快"。中点瞬时速度约 1.72× 平均速度，
// 中段的"快"看得出来，两端又没有硬拐点 —— 比"蹭地冲出去再急刹"的 emphasized
// decelerate 优雅得多。全程单调不过冲：一旦过冲会瞬间露出另一侧的邻居周，反而像 bug。
// 时长比之前的"急冲"版本稍长（460ms）：曲线两端本来就慢，太短会让首尾的慢段被压没。
const val FLIP_MS = 460
// 回弹是"手指没翻页"的纠错，保持稍快（320ms），曲线和翻页同一条保持观感统一。
const val SNAP_BACK_MS = 320
// 周次胶囊选中态的淡入淡出时长。翻页落定（或点击）的瞬间 currentWeek 才变，
// 这个补间正好接在那一刻：旧胶囊从实心蓝淡回描边、新胶囊从描边淡成实心蓝。
const val CHIP_FADE_MS = 300
// 点胶囊"跨好几周"时的过场：跨度大于一周没法像 ±1 那样滑一格就到位。
// 原来直接落位，会露出"换周那一帧整页课表是空的"（三个槽位还指着旧周、三层全在屏幕外），
// 表现就是"啪"地一下换个周，很突兀。
// 现在整块课表先朝行进方向滑出去并淡出，换完周再从另一侧滑回来淡入：
// 换周那一帧几乎全透明，空白帧看不见；而且只重组一次，不像"逐周翻过去"那样
// 要连做十几次课表组合（跨度 16 周就要组合 16 张，反而会卡）。
const val JUMP_EXIT_MS = 130
const val JUMP_ENTER_MS = 200
const val JUMP_ALPHA_MIN = 0.1f   // 淡出到此为止（不归零，避免中间出现"空屏停顿"）
const val JUMP_SLIDE = 0.5f       // 滑出距离 = 屏宽 × 这个比例
val FlipEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
val SnapBackEasing = FlipEasing

/**
 * 切周容器：上周 / 本周 / 下周三层常驻并排，横向偏移由 MainPage 的手势层实时写进 dragState。
 *
 * 为什么是这个结构（三条都是"丝滑"的必要条件）：
 *  1. 偏移只在 graphicsLayer 的更新 lambda 里读 —— 那是"延后读"，快照依赖落在绘制期，
 *     所以逐帧变化只让渲染结点重算 translationX/alpha，不重组、不重新布局。
 *     旧版把偏移当 Float 参数传进组合，手势层每帧写一次就要把整页课表（几百个结点，
 *     每帧还要重建 cellMap 数组）重组一遍 —— 那才是"响应及时但卡卡的"的真正原因。
 *  2. 三层用 key(周次) 包住：切周那一帧 Compose 是"移动"已有的那一层，
 *     而不是把新周次的课表从零组合一遍，落定动画期间不会再冒出一次全量组合。
 *  3. 邻居层常驻（超出部分被 clipToBounds 裁掉）：起手第一帧无需临时组合一整张课表，
 *     消除"手指刚动就顿一下"的起手掉帧。
 */
@Composable
private fun WeekPager(
    week: Int,
    maxWeek: Int,
    dragState: FloatState,
    pageW: Float,
    modifier: Modifier = Modifier,
    header: @Composable (Int) -> Unit,
    table: @Composable (Int) -> Unit,
) {
    // 当前周做成"延后读"：层的**内容**只跟自己的周次有关，层的**位置**才跟当前周有关。
    // 位置计算挪进 graphicsLayer 的 lambda 之后，翻页那一帧各层的参数都不会因为"位置"变化，
    // 只有真正换了周次的那一层需要重组。
    val weekState = rememberUpdatedState(week)
    // 三个固定"槽位"：下标永远 0/1/2（key 不变），槽里装的是"第几周"。
    // 每次翻页只把滑出窗口的那一个槽改写成新的邻居 → 那一帧只需要组合 1 张课表，
    // 另外两张参数一模一样，直接跳过。
    // （早先写 `key(周次)` 的做法行不通：循环里键集一变，Compose 并不会搬走旧组复用，
    //  而是把整排都重新执行一遍 —— 三张课表全量重组，正好砸在落定动画起步那一帧。）
    val slots = remember { mutableStateListOf((week - 1).coerceAtLeast(1), week, (week + 1).coerceAtMost(maxWeek)) }
    LaunchedEffect(week, maxWeek) {
        val lo = (week - 1).coerceAtLeast(1)
        val hi = (week + 1).coerceAtMost(maxWeek)
        for (n in lo..hi) {
            if (slots.contains(n)) continue
            // 优先改写窗口外的槽；首周/末周附近槽位会退化成重复值，那就改写重复的那个
            var idx = slots.indexOfFirst { it < lo || it > hi }
            if (idx < 0) idx = slots.indexOfFirst { v -> slots.count { it == v } > 1 }
            if (idx < 0) continue
            slots[idx] = n
        }
    }
    // 冷启动首帧只组合"当前周"那一层：每层都是一整张课表（几百个结点 + cellMap），
    // 三层一起组合等于第一帧多干两倍的活，直接体现在"启动要等两秒"上。
    // 邻居层等下一帧再补进来 —— 用户不可能在一帧内就开始滑动，而邻居层常驻的意义
    // 只是"起手第一帧不掉帧"，晚一帧完全不影响。
    var showNeighbors by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        showNeighbors = true
    }
    Box(modifier.clipToBounds()) {
        for (i in slots.indices) {
            if (!showNeighbors && slots[i] != week) continue
            // 同一个周次只画一层。首周/末周附近槽位会退化成重复值（第1周 → [1,1,2]），
            // 以前格子不透明，重复层被完全盖住只是浪费；开了背景图后格子是**全透明**的，
            // 两层各自挂着一份独立的 verticalScroll 状态 —— 上下滑动时只有一层跟着滚，
            // 另一层的时间列文字留在原地，透出来就是"重影"。
            // 切一次周就好了：那次 LaunchedEffect 会把重复槽位改写成真正的邻居。
            if (slots.take(i).contains(slots[i])) continue
            key(i) {
                WeekLayer(
                    dragState = dragState, pageW = pageW, weekState = weekState,
                    layerWeek = slots[i], header = header, table = table,
                )
            }
        }
    }
}

/** 一层 = 表头（自然高度）+ 课表（吃掉剩余高度），整体只做横向位移，邻居层顺带淡入。 */
@Composable
private fun WeekLayer(
    dragState: FloatState,
    pageW: Float,
    weekState: State<Int>,
    layerWeek: Int,   // 这一层画的是第几周（层内容固定，不随翻页变）
    header: @Composable (Int) -> Unit,
    table: @Composable (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().graphicsLayer {
            // 这里读的两个状态都是"延后读"：每帧变化只让本层的渲染结点重算
            // translationX/alpha，不会重组 WeekLayer，更不会重组里面的课表。
            val d = dragState.floatValue
            val delta = layerWeek - weekState.value
            translationX = d + delta * pageW
            val p = if (pageW > 0f) (kotlin.math.abs(d) / pageW).coerceIn(0f, 1f) else 0f
            alpha = if (delta == 0) 1f else 0.7f + 0.3f * p
        }
    ) {
        header(layerWeek)
        Box(Modifier.fillMaxWidth().weight(1f)) { table(layerWeek) }
    }
}

/**
 * 周次胶囊栏：静态展示 + 点击跳周，选中态用 0..1 浓度插值做交叉淡变。
 * 单独抽出来是因为它注定要"逐帧重组"（拖拽时浓度跟着手指走）：关在自己的组合作用域里，
 * 这份开销就不会波及 MainPage —— MainPage 底下挂着课表，一重组就是几百个结点。
 */
@Composable
internal fun WeekChipBar(
    currentWeek: Int,
    maxShownWeek: Int,
    dragState: FloatState,
    pageW: Float,
    chipTap: Animatable<Float, AnimationVector1D>,
    chipTapFrom: Int,
    onPick: (Int) -> Unit,
) {
    val barState = rememberScrollState()
    val density = LocalDensity.current
    val screenWidth = with(density) { LocalContext.current.resources.displayMetrics.widthPixels.toDp().roundToPx() }
    // 选中态的交叉淡变（chipTap / chipTapFrom）不在这里维护：它由 MainPage 的 goToWeek 在
    // 换周前后显式驱动。以前那个 LaunchedEffect(currentWeek) 会晚一帧才生效，正是"胶囊闪"的来源。
    // 跟着 currentWeek 重启：切周后被选中的胶囊要自动滚回视野中央，
    // 否则手指划几下就选到屏幕外看不见了。
    LaunchedEffect(currentWeek) {
        delay(100)
        val halfScreen = screenWidth / 2
        val target = with(density) { ((currentWeek - 1) * 78).dp.roundToPx() }
        barState.animateScrollTo((target - halfScreen + 40).coerceAtLeast(0))
    }
    Row(Modifier.background(AppC.card).fillMaxWidth().horizontalScroll(barState), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(6.dp))
        for (w in 1..maxShownWeek) {
            // 选中态用"浓度"插值而不是布尔切换 → 淡入淡出。
            // 每个胶囊单独成一个 composable：浓度只在拖拽时逐帧变，一帧最多只有
            // 「当前周 + 将要切到的那一周」两个胶囊的参数会变，其余 18 个直接跳过
            // （写在同一个 Row 里的话，逐帧重建的 onClick lambda 会让 20 个 Surface 全部重组）。
            WeekChip(w = w, fill = chipFill(w, dragState.floatValue, pageW, currentWeek, maxShownWeek, chipTapFrom, chipTap.value), onPick = onPick)
        }
        Spacer(Modifier.width(6.dp))
    }
}

/** 单个"第N周"胶囊，fill=选中浓度 0..1。 */
@Composable
private fun WeekChip(w: Int, fill: Float, onPick: (Int) -> Unit) {
    Surface(onClick = { onPick(w) }, shape = RoundedCornerShape(16.dp), color = lerp(AppC.card, AppC.accent, fill), border = BorderStroke(1.5.dp, AppC.accent)) {
        Text("第${w}周", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), color = lerp(AppC.accent, readableOn(AppC.accent), fill), fontSize = 13.sp)
    }
}

/**
 * 某个胶囊此刻的"选中浓度" 0..1（1=实心蓝，0=描边）。
 * 拖拽期间以 currentWeek 为锚点（不另记 dragBaseWeek：那个变量在「半途改方向」和
 * 「连滑」时会和实际渲染的页脱节，胶囊就不跟手了）。提交翻页前后这个公式是连续的
 * （提交前邻居在 p，提交后它变成当前周、残余偏移算出来仍是 1-p），所以不会跳。
 */
private fun chipFill(
    w: Int, d: Float, pageW: Float, currentWeek: Int, maxShownWeek: Int,
    chipTapFrom: Int, chipTapP: Float,
): Float {
    if (d != 0f && pageW > 0f) {
        val dir = if (d < 0f) 1 else -1
        val p = (kotlin.math.abs(d) / pageW).coerceIn(0f, 1f)
        val to = (currentWeek + dir).coerceIn(1, maxShownWeek)
        if (to == currentWeek) return if (w == currentWeek) 1f else 0f
        return when (w) {
            currentWeek -> 1f - p
            to -> p
            else -> 0f
        }
    }
    if (chipTapFrom != currentWeek) {
        // 点击跨周：旧选中淡出、新选中淡入（chipTap 由 goToWeek 驱动，前后都踩在正确帧上）
        return when (w) {
            chipTapFrom -> 1f - chipTapP
            currentWeek -> chipTapP
            else -> 0f
        }
    }
    return if (w == currentWeek) 1f else 0f
}

/** 表头里的一列：weekColor 非空时"周N + 日期"整体变色（补=橙、休=红）。 */
@Composable
private fun RowScope.WeekHeaderCell(
    name: String,
    date: String,
    weekColor: Color?,
    isToday: Boolean,
) {
    val bg = if (isToday) AppC.todayBg else Color.Transparent
    Column(modifier = Modifier.weight(1f)
        .padding(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 4.dp)
        .background(bg, RoundedCornerShape(6.dp)),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = weekColor ?: AppC.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp)
        Text(date, color = weekColor ?: AppC.textMuted, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = if (weekColor != null) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
internal fun WeekHeaderView(config: ScheduleConfig, holidays: List<HolidayItem>, week: Int) {
    val wdates = getWeekDates(config, week)
    val wToday = LocalDate.now()
    val wTodayDoW = if (!wToday.isBefore(wdates[0]) && !wToday.isAfter(wdates[6])) wToday.dayOfWeek.value else -1
    Row(Modifier.background(AppC.tableHeader).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(50.dp).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {}
        for (i in 0..6) {
            val offH = holidayOn(holidays, wdates[i])
            val mk = makeupOn(holidays, wdates[i])
            // 补课日整列橙色、休息日整列红色（"周N"和日期一起变色），
            // 用颜色而不是多一行角标来标调休，表头高度因此回到紧凑版。补优先于休。
            val cellColor = when {
                mk != null -> AppC.orange
                offH != null -> AppC.danger
                else -> null
            }
            WeekHeaderCell(
                name = dayNames[i + 1],
                date = wdates[i].format(DateTimeFormatter.ofPattern("MM/dd")),
                weekColor = cellColor,
                isToday = (i + 1) == wTodayDoW,
            )
        }
    }
}

/**
 * 切周动画已由 WeekPager（跟手双层翻页）取代；这里不再需要 AnimatedContent 版本。
 */

// ====== 下一节课提示条 ======
@Composable
fun NextClassBar(courses: List<Course>, config: ScheduleConfig, slots: List<SlotItem>, reminder: ReminderConfig, holidays: List<HolidayItem> = emptyList(), onClick: () -> Unit) {
    if (!reminder.enabled) return
    // 提示条按"上课时间"取下一节（不受提前量影响），保证快上课时仍能看到
    val next = remember(courses, config, slots, reminder, holidays) {
        val now = java.time.LocalDateTime.now()
        upcomingClassEvents(courses, config, slots, reminder, now, holidays = holidays).firstOrNull { !it.startDateTime.isBefore(now) }
    } ?: return
    // 圆角卡片，四边间隙视觉一致（12dp 太宽，收到 8dp）。
    // 可点击 Surface 会被 M3 撑到 48dp 最小触控高度，卡片背景只有 40dp，上下各内缩 4dp，
    // 所以纵向 padding 4dp 实际呈现 8dp 缝；横向不会被撑（已是 fillMaxWidth），
    // 直接给 8dp 才能和上下对齐。
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = AppC.card,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NotificationsActive, null, tint = AppC.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("${next.dayLabel()} ${next.startText()}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppC.accent)
            Spacer(Modifier.width(8.dp))
            Text("《${next.course.name}》", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = AppC.textPrimary, modifier = Modifier.weight(1f, fill = false))
            if (next.course.room.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(next.course.room, fontSize = 11.sp, color = AppC.textMuted)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (reminder.mode == MODE_FOCUS) "焦点倒计时" else advanceSummary(reminder.advances), fontSize = 10.sp, color = AppC.textFaint2)
        }
    }
}

@Composable
fun DrawerMenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, subtitle: String = "", onClick: () -> Unit) {
    // subtitle 只给"当前状态"用（几节课、哪种通知方式…）；纯解释性文字一律不写在这里，
    // 统一收进「使用手册」，否则列表被副文本撑得很啰嗦。
    Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = AppC.textSecondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium); if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 11.sp, color = AppC.textMuted) }
        }
    }
}

// ====== 课程表 ======
@Composable
fun CourseTable(courses: List<Course>, currentWeek: Int, colorMap: MutableMap<String, Pair<Color, Color>>, slots: MutableList<SlotItem>, onCourseClick: (List<Course>) -> Unit, columnSourceDates: List<LocalDate?> = emptyList(), config: ScheduleConfig = ScheduleConfig()) {
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

    // 每列"实际取哪一天的课"：null=放假清空；某日期=显示该日期的课（补课日跨周也按源日期的周次算）。
    // 未传（如课程管理页预览）时退回当前周的常规显示。
    val colDates: List<LocalDate?> = if (columnSourceDates.size == 7) columnSourceDates
        else (0..6).map { getWeekDates(config, currentWeek).getOrNull(it) }
    // 预计算每列的 (周次, 星期几)：放假列为 null
    val colKey: Array<Pair<Int, Int>?> = Array(7) { col ->
        val d = colDates[col]
        if (d == null) null else weekOfDate(config, d) to d.dayOfWeek.value
    }

    // Step 1: build cellMap[row][col] = list of courses
    val cellMap = Array(slots.size) { Array(7) { mutableListOf<Course>() } }
    courses.forEach { c ->
        if (c.room == "-") return@forEach
        val (sr, sp) = parseSlotSpan(c.slot)
        if (sr < 0) return@forEach
        for (col in 0..6) {
            val (wk, dow) = colKey[col] ?: continue
            if (wk < 0 || wk !in c.weekStart..c.weekEnd || c.day != dow) continue
            for (rr in sr until minOf(sr + sp, slots.size)) cellMap[rr][col].add(c)
        }
    }

    // Step 2: find conflict rows for each col
    fun isConflictRow(r: Int, col: Int) = cellMap[r][col].size >= 2

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(totalH.dp)) {
            // Background + time column
            slots.forEachIndexed { r, si ->
                if (r % 6 == 0 && r > 0) Box(Modifier.fillMaxWidth().height(8.dp).offset(y = (r * ROW_H).dp).background(AppC.gridDivider))
                // 左侧时间列与课程区同色；整块课表用"卡片白"，页面的灰底只留在课表外面的空隙里
                // 开了背景图时这个底色会自动变半透明，让图透出来（见 AppC.tableCell）
                Box(Modifier.width(TIME_COL_W.dp).offset(y = (r * ROW_H).dp).height(ROW_H.dp).background(AppC.tableCell).padding(2.dp), contentAlignment = Alignment.Center) {
                    Text(si.name + "\n" + si.startTime + "-" + si.endTime, fontSize = 8.sp, color = AppC.textSecondary, textAlign = TextAlign.Center, lineHeight = 10.sp)
                }
            }
            for (r in slots.indices) for (c in 0..6) {
                // 课程区统一一个色，不再按半天交替深浅；用卡片白，灰底只在外面的空隙里
                Box(Modifier.offset(x = (TIME_COL_W + c * with(density) { colDp.roundToPx() / density.density }).dp, y = (r * ROW_H).dp).size(colDp, ROW_H.dp).background(AppC.tableCell))
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
                    val bg = AppC.warnBg; val fg = AppC.warnText
                    Box(Modifier.offset(x = xOffset, y = yOffset).width(colDp).height((ROW_H * span).dp)
                        .padding(2.dp).background(bg, RoundedCornerShape(8.dp)).padding(3.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCourseClick(list) }, contentAlignment = Alignment.Center) {
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
                        .width(colDp).height((ROW_H * h).dp).padding(2.dp)
                        // 色块填充走 AppC.blockFill：开了背景图才按用户设的透明度变淡，
                        // 没开图时原样返回（= 现在的样子）。文字颜色不受影响，始终不透明。
                        .background(AppC.blockFill(bg), RoundedCornerShape(8.dp)).padding(3.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCourseClick(listOf(c)) }, contentAlignment = Alignment.Center) {
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
                Box(Modifier.fillMaxWidth().offset(y = (totalH + 8).dp).background(AppC.gridAlt).padding(12.dp, 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("其他安排", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppC.textSecondary)
                            special.forEach { Text(it.name + "\uff08" + it.teacher + "\uff09", fontSize = 12.sp, color = AppC.textMuted) }
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
fun ManagePage(courses: List<Course>, prefs: android.content.SharedPreferences, onBack: () -> Unit, onCourseClick: (String) -> Unit, onAddCourse: () -> Unit, colorMap: MutableMap<String, Pair<Color, Color>>, refreshKey: Int, onSwapCourses: (Int, Int) -> Unit = { _, _ -> }) {
    val schedules = remember(courses) { courses.toSchedules() }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingCourse by remember { mutableStateOf<CourseSchedule?>(null) }
    // Multi-select state
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf(setOf<String>()) }
    val allSelected = selectedNames.size == schedules.size && schedules.isNotEmpty()
    // Delete confirmation state
    var pendingDeleteNames by remember { mutableStateOf<List<String>>(emptyList()) }
    // 长按拖动排序：交换必须同步写回 courses 状态（外层实现），
    // 若走 prefs→refreshKey→重载 的异步链，数据会比拖拽视觉状态晚一帧，
    // 表现为"两张卡先跳到对方位置再弹回来"的闪现。
    val dragState = rememberDragState()
    val dragPitch = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx().toFloat() }
    fun moveCourseAt(from: Int, delta: Int) = onSwapCourses(from, delta)

    Scaffold(
        topBar = {
            if (multiSelectMode) {
                TopAppBar(
                    title = { Text("已选择 ${selectedNames.size} 项") },
                    navigationIcon = { IconButton(onClick = { multiSelectMode = false; selectedNames = emptySet() }) { Icon(Icons.Default.Close, "退出多选") } },
                    actions = {
                        IconButton(onClick = { selectedNames = if (allSelected) emptySet() else schedules.map { it.name }.toSet() }) { Icon(if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, "全选") }
                        IconButton(onClick = { pendingDeleteNames = selectedNames.toList() }) { Icon(Icons.Default.Delete, "删除选中", tint = AppC.danger) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText, actionIconContentColor = AppC.headerText)
                )
            } else {
                TopAppBar(title = { Text("课程管理（${schedules.size}门）") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText))
            }
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                if (!multiSelectMode) {
                    FloatingActionButton(onClick = { multiSelectMode = true }, containerColor = AppC.accentFill, contentColor = readableOn(AppC.accentFill)) {
                        Icon(Icons.Default.Checklist, "多选", tint = readableOn(AppC.accentFill))
                    }
                }
                FloatingActionButton(onClick = { showAddDialog = true }, containerColor = AppC.success) { Icon(Icons.Default.Add, "添加课程", tint = Color.White) }
            }
        }
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(padding)) {
            if (schedules.isEmpty()) {
                item { EmptyHint("还没有课程", "点右下角 ＋ 添加第一门课，或导入课表文件") }
            }
            items(schedules.size) { i ->
                val sch = schedules[i]; val (bg, fg) = getCourseColor(sch.name, sch.color, colorMap, i)
                val checked = selectedNames.contains(sch.name)
                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()
                    .then(if (multiSelectMode) Modifier else Modifier.dragToReorder(dragState, i, schedules.size, extraPx = dragPitch) { from, d -> moveCourseAt(from, d) })
                    // indication=null：去掉整卡按压高亮，否则长按拖拽期间卡片蒙一层深灰"状态层"
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
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
                                    tint = if (checked) AppC.accent else AppC.iconIdle, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Surface(shape = RoundedCornerShape(6.dp), color = bg) { Text(sch.name, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.weight(1f))
                            if (!multiSelectMode) {
                                IconButton(onClick = { editingCourse = sch }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp)) }
                                IconButton(onClick = { pendingDeleteNames = listOf(sch.name) }) { Icon(Icons.Default.Delete, null, tint = AppC.danger, modifier = Modifier.size(20.dp)) }
                            }
                        }
                        Spacer(Modifier.height(4.dp)); Text("${sch.teacher} · ${sch.credit}学分 · ${sch.type} · ${sch.exam}", fontSize = 12.sp, color = AppC.textMuted); Text("${sch.slots.size}个时间段", fontSize = 11.sp, color = AppC.accent)
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (pendingDeleteNames.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDeleteNames = emptyList() },
            containerColor = AppC.card, shape = RoundedCornerShape(16.dp),
            title = { Text("确认删除") },
            text = { Text("确定要删除 ${pendingDeleteNames.size} 门课程吗？此操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                val u = courses.toMutableList()
                u.removeAll { c -> pendingDeleteNames.any { it == c.name } }
                saveCoursesToPrefs(prefs, u)
                pendingDeleteNames = emptyList()
                multiSelectMode = false; selectedNames = emptySet(); onAddCourse()
            }) { Text("删除", color = AppC.danger) } },
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
        EditCourseDialog(sch = sch, onDismiss = { editingCourse = null }, onConfirm = { n, t, cr, un, clr, ex ->
            val updated = courses.toMutableList()
            val newFlat = sch.copy(name = n, teacher = t, credit = cr, unit = un, color = clr, exam = ex).toFlatCourses()
            val payload = if (newFlat.isEmpty()) listOf(Course(n, t, "-", 1, "第1节", 1, 1, "必修", cr, ex, un, clr)) else newFlat
            // 原地替换：保持该课程在列表里的位置，编辑后不再跳到最后
            replaceCourse(updated, sch.name, payload)
            saveCoursesToPrefs(prefs, updated)
            editingCourse = null
            onAddCourse()
        })
    }
}

@Composable
fun AddCourseDialog(onDismiss: () -> Unit, onConfirm: (String, String, Double, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var teacher by remember { mutableStateOf("") }; var credit by remember { mutableStateOf("2.0") }; var unit by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = AppC.card, shape = RoundedCornerShape(16.dp), title = { Text("添加课程") },
        text = { Column { FormField("课程名称", name) { name = it }; FormField("任课教师", teacher) { teacher = it }; FormField("学分", credit) { credit = it }; FormField("开课单位", unit) { unit = it } } },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), teacher.trim(), credit.toDoubleOrNull() ?: 2.0, unit.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditCourseDialog(sch: CourseSchedule, onDismiss: () -> Unit, onConfirm: (String, String, Double, String, String, String) -> Unit) {
    var name by remember { mutableStateOf(sch.name) }
    var teacher by remember { mutableStateOf(sch.teacher) }
    var credit by remember { mutableStateOf(sch.credit.toString()) }
    var unit by remember { mutableStateOf(sch.unit) }
    var exam by remember { mutableStateOf(sch.exam) }
    var selectedColor by remember { mutableStateOf(sch.color) }
    var showCoursePicker by remember { mutableStateOf(false) }

    // 备选色直接取全局色板（外观设置里主题色的备选同源），不再各写一份字符串
    val colorOptions = COURSE_SWATCHES.map { encodeCourseColor(it.bg, it.fg) to it.bg }
    val presetColors = colorOptions.map { it.first }.toSet()
    // 既不是"自动"也不在任何备选里 → 用户自定义过的颜色，由最后那个框显示出来
    val customBg = parseCourseColor(selectedColor)?.first
    val showCustom = selectedColor != "auto" && selectedColor !in presetColors

    AlertDialog(onDismissRequest = onDismiss, containerColor = AppC.card, shape = RoundedCornerShape(16.dp), title = { Text("编辑课程") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FormField("课程名称", name) { name = it }
                FormField("任课教师", teacher) { teacher = it }
                FormField("学分", credit) { credit = it }
                FormField("开课单位", unit) { unit = it }
                FormField("考核方式（如 考查 / 考试）", exam) { exam = it }
                Spacer(Modifier.height(8.dp))
                Text("课程颜色", fontSize = 12.sp, color = AppC.textMuted)
                Spacer(Modifier.height(4.dp))
                // 每行 6 格：第一行「自动 + 5 色」，第二行「5 色 + 自定义」，自定义正好落在最右
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ColorSwatch(selectedColor == "auto", AppC.chipGray, onPick = { selectedColor = "auto" }) {
                        Text("自动", fontSize = 8.sp, color = AppC.textMuted)
                    }
                    colorOptions.forEach { (colorStr, color) ->
                        ColorSwatch(selectedColor == colorStr, color, onPick = { selectedColor = colorStr }) {}
                    }
                    ColorSwatch(showCustom, customBg ?: AppC.chipGray, onPick = { showCoursePicker = true }) {
                        Icon(Icons.Default.Palette, "自定义颜色",
                            tint = if (showCustom) (customBg?.let { readableOn(it) } ?: AppC.textMuted) else AppC.textMuted,
                            modifier = Modifier.size(17.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), teacher.trim(), credit.toDoubleOrNull() ?: sch.credit, unit.trim(), selectedColor, exam.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })

    if (showCoursePicker) {
        ColorPickerDialog(
            title = "自定义课程颜色",
            initial = customBg ?: COURSE_SWATCHES.first().bg,
            onDismiss = { showCoursePicker = false },
            onConfirm = { c ->
                // 前景色按对比度自动配：用户挑浅色也不会出现白字糊在白底上
                selectedColor = encodeCourseColor(c, readableOn(c))
                showCoursePicker = false
            },
        )
    }
}

/** 课程颜色小方格：选中时描一圈主题色 */
@Composable
private fun ColorSwatch(
    selected: Boolean,
    bg: Color,
    onPick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onPick,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(2.dp, if (selected) AppC.accent else Color.Transparent),
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

// ====== 课程详情页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailPage(courses: List<Course>, courseName: String, prefs: android.content.SharedPreferences, onBack: () -> Unit, onSlotAdded: () -> Unit, colorMap: MutableMap<String, Pair<Color, Color>>, slots: MutableList<SlotItem>, onSwapSlots: (String, Int, Int) -> Unit = { _, _, _ -> }) {
    val schedule = remember(courses, courseName) { courses.toSchedules().find { it.name == courseName } }
    var showAddSlot by remember { mutableStateOf(false) }
    var showEditSlot by remember { mutableIntStateOf(-1) }
    // 时间段长按拖动排序：与课程页同理，交换须同步写回 courses（外层实现）才不闪
    val slotDragState = rememberDragState()
    val slotPitch = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx().toFloat() }
    fun moveSlotAt(from: Int, delta: Int) { schedule ?: return; onSwapSlots(courseName, from, delta) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(courseName) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText)) },
        floatingActionButton = { FloatingActionButton(onClick = { showAddSlot = true }, containerColor = AppC.success) { Icon(Icons.Default.Add, "添加时间段", tint = Color.White) } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            schedule?.let { sch ->
                Surface(color = AppC.card, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp), shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(16.dp)) { Text("时间安排", fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("${sch.teacher} · ${sch.credit}学分 · ${sch.type} · ${sch.exam}", fontSize = 13.sp, color = AppC.textMuted); Text(sch.unit, fontSize = 12.sp, color = AppC.textMuted) } }
                LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(sch.slots.size) { i -> val ts = sch.slots[i]; Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().dragToReorder(slotDragState, i, sch.slots.size, extraPx = slotPitch) { from, d -> moveSlotAt(from, d) }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("${dayNames[ts.day]}  ${ts.slot}", fontWeight = FontWeight.Bold, fontSize = 14.sp); Text(ts.room, fontSize = 12.sp, color = AppC.textMuted); Text("第${ts.weekStart}~${ts.weekEnd}周", fontSize = 11.sp, color = AppC.textMuted) }; IconButton(onClick = { showEditSlot = i }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp)) }; IconButton(onClick = { val u = courses.toMutableList(); sch.slots.removeAt(i); replaceCourse(u, courseName, sch.toFlatCourses()); saveCoursesToPrefs(prefs, u); onSlotAdded() }) { Icon(Icons.Default.Delete, null, tint = AppC.danger, modifier = Modifier.size(20.dp)) } } } } }
            } ?: Text("未找到课程", modifier = Modifier.padding(16.dp))
        }
    }
    if (showAddSlot && schedule != null) AddSlotDialog(onDismiss = { showAddSlot = false }, onConfirm = { d, s, ws, we, room -> val u = courses.toMutableList(); schedule.slots.add(TimeSlot(d, s, room, ws, we)); replaceCourse(u, courseName, schedule.toFlatCourses()); saveCoursesToPrefs(prefs, u); showAddSlot = false; onSlotAdded() }, slots = slots)

    if (showEditSlot >= 0 && schedule != null) {
        val ts = schedule.slots[showEditSlot]
        var editDay by remember { mutableIntStateOf(ts.day) }
        var editRoom by remember { mutableStateOf(ts.room) }
        var editWS by remember { mutableStateOf(ts.weekStart.toString()) }
        var editWE by remember { mutableStateOf(ts.weekEnd.toString()) }
        val editSelIndices = remember { mutableStateOf(ts.slot.split("、").mapNotNull { name -> slots.indexOfFirst { it.name == name }.takeIf { it >= 0 } }.toMutableSet()) }
        var editSlotError by remember { mutableStateOf(false) }
        @OptIn(ExperimentalLayoutApi::class)
        AlertDialog(onDismissRequest = { showEditSlot = -1 }, containerColor = AppC.card, shape = RoundedCornerShape(16.dp),
            title = { Text("编辑时间段") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("星期", fontSize = 12.sp, color = AppC.textMuted)
                    FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (d in 1..7) { val s = editDay == d; Surface(onClick = { editDay = d }, shape = RoundedCornerShape(8.dp), color = if (s) AppC.accent else AppC.chipGray) { Text(dayNames[d], modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 12.sp, color = if (s) readableOn(AppC.accent) else AppC.textSecondary) } }
                    }
                    Text("时间段（可多选，且要连续）", fontSize = 12.sp, color = AppC.textMuted)
                    FlowRow(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (idx in slots.indices) { val s = editSelIndices.value.contains(idx); Surface(onClick = { val n = editSelIndices.value.toMutableSet(); if (s) n.remove(idx) else n.add(idx); editSelIndices.value = n; editSlotError = false }, shape = RoundedCornerShape(8.dp), color = if (s) AppC.accent else AppC.chipGray) { Text(slots[idx].name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 12.sp, color = if (s) readableOn(AppC.accent) else AppC.textSecondary) } }
                    }
                    if (editSlotError) Text("*时间段必须连续", color = AppC.danger, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
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
                schedule.slots[showEditSlot] = newTS
                replaceCourse(u, courseName, schedule.toFlatCourses())
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
    AlertDialog(onDismissRequest = onDismiss, containerColor = AppC.card, shape = RoundedCornerShape(16.dp), title = { Text("添加时间段") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("星期", fontSize = 12.sp, color = AppC.textMuted)
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (d in 1..7) { val s = day == d; Surface(onClick = { day = d }, shape = RoundedCornerShape(8.dp), color = if (s) AppC.accent else AppC.chipGray) { Text(dayNames[d], modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 12.sp, color = if (s) readableOn(AppC.accent) else AppC.textSecondary) } } }
            Text("时间段（可多选，且要连续）", fontSize = 12.sp, color = AppC.textMuted)
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (idx in slots.indices) { val s = selectedSlots.contains(idx); Surface(onClick = { val n = selectedSlots.toMutableSet(); if (s) n.remove(idx) else n.add(idx); selectedSlots = n; slotVer++; slotError = false }, shape = RoundedCornerShape(8.dp), color = if (s) AppC.accent else AppC.chipGray) { Text(slots[idx].name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 12.sp, color = if (s) readableOn(AppC.accent) else AppC.textSecondary) } } }
            if (slotError) Text("*时间段必须连续", color = AppC.danger, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
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

// ====== 学期设置页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralInfoPage(config: ScheduleConfig, prefs: android.content.SharedPreferences, onBack: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, slots: MutableList<SlotItem>) {
    var tw by remember { mutableStateOf(config.totalWeeks.toString()) }; var sd by remember { mutableStateOf(config.startDate) }; var sch by remember { mutableStateOf(config.school) }; var maj by remember { mutableStateOf(config.major) }
    Scaffold(topBar = { TopAppBar(title = { Text("学期设置") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText)) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField("本学期周数", tw) { tw = it }
            DateField("学期开始日期", sd, "点击选择日期") { sd = it }
            FormField("学校", sch) { sch = it }
            FormField("专业", maj) { maj = it }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { saveConfigToPrefs(prefs, ScheduleConfig((tw.toIntOrNull() ?: 18).coerceIn(1, TOTAL_WEEKS_MAX), sd, sch, maj)); onBack() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppC.accentFill, contentColor = readableOn(AppC.accentFill))) { Text("保存设置") }
        }
    }
}

// ====== 设置页（导入、导出、清空） ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(onBack: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, prefs: android.content.SharedPreferences, dataPrefs: android.content.SharedPreferences, onAbout: () -> Unit, onDeleteAll: () -> Unit, onDeveloper: () -> Unit = {}) {
    var showClearDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // 解释性副文本统一收进「使用手册」，这里一律只留标题
            DrawerMenuItem(icon = Icons.Default.Upload, label = "导出数据", onClick = onExport)
            DrawerMenuItem(icon = Icons.Default.Download, label = "导入数据", onClick = { onImport() })
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            // 「上课提醒」「外观设置」都不在这里：入口统一放侧边菜单，同一个功能不留两个门
            // 深色模式在「外观设置」页里（和主题色同属外观）
            DrawerMenuItem(icon = Icons.Default.Delete, label = "删除所有数据", onClick = { showClearDialog = true })
            // 开发者模式入口：默认隐藏，关于页连点版本号 8 次才出现（页面内总开关可关）
            if (AppC.devModeOn) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                DrawerMenuItem(icon = Icons.Default.Build, label = "开发者模式", onClick = onDeveloper)
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            // 「使用手册」也不放这里：入口只留侧边菜单那一个（同一个功能不留两个门）
            DrawerMenuItem(icon = Icons.Default.Info, label = "关于", onClick = onAbout)
        }
    }
    if (showClearDialog) {
        AlertDialog(onDismissRequest = { showClearDialog = false },
            containerColor = AppC.card, shape = RoundedCornerShape(16.dp),
            title = { Text("确认删除所有数据") },
            text = { Text("确定要删除所有数据吗？包括学期设置、时间表和课程安排，此操作不可撤销。\n" +
                (if (TestSchedule.isEnabled(context)) "当前正在显示【测试课表】，删除的只会是测试数据，你的真实课表不受影响。" else "")) },
            confirmButton = { TextButton(onClick = {
                // 删的是"当前正在显示的这份"：开着测试课表时只清测试文件，真实数据绝不会被误删
                dataPrefs.edit()
                    .remove("courses_json")
                    .remove("totalWeeks")
                    .remove("startDate")
                    .remove("school")
                    .remove("major")
                    .putString("slots_json", "")
                    .putString("holidays_json", "")
                    .apply()
                ReminderNotifier.cancel(context)
                onDeleteAll()
                showClearDialog = false
            }) { Text("确定删除", color = AppC.danger) } },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }
}

// ====== 时间表设置页 ======
@Composable
fun EmptyHint(title: String, desc: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Inbox, null, tint = AppC.emptyIcon, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppC.textSlate)
        Spacer(Modifier.height(4.dp))
        Text(desc, fontSize = 13.sp, color = AppC.textHint, textAlign = TextAlign.Center)
    }
}

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
                        IconButton(onClick = { pendingDeleteIndices = selectedIndices.toSet() }) { Icon(Icons.Default.Delete, "删除选中", tint = AppC.danger) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText, actionIconContentColor = AppC.headerText)
                )
            } else {
                TopAppBar(title = { Text("时间表设置（${slots.size}节）") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText))
            }
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                if (!multiSelectMode) {
                    FloatingActionButton(onClick = { multiSelectMode = true }, containerColor = AppC.accentFill, contentColor = readableOn(AppC.accentFill)) {
                        Icon(Icons.Default.Checklist, "多选", tint = readableOn(AppC.accentFill))
                    }
                }
                FloatingActionButton(onClick = { showAddDialog = true }, containerColor = AppC.success) { Icon(Icons.Default.Add, "添加节次", tint = Color.White) }
            }
        }
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(padding)) {
            if (slots.isEmpty()) {
                item { EmptyHint("还没有节次", "点右下角 ＋ 添加第一节，或导入课表文件") }
            }
            items(slots.size) { i ->
                val si = slots[i]
                val checked = selectedIndices.contains(i)
                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (multiSelectMode) {
                        selectedIndices = if (checked) selectedIndices - i else selectedIndices + i
                    }
                }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (multiSelectMode) {
                            Icon(if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank, null,
                                tint = if (checked) AppC.accent else AppC.iconIdle, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(si.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${si.startTime} - ${si.endTime}", fontSize = 13.sp, color = AppC.accent)
                        }
                        if (!multiSelectMode) {
                            IconButton(onClick = { editingSlot = si }) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(20.dp)) }
                            IconButton(onClick = { pendingDeleteIndices = setOf(i) }) { Icon(Icons.Default.Delete, null, tint = AppC.danger, modifier = Modifier.size(20.dp)) }
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
            containerColor = AppC.card, shape = RoundedCornerShape(16.dp),
            title = { Text("确认删除") },
            text = { Text("确定要删除这 ${pendingDeleteIndices.size} 个节次吗？此操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                val toRemove = pendingDeleteIndices.sortedDescending()
                for (idx in toRemove) slots.removeAt(idx)
                saveSlots(prefs, slots)
                pendingDeleteIndices = emptySet()
                multiSelectMode = false; selectedIndices = emptySet(); onRefresh()
            }) { Text("删除", color = AppC.danger) } },
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
    AlertDialog(onDismissRequest = onDismiss, containerColor = AppC.card, shape = RoundedCornerShape(16.dp), title = { Text(if (name.isEmpty()) "添加节次" else "编辑节次") },
        text = { Column { FormField("节次名称", n) { n = it }; TimeField("开始时间", s, "点击选择时间") { s = it }; TimeField("结束时间", e, "点击选择时间") { e = it } } },
        confirmButton = { TextButton(onClick = {
            // 中文输入法可能打出全角冒号/全角数字，入库前统一归一化
            val ns = normalizeTimeText(s); val ne = normalizeTimeText(e)
            if (n.isNotBlank() && ns.isNotBlank() && ne.isNotBlank()) onConfirm(n.trim(), ns, ne)
        }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// ====== 上课提醒页 ======
/** 保活弹窗里的一行：标题+说明+（可选状态图标）+去设置按钮。ok=null 表示无法自动检测 */
@Composable
fun KeepAliveRow(title: String, desc: String, ok: Boolean?, actionText: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                when (ok) {
                    true -> { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.CheckCircle, null, tint = AppC.success, modifier = Modifier.size(15.dp)) }
                    false -> { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Warning, null, tint = AppC.orange, modifier = Modifier.size(15.dp)) }
                    else -> {}
                }
            }
            Text(desc, fontSize = 11.sp, color = AppC.textMuted, lineHeight = 15.sp)
        }
        TextButton(onClick = onAction) { Text(actionText, fontSize = 13.sp, color = AppC.accent) }
    }
}

/**
 * 「后台保活与闹钟权限」的内联版：四项直接摊在卡片里，不折叠、不弹窗。
 *
 * 只在**引导页**用。主 App 里这一块仍是"一行入口 + 弹窗"——老用户早就配好了，
 * 每次进来都摊开四大项是噪音；而引导页的新用户刚打开提醒，正需要一次把权限配对，
 * 藏进弹窗反而最容易被跳过。
 */
/**
 * 系统通知权限那一行：**点「去开启」才弹系统授权框**。
 *
 * 以前是走到引导第 6 步就自动弹，用户还没决定要不要用提醒就被系统框拦住，
 * 手一滑拒绝了还得去系统设置里翻回来。改成显式一行，由用户决定什么时候弹。
 * 已允许时按钮变成「已开启」，点它去系统通知设置里（想关掉也能关）。
 */
@Composable
fun NotificationPermissionRow(enabled: Boolean, onAction: () -> Unit) {
    KeepAliveRow(
        "系统通知",
        if (enabled) "已允许，到点会弹出提醒" else "未允许：到点不会有任何提醒弹出",
        ok = enabled,
        actionText = if (enabled) "已开启" else "去开启",
        onAction = onAction,
    )
}

@Composable
fun InlineKeepAliveCard(
    context: Context,
    permTick: Int,
    lockAdvised: Boolean,
    notificationsEnabled: Boolean,
    onLockAdvised: () -> Unit,
    onPermChanged: () -> Unit,
    onRequestNotification: () -> Unit,
) {
    val exactAllowed = remember(permTick) { ReminderNotifier.exactAlarmsAllowed(context) }
    Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("后台保活与闹钟权限", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            // 第一位永远是"系统通知"：没有它，后面四项配对得再齐也送不出提醒
            NotificationPermissionRow(enabled = notificationsEnabled, onAction = onRequestNotification)
            HorizontalDivider(color = AppC.divider)
            KeepAliveRow("精确闹钟", if (exactAllowed) "已允许，闹钟会准时触发" else "未允许：到点可能不准或不触发",
                ok = exactAllowed, actionText = "去开启") {
                ReminderNotifier.openAlarmSettings(context)
                onPermChanged()
            }
            HorizontalDivider(color = AppC.divider)
            KeepAliveRow("后台自启动", "允许后开机与划掉后台都能恢复提醒",
                ok = null, actionText = "去设置") { ReminderNotifier.openAutostartSettings(context) }
            HorizontalDivider(color = AppC.divider)
            KeepAliveRow("电池优化", "省电策略设为「不限制」，后台闹钟不被杀",
                ok = null, actionText = "去设置") { ReminderNotifier.openBatterySettings(context) }
            HorizontalDivider(color = AppC.divider)
            KeepAliveRow(
                "最近任务加锁",
                "打开最近任务 → 长按本应用卡片（或点锁定图标）→ 选「锁定」，出现小锁头即成功；手机没有锁定功能可忽略",
                ok = lockAdvised, actionText = if (lockAdvised) "已加锁" else "去加锁",
                onAction = onLockAdvised,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReminderPage(
    prefs: android.content.SharedPreferences,
    dataPrefs: android.content.SharedPreferences,
    courses: List<Course>,
    config: ScheduleConfig,
    slots: List<SlotItem>,
    onBack: () -> Unit,
    rearm: () -> Unit,
    version: Int,
    /** 引导页专用：保活权限卡摊平成"三选一下面的第二张卡"，而不是一行入口 + 弹窗 */
    inlineKeepAlive: Boolean = false
) {
    val context = LocalContext.current
    var cfg by remember(version) { mutableStateOf(loadReminderConfig(prefs)) }
    var permTick by remember { mutableIntStateOf(0) }
    var wheelOpen by remember { mutableStateOf(false) }
    var wheelFor by remember { mutableIntStateOf(MODE_NORMAL) }   // 滚轮这次是给哪种模式设值
    var keepAliveOpen by remember { mutableStateOf(false) }
    // "已去最近任务加锁"的确认状态：提醒页的黄色引导卡和保活弹窗共用，所以声明在这一层
    var lockAdvised by remember { mutableStateOf(ReminderNotifier.lockAdvised(context)) }

    // 所有操作直接落盘 + 重排闹钟：没有"保存"按钮，也不需要在返回时补保存
    fun persist(next: ReminderConfig) {
        cfg = next
        saveReminderConfig(prefs, next)
        rearm()
    }
    fun pickMode(m: Int) {
        if (m == cfg.mode) return
        // 重新用焦点：清掉历史"已关闭"记录，否则上次关掉的那段会一直静默到明天
        if (m == MODE_FOCUS) FocusNotifier.clearDismissed(context)
        // 离开焦点模式时立刻收掉常驻条（rearm 是异步的，先清一次更跟手）
        if (cfg.mode == MODE_FOCUS && m != MODE_FOCUS) {
            FocusNotifier.cancel(context)
            FocusNotifier.armTick(context, null)
        }
        // 切到焦点模式时顺带打开「下课时也提醒」：焦点条本身静默，下课那一声是唯一的听觉提示
        // （这是用户早先明确要求的联动；开关在焦点卡片里可见，想关随时能关）
        persist(if (m == MODE_FOCUS) cfg.copy(mode = m, endReminder = true) else cfg.copy(mode = m))
    }

    // 从系统设置页返回时自动刷新权限状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notificationsEnabled = remember(permTick, cfg.mode) { ReminderNotifier.notificationsGranted(context) }

    // 系统通知授权：Android 13+ 才有 POST_NOTIFICATIONS，低版本直接跳系统通知设置页。
    // 授权结果回来后 permTick++ 重算一次状态（授权/拒绝都要刷新那一行）。
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        permTick++
        rearm()
    }
    fun askNotification(): Unit {
        if (notificationsEnabled) {
            // 已经开着：点它是"去管理"，交给系统页（想关掉也行）
            ReminderNotifier.openNotificationSettings(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ReminderNotifier.openNotificationSettings(context)
        }
    }

    val upcoming = remember(courses, config, slots, cfg, version) {
        upcomingClassEvents(courses, config, slots, cfg, horizonDays = 8, holidays = loadHolidays(dataPrefs))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("上课提醒") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText)
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .background(AppC.bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 通知方式：三选一（互斥，从根上避免两套通知同时说话）----
            Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("通知方式", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    val modeNames = listOf("不启用通知", "普通通知", "焦点通知")
                    // 与「外观设置 → 深色模式」那组按钮同款：圆角方块、选中填主题色、无打勾。
                    // 不用 SegmentedButton —— 那是连体胶囊，和站内其它选择器风格不一致。
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modeNames.forEachIndexed { i, name ->
                            val sel = cfg.mode == i
                            Surface(
                                onClick = { pickMode(i) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) AppC.accentFill else AppC.chipGray,
                                border = BorderStroke(1.5.dp, if (sel) AppC.accent else Color.Transparent),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    name,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1,
                                    color = if (sel) readableOn(AppC.accentFill) else AppC.textSecondary,
                                )
                            }
                        }
                    }
                    // 三种方式各自说明统一收进「使用手册」，这里只留标题与选择器
                }
            }

            // 权限提示（只在真的要用通知时提示）。
            // 引导页不显示这条横幅：那里第二张卡的**第一行**就是「系统通知」，
            // 同一件事（没授权 + 去开启）再挂一张黄卡就是重复。
            if (cfg.mode != MODE_OFF && !notificationsEnabled && !inlineKeepAlive) {
                Surface(color = AppC.warnBg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.NotificationsOff, null, tint = AppC.warnText, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("通知权限未开启", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppC.warnText)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("不开启的话到点不会有提醒弹出。", fontSize = 12.sp, color = AppC.warnText)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            ReminderNotifier.openNotificationSettings(context); permTick++
                        }, colors = ButtonDefaults.buttonColors(containerColor = AppC.amber)) { Text("去开启") }
                    }
                }
            }

            // 引导页专用：把「后台保活与闹钟权限」直接摊成第二张卡（紧跟三选一），
            // 五项平铺（系统通知 + 四项保活）、不折叠也不弹窗 ——
            // 新用户此刻刚开提醒，正是最该一次配对完的时候。
            // 没启用任何通知方式时不必出现（没有闹钟要保活）。
            if (inlineKeepAlive && cfg.mode != MODE_OFF) {
                InlineKeepAliveCard(
                    context = context,
                    permTick = permTick,
                    lockAdvised = lockAdvised,
                    notificationsEnabled = notificationsEnabled,
                    onLockAdvised = {
                        ReminderNotifier.setLockAdvised(context, true)
                        lockAdvised = true
                    },
                    onPermChanged = { permTick++ },
                    onRequestNotification = { askNotification() },
                )
            }

            // ---- 普通通知：提醒时间（可多选）+ 下课也提醒 ----
            if (cfg.mode == MODE_NORMAL) {
                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("提醒时间", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.weight(1f))
                            Text("可多选", fontSize = 12.sp, color = AppC.textFaint2)
                        }
                        Spacer(Modifier.height(4.dp))
                        val presets = ADVANCE_PRESETS
                        val selected = cfg.advances.toSet()
                        val customs = (selected - presets.toSet()).sorted()
                        (presets + customs).sorted().distinct().forEach { m ->
                            AdvanceRow(
                                label = advanceRowLabel(m),
                                checked = selected.contains(m),
                                custom = !presets.contains(m),
                                onToggle = {
                                    val next = if (selected.contains(m)) selected - m else selected + m
                                    persist(cfg.copy(advanceMinutes = next.sorted()))
                                },
                                onDelete = {
                                    persist(cfg.copy(advanceMinutes = (selected - m).sorted()))
                                }
                            )
                            HorizontalDivider(color = AppC.divider)
                        }
                        if (selected.isEmpty()) {
                            Text("未选择任何时间，不会弹出上课提醒", fontSize = 12.sp, color = AppC.orange, modifier = Modifier.padding(vertical = 8.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            onClick = { wheelFor = MODE_NORMAL; wheelOpen = true },
                            shape = RoundedCornerShape(10.dp),
                            color = AppC.chipGray,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, tint = AppC.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("自定义时间", fontSize = 14.sp, color = AppC.accent, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("下课时也提醒", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        Switch(checked = cfg.endReminder, onCheckedChange = {
                            persist(cfg.copy(endReminder = it))
                        }, colors = SwitchDefaults.colors(checkedTrackColor = AppC.accentFill, checkedThumbColor = readableOn(AppC.accentFill)))
                    }
                }
            }

            // ---- 焦点通知：提醒时间（单选，自定义时长）+ 下课是否响一声 ----
            if (cfg.mode == MODE_FOCUS) {
                val curFocus = cfg.focusAdvanceMinutes.coerceIn(0, FOCUS_ADVANCE_CAP)
                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("提醒时间", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.weight(1f))
                            Text("单选", fontSize = 12.sp, color = AppC.textFaint2)
                        }
                        Spacer(Modifier.height(6.dp))
                        val presetList = ADVANCE_PRESETS
                        val shown = (presetList + if (curFocus in presetList) emptyList() else listOf(curFocus)).sorted()
                        shown.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { persist(cfg.copy(focusAdvanceMinutes = m)) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(advanceRowLabel(m), fontSize = 15.sp, color = AppC.textPrimary, modifier = Modifier.weight(1f))
                                RadioButton(
                                    selected = curFocus == m,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = AppC.accent, unselectedColor = AppC.iconIdle),
                                )
                            }
                            HorizontalDivider(color = AppC.divider)
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            onClick = { wheelFor = MODE_FOCUS; wheelOpen = true },
                            shape = RoundedCornerShape(10.dp),
                            color = AppC.chipGray,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, tint = AppC.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("自定义时长", fontSize = 14.sp, color = AppC.accent, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("下课时也提醒", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                        Switch(checked = cfg.endReminder, onCheckedChange = {
                            persist(cfg.copy(endReminder = it))
                        }, colors = SwitchDefaults.colors(checkedTrackColor = AppC.accentFill, checkedThumbColor = readableOn(AppC.accentFill)))
                    }
                }
            }

            if (cfg.mode != MODE_OFF) {
                // 预览
                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("接下来会提醒", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        when {
                            slots.none { it.startTime.isNotBlank() } -> Text("请先在「时间表设置」里填写每节课的起止时间", fontSize = 13.sp, color = AppC.orange)
                            courses.none { it.room != "-" } -> Text("还没有安排教室的课程，先去「课程管理」添加吧", fontSize = 13.sp, color = AppC.orange)
                            upcoming.isEmpty() -> Text("近期没有课程（学期起止日期或周次范围可能已过）", fontSize = 13.sp, color = AppC.textMuted)
                            else -> upcoming.take(8).forEach { e ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = AppC.iconCircleBg) {
                                        Text("${e.dayLabel()} ${e.startText()}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 11.sp, color = AppC.titleDark, fontWeight = FontWeight.Medium)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(e.course.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(e.course.slot + " · ${e.slotLabel}", fontSize = 11.sp, color = AppC.textMuted)
                                    }
                                    if (e.course.room.isNotBlank()) Text(e.course.room, fontSize = 12.sp, color = AppC.accent)
                                }
                                if (e != upcoming.take(8).last()) HorizontalDivider()
                            }
                        }
                    }
                }

                // 通知显示（悬浮横幅）/ 加锁引导 / 保活入口
                val headsUpOk = remember(permTick) { ReminderNotifier.headsUpReady(context) }
                val showHeadsUp = cfg.mode == MODE_NORMAL
                // 引导页里“加锁”已是第二张卡上的常驻一行、保活四项也已摊开
                // → 这里不再重复黄色引导卡，也不出现入口行。
                val showLockGuide = !inlineKeepAlive && cfg.mode == MODE_NORMAL && !lockAdvised
                val showEntry = !inlineKeepAlive
                // 都没有就别画空卡（焦点通知 + 引导页：两项都不成立）
                if (showHeadsUp || showLockGuide || showEntry) {
                Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (inlineKeepAlive) "通知显示" else "通知显示与后台保活", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.height(10.dp))

                        // 悬浮横幅（焦点通知是静默条，不谈横幅；这里只在普通通知时判它的渠道）
                        if (showHeadsUp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("锁屏/悬浮通知", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text(
                                        if (headsUpOk) "已开启，到点会在屏幕顶部弹出横幅"
                                        else "未开启：通知只安静地躺在通知栏里，不会弹出",
                                        fontSize = 11.sp, color = if (headsUpOk) AppC.success else AppC.orange
                                    )
                                }
                                if (!headsUpOk) {
                                    TextButton(onClick = { ReminderNotifier.openChannelSettings(context); permTick++ }) {
                                        Text("去开启", fontSize = 13.sp, color = AppC.accent)
                                    }
                                }
                            }
                            // 后面还有内容才画分隔线，否则卡片底部会挂一条悬空的线
                            if (showLockGuide || showEntry) {
                                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = AppC.divider)
                            }
                        }

                        // 最近任务加锁引导：没有常驻通知时，这是防“划掉后台丢闹钟”的关键一步
                        if (showLockGuide) {
                            Surface(color = AppC.warnBg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.NotificationsActive, null, tint = AppC.orange, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("请把本应用「加锁」", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppC.warnText)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "本应用没有常驻通知。不少安卓手机在最近任务里划掉应用时，会连带删掉它登记的闹钟，" +
                                            "之后到点就不会提醒了。加锁方法：打开最近任务 → 长按本应用卡片（或点卡片上的锁定图标）→ " +
                                            "选择「锁定」，卡片出现小锁头即成功。加锁后划清其他应用不会影响本应用的提醒；" +
                                            "如果你的手机没有锁定功能，可忽略这条。",
                                        fontSize = 12.sp, color = AppC.warnText, lineHeight = 17.sp
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = {
                                        ReminderNotifier.setLockAdvised(context, true)
                                        lockAdvised = true
                                    }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppC.orange)) {
                                        Text("我知道了，已去加锁", fontSize = 13.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider(Modifier.padding(vertical = 0.dp), color = AppC.divider)
                        }

                        // 后台保活设置入口：精确闹钟/自启动/电池优化三项合并成一个入口，点进去逐项处理
                        if (showEntry) {
                            val exactOk = remember(permTick) { ReminderNotifier.exactAlarmsAllowed(context) }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { keepAliveOpen = true }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("后台保活与闹钟权限", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    // 只在真的有问题时提示；“建议检查…”那种正确的废话一律不写
                                    if (!exactOk) {
                                        Text("精确闹钟未允许，提醒可能不准，点进去开启",
                                            fontSize = 11.sp, color = AppC.orange, lineHeight = 15.sp)
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, "打开", tint = AppC.iconIdle)
                            }
                        }
                    }
                }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (keepAliveOpen) {
        val exactAllowed = ReminderNotifier.exactAlarmsAllowed(context)
        AlertDialog(
            onDismissRequest = { keepAliveOpen = false },
            containerColor = AppC.card, shape = RoundedCornerShape(16.dp),
            title = { Text("后台保活与闹钟权限") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 和引导页同一套顺序：系统通知打头，后面才是四项保活
                    NotificationPermissionRow(enabled = notificationsEnabled, onAction = { askNotification() })
                    HorizontalDivider(color = AppC.divider)
                    KeepAliveRow("精确闹钟", if (exactAllowed) "已允许，闹钟会准时触发" else "未允许：到点可能不准或不触发",
                        ok = exactAllowed, actionText = "去开启") {
                        ReminderNotifier.openAlarmSettings(context); permTick++
                    }
                    HorizontalDivider(color = AppC.divider)
                    KeepAliveRow("后台自启动", "允许后开机与划掉后台都能恢复提醒",
                        ok = null, actionText = "去设置") { ReminderNotifier.openAutostartSettings(context) }
                    HorizontalDivider(color = AppC.divider)
                    KeepAliveRow("电池优化", "省电策略设为「不限制」，后台闹钟不被杀",
                        ok = null, actionText = "去设置") { ReminderNotifier.openBatterySettings(context) }
                    HorizontalDivider(color = AppC.divider)
                    // 最近任务加锁：系统里没有对应的开关页，只能给步骤 + 让用户自己确认
                    KeepAliveRow(
                        "最近任务加锁",
                        "打开最近任务 → 长按本应用卡片（或点卡片上的锁定图标）→ 选「锁定」，卡片出现小锁头即成功；手机没有锁定功能可忽略",
                        ok = lockAdvised, actionText = if (lockAdvised) "已加锁" else "去加锁",
                    ) {
                        ReminderNotifier.setLockAdvised(context, true)
                        lockAdvised = true
                    }
                }
            },
            confirmButton = { TextButton(onClick = { keepAliveOpen = false }) { Text("完成", color = AppC.accent) } },
        )
    }
    if (wheelOpen) {
        AdvanceWheelDialog(
            onDismiss = { wheelOpen = false },
            onConfirm = { minutes ->
                wheelOpen = false
                if (minutes in 1..ReminderNotifier.ADVANCE_MAX) {
                    if (wheelFor == MODE_FOCUS) {
                        persist(cfg.copy(focusAdvanceMinutes = minutes.coerceAtMost(FOCUS_ADVANCE_CAP)))
                    } else {
                        persist(cfg.copy(advanceMinutes = (cfg.advances + minutes).distinct().sorted()))
                    }
                }
            }
        )
    }
}

// 提醒时间预设档位（"开始时" + 常用分钟数）
// 注意：这里只到 30 分钟，更大的提前量走「自定义时间」滚轮
val ADVANCE_PRESETS = listOf(0, 5, 10, 15, 20, 30)

// ====== 提醒时间列表行 ======
@Composable
fun AdvanceRow(label: String, checked: Boolean, custom: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = AppC.textPrimary, modifier = Modifier.weight(1f))
        if (custom) {
            TextButton(onClick = onDelete) { Text("删除", fontSize = 12.sp, color = AppC.textDisabled) }
        }
        Surface(
            onClick = onToggle,
            shape = RoundedCornerShape(percent = 50),
            color = if (checked) AppC.accent else AppC.switchOff,
            modifier = Modifier.size(26.dp)
        ) {
            if (checked) Icon(Icons.Default.Check, null, tint = readableOn(AppC.accent), modifier = Modifier.size(18.dp))
        }
    }
}

// ====== 自定义提前量滚轮选择弹窗 ======
@Composable
fun AdvanceWheelDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    // 单位只有分钟/小时，最大提前量 = 24 小时
    val units = listOf("分钟" to 1, "小时" to 60)
    var unitIndex by remember { mutableIntStateOf(0) }
    var value by remember { mutableIntStateOf(3) }

    // 单位变化后可选数值范围不同，需把数值夹回合法区间
    val maxValue = when (unitIndex) {
        0 -> 60
        else -> 24
    }
    LaunchedEffect(unitIndex) { if (value > maxValue) value = maxValue }

    val minutes = (value * units[unitIndex].second).coerceAtMost(ReminderNotifier.ADVANCE_MAX)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppC.card,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("自定义", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppC.textPrimary)
                Spacer(Modifier.height(4.dp))
                // 标题直接跟随滚轮读数，避免 60分钟/24小时 被换算成 1小时/1天 与滚轮不一致
                Text("${value}${units[unitIndex].first}前", fontSize = 14.sp, color = AppC.textFaint)
            }
        },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                WheelColumn(
                    items = (1..maxValue).map { it.toString() },
                    selectedIndex = value - 1,
                    onSelected = { value = (it + 1).coerceIn(1, maxValue) },
                    modifier = Modifier.weight(1f),
                    loop = true
                )
                WheelColumn(
                    items = units.map { it.first },
                    selectedIndex = unitIndex,
                    onSelected = { unitIndex = it },
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(minutes) },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppC.pickerBlue)
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            ) { Text("确定", color = readableOn(AppC.pickerBlue), fontWeight = FontWeight.Medium) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppC.wheelBg)
                    .padding(horizontal = 28.dp, vertical = 10.dp)
            ) { Text("取消", color = AppC.textPrimary) }
        }
    )
}

// ====== 单列滚轮 ======
// loop = true 时无限循环（1 上面接最大值、最大值下面接 1），只用于数字列；
// 单位列只有两项，循环滚动体验很差，保持普通列表。
@Composable
fun WheelColumn(items: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier, loop: Boolean = false) {
    val itemHeight = 44.dp
    val n = items.size.coerceAtLeast(1)
    // 循环模式从一个大的对齐起点开始，两边都有足够的滚动余量
    val base = if (loop) 1_000_000 - (1_000_000 % n) else 0
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = base + selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    )
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }

    // 档位数量变化（分钟↔小时切换）时，把列表重新对齐到当前选中项
    LaunchedEffect(items.size) {
        state.scrollToItem(base + selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    }

    // 居中项 = 首个可见项 + 半行以上偏移（循环模式下对数量取模）
    // key 必须含 selectedIndex：闭包会捕获它，少了它 collector 一直拿旧值，
    // 滚回初始项时 centered == 旧值就不回调（单位列"滚不回分钟"的根因）
    LaunchedEffect(state, items, loop, selectedIndex) {
        snapshotFlow {
            Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress)
        }.collect { (idx, offset, scrolling) ->
            val step = if (offset > itemHeightPx / 2) 1 else 0
            val centered = if (loop) ((idx + step) % n + n) % n else idx + step
            if (scrolling) {
                if (centered in items.indices && centered != selectedIndex) onSelected(centered)
            } else if (centered in items.indices && centered != selectedIndex) {
                // 滚动停止但列表停在非选中项（拖一半弹回、外部夹值等）→ 把列表对齐回选中项，
                // 否则高亮/标题和滚轮会永久脱节
                state.scrollToItem(base + selectedIndex)
            }
        }
    }

    LazyColumn(
        state = state,
        modifier = modifier.height(itemHeight * 3),
        contentPadding = PaddingValues(vertical = itemHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = state)
    ) {
        items(if (loop) Int.MAX_VALUE else items.size) { i ->
            val real = if (loop) i % n else i
            val active = real == selectedIndex
            Box(
                Modifier.fillMaxWidth().height(itemHeight),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    items[real],
                    fontSize = if (active) 20.sp else 17.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) AppC.pickerBlue else AppC.textDisabled,
                    maxLines = 1
                )
            }
        }
    }
}

// ====== 开发者模式页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperPage(onBack: () -> Unit, onDataReload: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // 从系统设置页返回时自动刷新权限/闹钟状态
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val diag = remember(tick) { ReminderNotifier.diagnose(context) }
    val exactOk = remember(tick) { ReminderNotifier.exactAlarmsAllowed(context) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("开发者模式") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText))
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .background(AppC.bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 总开关：关掉后设置页不再显示"开发者模式"入口，但当前页面保持停留（重新开启需再到关于页连点版本 8 次）
            Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("开发者模式", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = AppC.devModeOn,
                        onCheckedChange = { on ->
                            // 关掉时页内所有子设置恢复默认（含测试课表）；数据源可能因此从
                            // 测试文件切回真实文件，顺手原地重载一次，页面本身保持停留
                            DeveloperSettings.setEnabled(context, on)
                            onDataReload()
                            tick++
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = AppC.accentFill, checkedThumbColor = readableOn(AppC.accentFill)),
                    )
                }
            }

            // 测试课表：把课表数据整体换成一份专门构造的假数据，用来测通知的各种边界。
            // 实现上写在一个独立的 prefs 文件里，真实课表绝不会被覆盖（详见 TestSchedule）。
            val testOn = remember(tick) { TestSchedule.isEnabled(context) }
            Surface(
                color = if (testOn) AppC.warnBg else AppC.card,
                shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "测试课表",
                            fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            color = if (testOn) AppC.warnText else AppC.textPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = testOn,
                            onCheckedChange = { on ->
                                if (on) TestSchedule.enable(context) else TestSchedule.disable(context)
                                // 原地重读课表数据（不 recreate，否则会被弹回主页）+ 重新排期
                                onDataReload()
                                tick++
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = AppC.orange),
                        )
                    }
                    if (testOn) {
                        Spacer(Modifier.height(10.dp))
                        Text("已覆盖的测试点", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppC.warnText)
                        Spacer(Modifier.height(4.dp))
                        TestSchedule.CHECKLIST.forEach {
                            Text("· $it", fontSize = 11.sp, color = AppC.warnText, lineHeight = 15.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                TestSchedule.enable(context)   // 重新按"今天"生成一份
                                onDataReload()                 // 同样原地重载，不离开本页
                                tick++
                            },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, AppC.orange),
                        ) { Text("按今天重新生成测试数据", fontSize = 13.sp) }
                    }
                }
            }

            // 测试通知（原来放在上课提醒页，按用户要求挪到开发者模式里单独一张卡）
            Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("测试通知", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        ReminderNotifier.notifyTest(context)
                        android.widget.Toast.makeText(context, "已发送测试通知", android.widget.Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, AppC.accent)) { Text("发送测试通知", fontSize = 13.sp) }
                }
            }

            // 焦点通知：息屏显示这条路实测走不通，保留权限查询供排查
            Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("焦点通知（息屏显示）", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(8.dp))
                    val permDebug = remember(tick) { FocusNotifier.focusPermissionDebug(context) }
                    Text("权限查询：$permDebug", fontSize = 12.sp, color = AppC.textSecondary, lineHeight = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        FocusNotifier.notifyFocusTest(context)
                        android.widget.Toast.makeText(context, "已发送（去通知栏/锁屏看一眼）", android.widget.Toast.LENGTH_SHORT).show()
                        tick++
                    }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, AppC.accent)) { Text("发一条焦点通知测试", fontSize = 13.sp) }
                }
            }

            // 闹钟登记状态（排查"到点不弹"用）
            Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("闹钟登记状态", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(diag, fontSize = 12.sp, color = AppC.textSecondary, lineHeight = 18.sp)
                    if (!exactOk) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { ReminderNotifier.openAlarmSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text("开启精确闹钟权限", fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        ReminderNotifier.schedule(context); tick++
                        android.widget.Toast.makeText(context, "已重新排期", android.widget.Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, AppC.accent)) { Text("重新排期闹钟", fontSize = 13.sp) }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ====== 外观设置 ======
/**
 * 主题色：影响顶栏、按钮、选中态、开关、当天列高亮等一整套"强调色"。
 * 备选直接取课程色板 [COURSE_SWATCHES]（同一份数据源，改课程颜色这里跟着变），
 * 最后给一个调色盘入口让用户自定义任意颜色。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearancePage(prefs: android.content.SharedPreferences, onBack: () -> Unit, onEditBgPosition: () -> Unit = {}) {
    var showThemePicker by remember { mutableStateOf(false) }
    val current = AppC.themeColor
    val isPreset = COURSE_SWATCHES.any { it.bg == current }
    val isDefault = current == DEFAULT_THEME

    Scaffold(topBar = { TopAppBar(title = { Text("外观设置") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText)) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 深色模式从「设置」页搬来这里：和主题色同属外观，放在主题色上面
            Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("深色模式", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (v, label) ->
                            val sel = AppC.mode == v
                            Surface(
                                onClick = {
                                    AppC.mode = v
                                    prefs.edit().putInt("dark_mode", v).apply()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (sel) AppC.accentFill else AppC.chipGray,
                                border = BorderStroke(1.5.dp, if (sel) AppC.accent else Color.Transparent),
                            ) {
                                Text(
                                    label,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    fontSize = 13.sp,
                                    color = if (sel) readableOn(AppC.accentFill) else AppC.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
            Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("主题色", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        COURSE_SWATCHES.forEach { s ->
                            ThemeSwatch(current == s.bg, s.bg, onPick = {
                                AppC.themeColor = s.bg
                                saveThemeColor(prefs, s.bg)
                            }) {}
                        }
                        // 自定义框：当前色不在备选里时，它直接显示那个颜色
                        ThemeSwatch(!isPreset, if (isPreset) AppC.chipGray else current, onPick = { showThemePicker = true }) {
                            if (isPreset) {
                                Icon(Icons.Default.Palette, "自定义主题色", tint = AppC.textMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 出厂默认蓝不在课程色板里，所以它落在"自定义"框上 —— 这里只报当前值，别写成"自定义"误导
                    Text(if (isPreset) "备选色与课程颜色同源，挑一个即可。" else "当前颜色：${hexOf(current)}",
                        fontSize = 11.sp, color = AppC.textMuted)
                    Spacer(Modifier.height(10.dp))
                    // 预览：顶栏 + 主按钮 + 选中态 + 当天列，改色时当场看到效果
                    AppearancePreview()
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = {
                            clearThemeColor(prefs)
                            AppC.themeColor = DEFAULT_THEME
                        },
                        enabled = !isDefault,
                    ) { Text("恢复默认（蓝色）", fontSize = 13.sp, color = if (isDefault) AppC.textDisabled else AppC.accent) }
                }
            }

            // ---- 课表背景图片 ----
            BackgroundImageCard(onEditPosition = onEditBgPosition)
        }
    }

    if (showThemePicker) {
        ColorPickerDialog(
            title = "自定义主题色",
            initial = if (isPreset) DEFAULT_THEME else current,
            onDismiss = { showThemePicker = false },
            onConfirm = { c ->
                AppC.themeColor = c
                saveThemeColor(prefs, c)
                showThemePicker = false
            },
        )
    }
}

@Composable
private fun ThemeSwatch(selected: Boolean, bg: Color, onPick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onPick,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(2.5.dp, if (selected) AppC.accent else Color.Transparent),
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
            if (selected) Icon(Icons.Default.Check, "已选", tint = readableOn(bg), modifier = Modifier.size(18.dp))
        }
    }
}

/** 用当前主题色画一小段仿真界面，避免"改完要跑到别的页面才知道好不好看" */
@Composable
private fun AppearancePreview() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(AppC.bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().background(AppC.headerBlue, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Icon(Icons.Default.NotificationsActive, null, tint = readableOn(AppC.headerBlue), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("课程表", color = readableOn(AppC.headerBlue), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(30.dp).background(AppC.accentFill, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Text("主按钮", color = readableOn(AppC.accentFill), fontSize = 12.sp)
            }
            // 选中胶囊：主题色描边 + 主题色文字
            Box(Modifier.background(AppC.chipGray, RoundedCornerShape(16.dp))
                .border(1.5.dp, AppC.accent, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                Text("选中态", color = AppC.accent, fontSize = 12.sp)
            }
            // 当天列高亮
            Box(Modifier.background(AppC.todayBg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("今天", color = AppC.titleDark, fontSize = 12.sp)
            }
        }
    }
}

// ====== 使用手册 ======
/**
 * 各页面被精简掉的解释性文字统一收在这里：页面上只留标题和控件，
 * 想弄明白"这项是干什么的"再来这页查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualPage(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("使用手册") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText)
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .background(AppC.bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ManualSection("学期设置") {
                ManualLine("本学期周数：一学期上多少周，1~52（一年 52 周），课程表的周次条就到第几周。")
                ManualLine("学期开始日期：填第 1 周周一的日期，决定今天算第几周、以及课程按哪一周显示。")
                ManualLine("学校、专业只是标题栏上的字，不影响课表内容。")
            }
            ManualSection("时间表设置") {
                ManualLine("定义每节课的名称和起止时间，例如「第1节 08:30-09:15」。")
                ManualLine("课程是按「节次名称」对齐到这张表的：课程里写「第1节」，这里就得有叫「第1节」的条目，否则那一门课在课表上找不到位置。")
                ManualLine("支持长按拖拽排序、多选批量删除。")
            }
            ManualSection("课程管理") {
                ManualLine("一门课可以添加多个时间段（各自的星期、节次、教室、周次范围）。")
                ManualLine("同一星期同一节次撞上两门课时，课表会标出冲突，点击可查看是哪几节。")
                ManualLine("每门课自动分配一种颜色，同名课程在整个列表里保持一致。")
                ManualLine("支持长按拖拽排序、多选批量删除。")
            }
            ManualSection("外观设置") {
                ManualLine("入口是侧边菜单的「外观」（页面标题叫「外观设置」），不在「设置」页里。")
                ManualLine("深色模式：跟随系统 / 浅色 / 深色 三选一，放在这一页最上面。")
                ManualLine("不想进设置页的话，侧边栏左下角有个快捷图标，点一下按 跟随系统 → 深色 → 浅色 循环；图标本身就是当前状态（手机=跟随系统、月亮=深色、太阳=浅色）。")
                ManualLine("主题色会带动顶栏、按钮、开关、选中态、课表「今天」那一列的高亮。")
                ManualLine("备选色与课程颜色是同一份色板；最后那个调色盘格子可以挑任意颜色。")
                ManualLine("应用里用到的颜色就是你点中的那块，不会被调暗；文字会自动在黑白之间切换保证看得清（挑亮黄时顶栏字会变黑）。")
                ManualLine("「恢复默认」回到出厂蓝。改动会记住，重启后仍在。")
                ManualLine("课表背景图片：开关默认关。打开后可以选一张照片垫在课表底下，图片会复制进应用内部，之后删掉相册里的原图也不影响。")
                ManualLine("关掉开关图片会保留 —— 再打开还是上次那张，不用重新选；想换才需要重新选图。")
                ManualLine("「遮盖强度」控制图片上压的那层底色：图太花、课表看不清就调高，想看清图就调低。出厂默认 20%，旁边有按钮一键还原。")
                ManualLine("「课程色块透明度」：调高它课程色块会变淡、露出底下的背景图（文字不受影响，仍然不透明）。")
                ManualLine("这一项只在开着背景图时生效；关掉背景图时色块自动恢复实心，等于这项不存在。")
                ManualLine("「图像位置编辑」：进去是对着模拟的课程表调图的位置 —— 双指捏合缩放、**单指拖动**平移。")
                ManualLine("位置按窗口形态分别保存：全屏 / 小窗 / 分屏各一份，互不影响（入口和页面里都会写明当前是哪一种）。")
                ManualLine("图被裁掉的部分不用先放大，直接拖动就能把想露出来的部分挪进画面；「重置」回到铺满并居中。")
                ManualLine("编辑页里那三行（通知提醒/周次/星期）就是主页面的同一批组件，所以看到的版式和主页面一模一样。")
                ManualLine("底部有「显示通知栏」开关：不启用通知时那一栏没什么内容却占地方，关掉能多看一截图。")
            }
            ManualSection("上课提醒 · 三种方式") {
                ManualLine("不启用通知：不发送任何通知，也不需要别的设置。")
                ManualLine("普通通知：到点发通知，会响铃/震动；课前、准点、下课各自一个通知类别，可在系统设置里分别调声音与横幅。")
                ManualLine("焦点通知：全程只挂一条静默的常驻倒计时（不响铃），课前显示还有多久上课，课上显示还有多久下课。")
                ManualLine("三种方式互斥，同一时间只有一套在工作。切换即时生效，不需要另外点保存。")
            }
            ManualSection("焦点常驻通知的行为") {
                ManualLine("从设定的提前量那一刻起出现，一直到这一段课下课。")
                ManualLine("到点不响铃，只在通知栏（含锁屏）显示剩余时间，每分钟更新。")
                ManualLine("连堂算一次：整段下课才收起，下一段照常出现。")
                ManualLine("它是常驻通知，右滑划不掉、点「清除全部」也不会消失；要收掉请点通知上的「关闭」。")
                ManualLine("息屏显示（AOD）目前不支持：实测在部分机型上带该参数的通知反而完全不显示，所以走「亮屏锁屏」方案。")
            }
            ManualSection("节假日与调休") {
                ManualLine("设置放假区间和补课日（补课日要指明它补的是哪一天的课）。")
                ManualLine("课表会自动重排：放假那天那列清空并显示「休」，补课那天显示被补的那一天的课并显示「补」。")
            }
            ManualSection("导入与导出") {
                ManualLine("导出为一个 Markdown 文件，包含学期设置、时间表、课程、节假日四段，可手动编辑后再导入。")
                ManualLine("导入是覆盖式更新，会替换手机上现有的同类数据。")
                ManualLine("日期写法是宽容的：2026-10-01、2026/10/1、2026年10月1日 都认。")
                ManualLine("也可以在文件管理器里直接打开 .md 文件导入。")
            }
            ManualSection("提醒不响？逐项检查这些") {
                ManualLine("通知权限：没开的话到点不会有提醒弹出。")
                ManualLine("精确闹钟：未允许时提醒时间可能偏移甚至不触发。")
                ManualLine("悬浮横幅：关掉后通知只会安静地躺在通知栏里。")
                ManualLine("后台自启动：部分国产系统需要允许，开机后提醒才不会被清掉。")
                ManualLine("电池优化：省电策略会杀后台，建议设为「不限制」。")
                ManualLine("最近任务加锁：打开最近任务 → 长按本应用卡片 → 选「锁定」。不加锁的话划掉后台会连带删掉已登记的闹钟。")
                ManualLine("改过系统时间后回到 App 会自动重新排期；也可以到「上课提醒」页确认下一次触发时间。")
            }
            ManualSection("手势与快捷操作") {
                ManualLine("课程表区域左右滑动 = 切换周次；周次胶囊点一下可直接跳到那一周。")
                ManualLine("从屏幕左边缘向右滑 = 打开侧边栏。")
                ManualLine("关于页连点版本号 8 次 = 打开开发者模式（可在其页面内关闭入口）。")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ManualSection(title: String, lines: @Composable () -> Unit) {
    Surface(color = AppC.card, shape = RoundedCornerShape(12.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            lines()
        }
    }
}

@Composable
private fun ManualLine(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("·", fontSize = 13.sp, color = AppC.textMuted, modifier = Modifier.width(12.dp))
        Text(text, fontSize = 13.sp, color = AppC.textSecondary, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

// ====== 关于页 ======
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    // 连点版本号 8 次开启开发者模式（1 秒内连点才计数，跟安卓原生逻辑一致）。
    // 提示必须用 Snackbar 而不是 Toast：Android 11+ 上 Toast.cancel() 是空操作，
    // 连点后队列里的旧"再点N次"必然重放，让人误以为没开启成功。
    var devTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val snackbarHost = remember { SnackbarHostState() }
    val hintScope = rememberCoroutineScope()
    var hintJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun showHint(text: String) {
        hintJob?.cancel()
        hintJob = hintScope.launch { snackbarHost.showSnackbar(text, duration = SnackbarDuration.Short) }
    }
    fun clearHint() {
        hintJob?.cancel()
        snackbarHost.currentSnackbarData?.dismiss()
    }
    Scaffold(topBar = { TopAppBar(title = { Text("关于") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AppC.headerBlue, titleContentColor = AppC.headerText, navigationIconContentColor = AppC.headerText)) },
        snackbarHost = {
            // 自绘胶囊样式：默认 Snackbar 的深灰大块和这页的浅色卡片风格完全不搭。
            // 机制仍走 SnackbarHost（Toast 在 Android 11+ 有 cancel 空操作的旧提示重放问题）。
            SnackbarHost(snackbarHost) { data ->
                val msg = data.visuals.message
                val done = msg.contains("已开启") || msg.contains("已进入")
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = if (done) AppC.successBg else AppC.card,
                    border = BorderStroke(1.dp, if (done) AppC.success.copy(alpha = 0.45f) else AppC.cardBorder),
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp)
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (done) Icons.Default.CheckCircle else Icons.Default.Build,
                            null,
                            tint = if (done) AppC.success else AppC.amber,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(msg, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = if (done) AppC.successText else AppC.textPrimary)
                    }
                }
            }
        }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(AppC.chipGray).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            // 头部卡片：图标 + 名称 + 一句话简介
            Surface(color = AppC.card, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_about),
                        contentDescription = "App Icon",
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("大学课程表", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppC.textPrimary)
                        Text("自定义课程时间表", fontSize = 12.sp, color = AppC.textMuted)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            // 信息列表卡片：图标行风格，GitHub 行可点跳浏览器
            Surface(color = AppC.card, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    AboutIconRow(rememberVectorPainter(Icons.Default.Code), "版本", value = "v${appVersionName(context)}", onClick = {
                        val t = System.currentTimeMillis()
                        devTaps = if (t - lastTapAt < 1000) devTaps + 1 else 1
                        lastTapAt = t
                        if (AppC.devModeOn) {
                            // 已经开着就绝不能再用"再点 N 次开启"的倒计时——会让人以为没生效。
                            // 到了原本该出提示的节点，直接确认"已在开发者模式"，后续连点保持安静。
                            if (devTaps == 5) showHint("已进入开发者模式")
                            if (devTaps >= 8) devTaps = 0
                        } else if (devTaps >= 8) {
                            devTaps = 0
                            // 统一走入口写标志位（开启不涉及子设置重置）
                            DeveloperSettings.setEnabled(context, true)
                            showHint("开发者模式已开启")
                        } else if (devTaps >= 5) {
                            // 接近时给个提示，跟安卓"再点 N 次就开启"一样
                            showHint("再点 ${8 - devTaps} 次开启开发者模式")
                        }
                    })
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = AppC.divider)
                    AboutIconRow(rememberVectorPainter(Icons.Default.PhoneAndroid), "平台", value = "Android")
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = AppC.divider)
                    // 版本号永远是 2.0，装的是哪一版只能靠这个"安装时间"分辨：
                    // 报 bug 前先对一眼，别拿旧包测了半天
                    AboutIconRow(rememberVectorPainter(Icons.Default.Update), "安装时间", value = appInstallTime(context))
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = AppC.divider)
                    AboutIconRow(painterResource(R.drawable.ic_github), "GitHub", chevron = true, onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/xiaoliang6959/UniversitySchedule")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            android.widget.Toast.makeText(context, "未找到可打开链接的应用", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Power by xiaoliang6959", fontSize = 12.sp, color = AppC.textDisabled)
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** 关于页图标行：左图标+标题，右侧可放灰色值或 ›；onClick 非空则整行可点 */
@Composable
fun AboutIconRow(icon: androidx.compose.ui.graphics.painter.Painter, title: String, value: String = "", chevron: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        (if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth())
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AppC.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 15.sp, color = AppC.textPrimary, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, fontSize = 13.sp, color = AppC.textMuted)
        if (chevron) {
            if (value.isNotEmpty()) Spacer(Modifier.width(6.dp))
            Icon(Icons.Default.ChevronRight, null, tint = AppC.iconIdle, modifier = Modifier.size(18.dp))
        }
    }
}
