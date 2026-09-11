package com.schedule.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {

    var reloadVersion = mutableIntStateOf(0)

    /** 每次导入成功 +1，引导页靠它把数据刷新并跳到提醒那一步 */
    var importVersion = mutableIntStateOf(0)

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.let { text ->
                val result = MarkdownCodec.importMarkdown(text)
                if (result.courses.isEmpty() && result.slots.isEmpty()) {
                    Toast.makeText(this, "⚠️ 文件内无有效数据，无法导入", Toast.LENGTH_LONG).show()
                    return@let
                }
                val prefs = dataPrefs()
                saveConfig(prefs, result.config)
                if (result.courses.isNotEmpty()) {
                    saveCourses(prefs, result.courses)
                }
                if (result.slots.isNotEmpty()) {
                    saveSlots(prefs, result.slots)
                }
                // 节假日：文件里有"## 节假日"段才覆盖；没有该段（老文件）保留手机上的现状
                if (result.holidays.isNotEmpty()) {
                    saveHolidays(prefs, result.holidays)
                }
                Toast.makeText(this, "✅ 导入成功：课程${result.courses.size}条，时间表${result.slots.size}条，节假日${result.holidays.size}个（已覆盖原有数据）", Toast.LENGTH_SHORT).show()
                if (result.errors.isNotEmpty()) {
                    // 以前只弹第一条：被丢掉的行"为什么丢"用户根本看不到（本次中秋节丢失就是这么被藏住的）。
                    // 现在把最多 4 条原因直接摆出来。
                    val head = result.errors.take(4).joinToString("\n· ")
                    val more = if (result.errors.size > 4) "\n…共 ${result.errors.size} 条" else ""
                    Toast.makeText(this, "⚠️ 有 ${result.errors.size} 行没解析成功：\n· $head$more", Toast.LENGTH_LONG).show()
                }
                reloadVersion.intValue++
                importVersion.intValue++
            }
        }
    }

    private val fileSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let {
            contentResolver.openOutputStream(it)?.bufferedWriter()?.use { w ->
                val prefs = dataPrefs()
                val courses = loadCourses(prefs)
                val config = loadConfig(prefs)
                val slots = loadSlots(prefs)
                val holidays = loadHolidays(prefs)
                w.write(MarkdownCodec.exportCourses(courses, config, slots, holidays))
            }
            Toast.makeText(this, "✅ 导出成功", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPrefs() = getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)

    /**
     * 课表数据（学期设置/时间表/课程/节假日）所在的文件。
     * 开发者模式打开"测试课表"时会指向另一个文件，真实数据因此绝不会被覆盖。
     */
    private fun dataPrefs() = TestSchedule.dataStore(this)

    // ---- 闹钟排期一律丢到后台单线程 ----
    // schedule() 里是一串 binder 调用（建通知渠道、cancel 全部闹钟、按每个提前量/每节课
    // setAlarmClock、写 prefs），几百毫秒量级；以前是在 onCreate/onResume 的主线程上同步跑的，
    // 冷启动就白等这一截。现在合并成一个后台任务：短时间内的连续请求（冷启动的 onCreate +
    // onResume）只会真正跑一次，主线程立刻把首帧画出来。
    // 单线程保证不会有两份排期同时"先 cancel 再注册"互相踩。
    private val scheduleExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val scheduleQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun scheduleRemindersAsync() {
        if (!scheduleQueued.compareAndSet(false, true)) return
        scheduleExec.execute {
            scheduleQueued.set(false)
            runCatching { ReminderNotifier.schedule(this) }
        }
    }

    /** Android 13+ 首次启动申请通知权限 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "未开启通知权限，上课提醒将无法弹出", Toast.LENGTH_LONG).show()
            }
            scheduleRemindersAsync()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 自愈要放在最前面：以前可能留下"开发者模式已关、测试课表却还开着"的孤儿状态
        // （那时页内子设置还各自为政），入口没了、用户看不见也关不掉。
        // 必须在下面任何一次排期/读数据之前清掉，否则 schedule() 会照测试文件去排闹钟。
        DeveloperSettings.healInconsistentState(this)
        AppC.devModeOn = DeveloperSettings.isEnabled(this)
        val prefs = getPrefs()
        // 首次安装：给真实数据写入默认时间表。判断和写入都固定用真实文件 ——
        // 开着"测试课表"时 dataPrefs() 指向测试文件，拿它判断会把真实数据误判成"已初始化"。
        if (!prefs.contains("slots_json")) {
            saveSlots(prefs, defaultSlots())
        }
        // 通知渠道不在这里建了：schedule() 第一步就会 ensureChannels，让它跟排期一起在后台跑。
        // 权限框交给引导页第 6 步，避免和引导抢弹窗。
        val firstRun = onboardingNeeded(prefs)
        if (!firstRun) {
            requestNotificationPermissionIfNeeded()
        } else {
            scheduleRemindersAsync()
        }
        AppC.mode = prefs.getInt("dark_mode", 0)
        // 主题色（外观设置）：没改过就用出厂蓝
        AppC.themeColor = loadThemeColor(prefs)
        // 课表背景图：开关状态 + 遮盖透明度（图本身在私有目录，关了再开还是那张）
        AppC.bgImageOn = BackgroundStore.isEnabled(this)
        AppC.bgScrim = BackgroundStore.scrimPct(this) / 100f
        AppC.bgBlockAlpha = BackgroundStore.blockAlphaPct(this) / 100f
        setContent {
            var onboardDone by remember { mutableStateOf(!firstRun) }
            var winSize by remember { mutableStateOf(IntSize.Zero) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            val realMetrics = remember {
                android.util.DisplayMetrics().also {
                    @Suppress("DEPRECATION")
                    (getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
                        ?.defaultDisplay?.getRealMetrics(it)
                }
            }
            // HyperOS 悬浮小窗=freeform 且系统整体缩放绘制：App 逻辑尺寸仍是整屏宽、
            // isInMultiWindowMode 为 false、Compose 尺寸信号不可靠（实测 dumpsys 证实）。
            // 唯一稳定信号=窗口在屏幕上的实际位置：悬浮小窗竖直悬在屏幕中间（顶不贴顶、
            // 底不贴底）；上下分屏顶或底必贴边、左右分屏顶=0、全屏(0,0)，都不会误判。
            // 小窗缩放/移动会触发 configuration 变化（screenHeightDp 834→601 实测），据此重读。
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            var winLocX by remember { mutableStateOf(0) }
            var winLocY by remember { mutableStateOf(0) }
            var decorSize by remember { mutableStateOf(IntSize.Zero) }
            val win = this@MainActivity.window
            LaunchedEffect(configuration, winSize) {
                val loc = IntArray(2)
                win.decorView.getLocationOnScreen(loc)
                winLocX = loc[0]
                winLocY = loc[1]
                decorSize = IntSize(win.decorView.width, win.decorView.height)
                android.util.Log.d(
                    "WinProbe",
                    "loc=${loc[0]},${loc[1]} decor=${win.decorView.width}x${win.decorView.height} " +
                        "comp=$winSize cfg=${configuration.screenWidthDp}x${configuration.screenHeightDp}"
                )
            }
            val statusInsetPx = WindowInsets.statusBars.getTop(density)
            val realH = realMetrics.heightPixels
            val floating = decorSize.height > 0 &&
                (winLocY > 40 && winLocY + decorSize.height < realH - 40 ||
                    winLocX > 40 && winLocY > 40)
            val smallWindow = isInMultiWindowMode || floating ||
                (decorSize.height > 0 && decorSize.height < realH * 0.9f) ||
                (winSize.height > 0 && winSize.height > winSize.width &&
                    winSize.height < realH * 0.85f) ||
                (winSize.height > 0 && winSize.height > winSize.width && statusInsetPx == 0)
            // 三形态判定（背景图位置按形态分别保存，所以要能分清小窗和分屏）：
            //   悬浮小窗 → SMALL：**只能**靠"窗口没贴边"来判 —— 澎湃小窗自报仍是非多窗口、
            //     逻辑宽仍是整屏，比例类信号全失灵（历史踩过）；
            //   分屏 → SPLIT：系统会老实置 isInMultiWindowMode，或窗口高度只剩半屏左右；
            //   其余 → FULL。
            // 注意顺序：先判 floating，因为小窗有时也会置 isInMultiWindowMode。
            val windowMode = when {
                floating -> WindowMode.SMALL
                smallWindow -> WindowMode.SPLIT
                else -> WindowMode.FULL
            }
            // 只有悬浮小窗里系统把小白条画进窗口内部 → 固定抬高（实测条高约15dp，20dp 足够且不留大空档）；
            // 分屏/全屏不抬，无幽灵空隙。
            val bottomReserve = if (floating) 20.dp else 0.dp
            // 系统栏图标配色：浅色模式→深色图标(浅底)，深色模式→浅色图标(深底)。
            // 悬浮小窗把条画进窗口内部，同样按当前主题底色决定图标明暗。
            val darkNow = AppC.isDark
            LaunchedEffect(floating, darkNow) {
                val ctrl = androidx.core.view.WindowInsetsControllerCompat(win, win.decorView)
                ctrl.isAppearanceLightStatusBars = !darkNow
                ctrl.isAppearanceLightNavigationBars = !darkNow
            }
            ScheduleTheme {
            CompositionLocalProvider(
                LocalSmallWindow provides smallWindow,
                LocalWindowMode provides windowMode,
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .background(AppC.bg)
                        .onSizeChanged { winSize = it }
                        .padding(bottom = bottomReserve)
                ) {
                    // 引导结束 → 主页：淡入交接，避免最后一页"啪"地换成课表
                    AnimatedContent(
                        targetState = onboardDone,
                        transitionSpec = {
                            fadeIn(tween(260)) togetherWith fadeOut(tween(160))
                        },
                        label = "onboardHandoff",
                    ) { done ->
                        if (!done) {
                            OnboardingApp(
                                onFinish = {
                                    completeOnboarding(prefs)
                                    onboardDone = true
                                    // 引导里改过的学期设置 / 时间表 / 课程重新读一遍
                                    reloadVersion.intValue++
                                    // 这里不再顺手弹通知授权框：引导第 6 步那句「下一步」刚点完弹出系统框，
                                    // 等于把用户明确要求去掉的自动弹窗挪后一步。授权入口在提醒页的
                                    // 「后台保活与闹钟权限」卡里（第一行「系统通知」），点了才弹。
                                    scheduleRemindersAsync()
                                },
                                onImport = { filePicker.launch(arrayOf("text/markdown", "text/*")) },
                                importTick = importVersion.intValue,
                            )
                        } else {
                            ScheduleApp(
                                onExport = { fileSaver.launch("课程表.md") },
                                onImport = { filePicker.launch(arrayOf("text/markdown", "text/*")) },
                                reloadVersion = reloadVersion
                            )
                        }
                    }
                }
            }
            }
        }
    }

    /** Android 13+ 未授权时拉起系统授权框 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !ReminderNotifier.notificationsGranted(this)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scheduleRemindersAsync()
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚在系统设置里改过权限或时间，重新排期一次（后台跑，别卡住返回动画）
        scheduleRemindersAsync()
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
