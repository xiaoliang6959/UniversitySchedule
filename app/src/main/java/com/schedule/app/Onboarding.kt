package com.schedule.app

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次安装的新手引导。
 *
 * 七个页面：欢迎 → 是否导入 → 学期设置 → 时间表 → 课程 → 上课提醒 → 完成。
 * 除首页和末页外，左上角都能返回上一页；时间表、课程两步可以直接跳过。
 */

// 页面序号
private const val PAGE_WELCOME = 0
private const val PAGE_IMPORT = 1
private const val PAGE_INFO = 2
private const val PAGE_SLOTS = 3
private const val PAGE_COURSES = 4
private const val PAGE_REMIND = 5
private const val PAGE_DONE = 6
private const val PAGE_COUNT = 7

/** 引导是否走完（走完后主页设置里可以重看） */
const val KEY_ONBOARDING_DONE = "onboarding_done"

fun onboardingNeeded(prefs: SharedPreferences): Boolean = !prefs.getBoolean(KEY_ONBOARDING_DONE, false)

fun completeOnboarding(prefs: SharedPreferences) {
    prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
}

/** 用户没填本学期周数时的兜底值 */
private const val DEFAULT_TOTAL_WEEKS = 19

/**
 * 用户没填开始日期时的兜底：本周一。
 * 比写死某个日期合理——学期起点没填，最可能就是"这周就在上课"。
 */
private fun defaultStartDate(): String =
    java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString()

/**
 * 小窗/分屏标记：由 MainActivity 统一检测并 provide，
 * 引导页顶栏避让"⋯"把手、主页顶栏避让都读它。
 */
internal val LocalSmallWindow = staticCompositionLocalOf { false }

@Composable
fun OnboardingApp(
    onFinish: () -> Unit,
    onImport: () -> Unit,
    importTick: Int,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    var page by remember { mutableIntStateOf(PAGE_WELCOME) }
    /** 切换方向：决定翻页动画往左还是往右滑 */
    var forward by remember { mutableStateOf(true) }
    // 「跳过」= 这一步加的东西一概不留。可复用的编辑页是**直接落盘**的，
    // 所以进页面之前先拍一份快照，跳过时把快照原样写回去（null = 当时还没这个键 → 删掉）。
    var slotsSnapshot by remember { mutableStateOf<String?>(null) }
    var coursesSnapshot by remember { mutableStateOf<String?>(null) }
    // 快照是否已经拍过。**只拍第一次**，这是"跳过要清除"能成立的关键（原因见 goTo）。
    var slotsSnapTaken by remember { mutableStateOf(false) }
    var coursesSnapTaken by remember { mutableStateOf(false) }
    /** 正在确认跳过哪一步；-1 = 没有弹窗 */
    var skipStep by remember { mutableIntStateOf(-1) }
    /** 本次引导里是否已导入过课表（导入后又被改成「从零开始」会变回 false） */
    var importActive by remember { mutableStateOf(false) }

    fun goTo(target: Int) {
        forward = target >= page
        // 快照**只在第一次进入这一步时**拍：这一步的"原样"应该是用户还没碰它之前的样子。
        // 每次进入都重拍会踩这样一个坑：用户加完东西、退回去、再进来（此时重拍 = 带着他刚加的内容），
        // 然后点"跳过" → 快照被原样写回 → 看着就是"根本没清除"。
        if (target == PAGE_SLOTS && !slotsSnapTaken) {
            slotsSnapshot = prefs.getString("slots_json", null); slotsSnapTaken = true
        }
        if (target == PAGE_COURSES && !coursesSnapTaken) {
            coursesSnapshot = prefs.getString("courses_json", null); coursesSnapTaken = true
        }
        page = target
    }
    fun goBack() { forward = false; page = (page - 1).coerceAtLeast(PAGE_WELCOME) }

    // 时间表 / 课程：直接复用主 App 的编辑页，所以数据要能重新从 prefs 读出来
    var slotsVersion by remember { mutableIntStateOf(0) }
    val slots = remember(slotsVersion, importTick) { loadSlots(prefs) }
    var courses by remember { mutableStateOf(loadCoursesFromPrefs(prefs)) }
    var courseDetail by remember { mutableStateOf<String?>(null) }
    val colorMap = remember { mutableMapOf<String, Pair<Color, Color>>() }

    /** 把某个键还原成进入这一步之前的样子 */
    fun restore(key: String, snap: String?) {
        val e = prefs.edit()
        if (snap == null) e.remove(key) else e.putString(key, snap)
        e.apply()
    }

    /** 确认跳过某一步：先还原数据，再往下走 */
    fun doSkip(target: Int) {
        when (target) {
            PAGE_SLOTS -> { restore("slots_json", slotsSnapshot); slotsVersion++ }
            PAGE_COURSES -> { restore("courses_json", coursesSnapshot); courses = loadCoursesFromPrefs(prefs) }
        }
        skipStep = -1
        // 注意跳的是"下一步该去的那一页"，不是 target 本身 ——
        // target 是**当前**这一步（弹窗就是它弹出来的），原地重进本页会让人以为按钮没反应。
        goTo(if (target == PAGE_SLOTS) PAGE_COURSES else PAGE_REMIND)
    }

    /**
     * 舍弃"已导入"的课表：把导入写进去的那几项清干净，回到什么都没导入的状态。
     * 用户在导入之后又选「从零开始」时调用 —— 两条路的数据混在一起会说不清下一步走哪条。
     */
    fun discardImported() {
        prefs.edit()
            .remove("courses_json").remove("slots_json").remove("holidays_json")
            .remove("totalWeeks").remove("startDate").remove("school").remove("major")
            .apply()
        importActive = false
        courses = emptyList()
        slotsVersion++
        // 导入的东西都丢了，这两份快照也不再代表"这一步之前的样子"，一起作废
        slotsSnapshot = null; coursesSnapshot = null
        slotsSnapTaken = false; coursesSnapTaken = false
    }

    // 引导中导入成功：课表数据已经齐了，直接跳到提醒那一步
    LaunchedEffect(importTick) {
        if (importTick > 0) {
            importActive = true
            courses = loadCoursesFromPrefs(prefs)
            slotsVersion++
            courseDetail = null
            goTo(PAGE_REMIND)
        }
    }

    BackHandler(enabled = page > PAGE_WELCOME || courseDetail != null) {
        if (courseDetail != null) courseDetail = null
        else goBack()
    }

    Box(Modifier.fillMaxSize().background(AppC.bg)) {
        // 页面衔接：安卓桌面那种"层叠翻页"——新页从右侧推进、旧页向左退场并淡出，
        // 方向跟着前进/后退走。位移只占屏宽 28%，配合淡入淡出，
        // 不会像整屏推走那样让内容瞬间消失，观感更接近桌面翻页。
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val shift = 0.28
                if (forward) {
                    (slideInHorizontally(tween(300)) { (it * shift).toInt() } +
                        fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(300)) { -(it * shift).toInt() } +
                            fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(300)) { -(it * shift).toInt() } +
                        fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(300)) { (it * shift).toInt() } +
                            fadeOut(tween(160)))
                }
            },
            label = "obPage",
        ) { target ->
        when (target) {
            PAGE_WELCOME -> WelcomePage(onNext = { goTo(PAGE_IMPORT) })

            PAGE_IMPORT -> ImportPage(
                imported = importActive,
                onImport = onImport,
                onDiscardImported = { discardImported() },
                onNext = { goTo(PAGE_INFO) },
                onBack = { goBack() },
            )

            PAGE_INFO -> InfoPage(
                prefs = prefs,
                importTick = importTick,
                onNext = { goTo(PAGE_SLOTS) },
                onBack = { goBack() },
            )

            PAGE_SLOTS -> ObStepPage(
                title = "时间表",
                page = target,
                onBack = { goBack() },
                primaryText = "下一步",
                // 没有节次就进行不下去：课表没有行，后面的"添加课程"也没意义。
                // 这时「跳过」是页面上唯一的出路，所以它必须保持可用。
                primaryEnabled = slots.isNotEmpty(),
                onPrimary = { goTo(PAGE_COURSES) },
                secondaryText = "跳过",
                onSecondary = { skipStep = PAGE_SLOTS },
                embedded = true,
            ) {
                // 复用主 App 的时间表设置页：自带返回箭头、增删改、空表模板
                TimeTableSettingsPage(
                    prefs = prefs,
                    onBack = { goBack() },
                    onRefresh = { slotsVersion++ },
                    slots = slots,
                )
            }

            PAGE_COURSES -> {
                if (courseDetail != null) {
                    CourseDetailPage(
                        courses = courses,
                        courseName = courseDetail ?: "",
                        prefs = prefs,
                        onBack = { courseDetail = null },
                        onSlotAdded = { courses = loadCoursesFromPrefs(prefs) },
                        colorMap = colorMap,
                        slots = slots,
                    )
                } else {
                    ObStepPage(
                        title = "添加课程",
                        page = target,
                        onBack = { goBack() },
                        primaryText = "下一步",
                        // 一门课都没加时"下一步"和"跳过"就是同一件事，留一个可点即可
                        primaryEnabled = courses.isNotEmpty(),
                        onPrimary = { goTo(PAGE_REMIND) },
                        secondaryText = "跳过",
                        onSecondary = { skipStep = PAGE_COURSES },
                        embedded = true,
                    ) {
                        ManagePage(
                            courses = courses,
                            prefs = prefs,
                            onBack = { goBack() },
                            onCourseClick = { courseDetail = it },
                            onAddCourse = { courses = loadCoursesFromPrefs(prefs) },
                            colorMap = colorMap,
                            refreshKey = slotsVersion,
                        )
                    }
                }
            }

            PAGE_REMIND -> ObStepPage(
                title = "上课提醒",
                page = PAGE_REMIND,
                onBack = { goBack() },
                primaryText = "下一步",
                onPrimary = {
                    ReminderNotifier.schedule(context)
                    goTo(PAGE_DONE)
                },
                // 这里原本还有个「暂不设置」：默认就是"不启用通知"，什么都不点直接下一步
                // 效果完全一样，等于三个选项干两件事。删掉，按钮铺满整行。
                embedded = true,
            ) {
                // 直接复用主 App 的完整提醒页（自带顶栏，所以 embedded=true）：
                // 三选一（不启用/普通/焦点）、提醒时间、下课提醒、权限与后台保活全都在，
                // 引导里能设的和之后在「上课提醒」里能改的完全一致。
                // 注意：这里**不再**一进页面就自动弹通知授权框 ——
                // 授权改成"后台保活与闹钟权限"卡里的第一行「系统通知」，用户点了才弹。
                ReminderPage(
                    prefs = prefs,
                    // 引导阶段还没有"测试课表"可言，数据文件就是真实文件
                    dataPrefs = prefs,
                    courses = courses,
                    config = loadConfigFromPrefs(prefs),
                    slots = slots,
                    onBack = { goBack() },
                    rearm = { ReminderNotifier.schedule(context) },
                    version = importTick,
                    // 引导专用：一选中普通/焦点通知，就把「后台保活与闹钟权限」摊成第二张卡
                    // （四项平铺，不折叠不弹窗）。主 App 里仍是入口行 + 弹窗。
                    inlineKeepAlive = true,
                )
            }

            PAGE_DONE -> DonePage(
                courses = courses.size,
                slots = slots.size,
                reminderOn = loadReminderConfig(prefs).enabled,
                onFinish = onFinish,
                onBack = { goBack() },
            )
        }
        }
    }

    // 「跳过」的确认：加过东西就明确说"不会保存"（并可反悔），什么都没加就只提示以后去哪补。
    if (skipStep >= 0) {
        val isSlotsStep = skipStep == PAGE_SLOTS
        // 判"有没有内容"直接读 prefs，而不是读那份可能滞后的内存状态：
        // 弹窗该显示哪一版、跳过时会不会清掉东西，都取决于 prefs 里真实存着什么。
        val added = if (isSlotsStep) loadSlots(prefs).isNotEmpty() else loadCoursesFromPrefs(prefs).isNotEmpty()
        val msg = when {
            added -> "这一步里添加的内容不会被保存，之后得重新再加一遍。确定跳过吗？"
            // 提示必须写**当前这一步**要去哪儿补。这里以前为"上一页跳过、这页没时间表"加过
            // 一条指向「时间表设置」的分支 —— 结果第 5 页弹出来的话是在讲第 4 页的事，读着莫名其妙。
            isSlotsStep -> "这一步先不设置，之后随时能在主菜单的「时间表设置」里补上。"
            else -> "这一步先不设置，之后随时能在主菜单的「课程管理」里补上。"
        }
        AlertDialog(
            onDismissRequest = { skipStep = -1 },
            containerColor = AppC.card,
            shape = RoundedCornerShape(16.dp),
            title = { Text(if (added) "跳过就不保存了" else "先不填了") },
            text = { Text(msg, fontSize = 14.sp, color = AppC.textBody, lineHeight = 20.sp) },
            // 两种情况共用同一套左右语义：右边（主位）= 留在这一步继续弄，主题色高亮；
            // 左边（次位）= 仍然跳过，灰色、不推荐。用户不用每次重新判断哪个在劝他留下。
            confirmButton = {
                TextButton(onClick = { skipStep = -1 }) {
                    Text(if (added) "继续编辑" else "取消", color = AppC.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { doSkip(skipStep) }) {
                    Text(if (added) "清除并跳过" else "知道了", color = AppC.textMuted)
                }
            },
        )
    }
}

// ====== 通用外壳：顶部标题栏 + 底部（进度点 + 按钮） ======

@Composable
private fun ObStepPage(
    title: String,
    page: Int,
    onBack: () -> Unit,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    embedded: Boolean = false,
    primaryEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // embedded：内容区自带顶栏（复用的主 App 页面），这里就不再放一个，避免双顶栏
        if (!embedded) {
            ObTopBar(title = title, onBack = onBack)
        }
        Box(Modifier.weight(1f)) { content() }
        ObBottomBar(
            page = page,
            primaryText = primaryText,
            onPrimary = onPrimary,
            secondaryText = secondaryText,
            onSecondary = onSecondary,
            primaryEnabled = primaryEnabled,
        )
    }
}

@Composable
private fun ObTopBar(title: String, onBack: (() -> Unit)?) {
    // 小窗/分屏：系统在窗口顶部中央画"⋯"把手，且状态栏 inset 常为 0 → 顶栏额外下移避让
    val smallWindow = LocalSmallWindow.current
    Surface(color = AppC.headerBlue, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(
                    start = 4.dp, end = 16.dp,
                    top = if (smallWindow) 28.dp else 8.dp, bottom = 8.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回上一页", tint = AppC.headerText)
                    }
                } else {
                    Spacer(Modifier.width(16.dp))
                }
                Text(title, color = AppC.headerText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ObBottomBar(
    page: Int,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
) {
    // 小窗里底栏下方会露出抬升留白，12dp 阴影投在白底上形成明显"分层线"→ 小窗去阴影。
    Surface(
        color = AppC.card,
        shadowElevation = if (LocalSmallWindow.current) 0.dp else 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp, vertical = 10.dp)) {
            ObStepDots(page)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (secondaryText != null && onSecondary != null) {
                    // 「跳过」和「下一步」等宽、同圆角：主按钮置灰时跳过常常是页面上唯一的出路，
                    // 不能把它做成又小又像装饰的次要控件。表达式只靠主题色描边就够（不填色）。
                    OutlinedButton(
                        onClick = onSecondary,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.5.dp, AppC.accent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppC.accent),
                    ) { Text(secondaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                }
                Button(
                    onClick = onPrimary,
                    enabled = primaryEnabled,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppC.accentFill,
                        contentColor = readableOn(AppC.accentFill),
                        // 置灰用中性灰，不要"半透明主题色"——那会被当成还能点
                        disabledContainerColor = AppC.switchOff,
                        disabledContentColor = AppC.textDisabled,
                    ),
                ) { Text(primaryText, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ObStepDots(page: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until PAGE_COUNT) {
            val active = i == page
            Box(
                Modifier.padding(horizontal = 3.dp)
                    .size(if (active) 18.dp else 7.dp)
                    .clip(CircleShape)
                    .background(if (active) AppC.accent else AppC.stepDotIdle)
            )
        }
    }
}

// ====== 第 1 页：欢迎 ======

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    val smallWindow = LocalSmallWindow.current
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to AppC.gradientTop, 0.45f to AppC.bg))
            .windowInsetsPadding(WindowInsets.statusBars)
            // 分屏下栏时小白条走 navigationBars insets 上报（后面页面靠它才正常），欢迎页也得吃上；
            // 小窗时该 insets 为 0，由 MainActivity 根节点的固定底部留白兜底
            .windowInsetsPadding(WindowInsets.navigationBars)
            // 小窗/分屏：内容可能高过窗口 → 可滚动；顶部让出"⋯"把手
            .then(if (smallWindow) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 28.dp)
            .padding(top = if (smallWindow) 28.dp else 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Image(
            painter = painterResource(id = R.drawable.ic_onboarding_logo),
            contentDescription = "大学课程表",
            modifier = Modifier.size(92.dp).clip(RoundedCornerShape(20.dp)),
        )
        Spacer(Modifier.height(24.dp))
        Text("大学课程表", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppC.titleDark)
        Spacer(Modifier.height(8.dp))
        Text(
            "把一学期的课，安排得明明白白",
            fontSize = 15.sp, color = AppC.textSlate2, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        ObFeatureRow(Icons.Default.AccessTime, "自定义时间表", "节次名称和起止时间都由你定")
        ObFeatureRow(Icons.Default.Edit, "课程管理", "多时间段、冲突检测、颜色标记")
        ObFeatureRow(Icons.Default.NotificationsActive, "上课提醒", "到点前在通知栏提醒你")
        ObFeatureRow(Icons.Default.Download, "导入导出", "支持 Markdown 课表文件")
        // 全屏：weight 把按钮顶到底部；小窗：改用固定间距，随内容一起滚动
        if (smallWindow) Spacer(Modifier.height(24.dp)) else Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            // 圆角统一 12dp：和底栏那两颗按钮一致，别一页一个形状
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppC.accentFill, contentColor = readableOn(AppC.accentFill)),
        ) { Text("下一步", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
        Text("几分钟就能配好，之后随时能改", fontSize = 12.sp, color = AppC.textHint)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ObFeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    // 背景必须不透明：半透明白会让 shadowElevation 的阴影从卡片内部透出来，
    // 看起来就是"中间亮、四周暗"一圈脏色。改用描边代替阴影勾轮廓。
    Surface(
        color = AppC.card, shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AppC.cardBorder),
        shadowElevation = 0.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = AppC.iconCircleBg, shape = CircleShape) {
                Icon(icon, null, tint = AppC.accent, modifier = Modifier.padding(9.dp).size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppC.textPrimary)
                Text(desc, fontSize = 12.sp, color = AppC.iconMuted)
            }
        }
    }
}

// ====== 第 2 页：是否导入已有课表 ======

@Composable
private fun ImportPage(
    imported: Boolean,
    onImport: () -> Unit,
    onDiscardImported: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    // 两张卡是**互斥的单选**：导入成功 → 只有"导入"那张打勾；点"从零开始" → 只有手动那张打勾。
    // 之前两张能同时打勾（导入过 + 选了手动），"下一步"到底走哪条路就说不清了。
    var manual by remember { mutableStateOf(false) }
    // 导入成功（本页操作或从后面几页返回本页都算）→ 取消手动那张的勾
    LaunchedEffect(imported) { if (imported) manual = false }

    ObStepPage(
        title = "导入课表",
        page = PAGE_IMPORT,
        onBack = onBack,
        primaryText = "下一步",
        // 一张卡都没选就灰着：先让用户表态，才知道下一步是走导入还是手动
        primaryEnabled = imported || manual,
        onPrimary = onNext,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text("已经有课表了？", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppC.titleDark)
            Spacer(Modifier.height(8.dp))
            Text(
                "如果学校或同学给过你课程表文件（Markdown），可以直接导入，学期设置、时间表和课程会一次填好；" +
                    "没有也没关系，从零开始手动填也一样。",
                fontSize = 14.sp, color = AppC.textSlate, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(10.dp))
            // 明确写出"先选一个"：下面「下一步」在没选之前是灰的，不交代一句会让人以为卡住了
            Text(
                "下面两种方式选一个（选中后「下一步」才会亮起）：",
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppC.titleDark, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(20.dp))

            ObChoiceTile(
                icon = Icons.Default.FolderOpen,
                title = "导入已有课表文件",
                desc = if (imported) "已导入，正在继续后面的设置" else "选择 .md 文件，自动覆盖当前数据",
                highlight = !imported && !manual,
                selected = imported,
                onClick = onImport,
            )
            Spacer(Modifier.height(12.dp))
            ObChoiceTile(
                icon = Icons.Default.AddCircleOutline,
                title = "从零开始手动添加",
                desc = if (imported) "选了就会丢掉上面导入的课表" else "接下来一步步填学期设置、时间表和课程",
                highlight = manual,
                selected = manual,
                onClick = {
                    // 已经导入过又要改手动：把导入进来的内容丢掉，保证两条路只留一条
                    if (imported) onDiscardImported()
                    manual = true
                },
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "提示：导入会覆盖 App 里已有的数据。引导结束后，在「设置 → 导入数据 / 导出数据」里也能随时操作。",
                fontSize = 12.sp, color = AppC.textHint, lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun ObChoiceTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    highlight: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = AppC.card, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
            .border(
                if (selected) 2.dp else if (highlight) 1.5.dp else 1.dp,
                if (selected || highlight) AppC.accent else AppC.stepBorder,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, null,
                tint = if (highlight) AppC.accent else AppC.iconMuted,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppC.titleDark)
                Text(desc, fontSize = 12.sp, color = if (selected) AppC.success else AppC.iconMuted)
            }
            if (selected) Icon(Icons.Default.CheckCircle, "已完成", tint = AppC.success, modifier = Modifier.size(22.dp))
        }
    }
}

// ====== 第 3 页：学期设置 ======

@Composable
private fun InfoPage(
    prefs: SharedPreferences,
    importTick: Int,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    // 全新安装：四个框一律留空由用户自己填，只放灰色示例提示；
    // 已经存过（重看引导 / 导入过）才回填已有值。
    val hasSaved = prefs.contains("totalWeeks") || prefs.contains("startDate") ||
        prefs.contains("school") || prefs.contains("major")
    val initial = remember(importTick) {
        if (hasSaved) loadConfigFromPrefs(prefs) else ScheduleConfig()
    }
    var tw by remember(importTick) { mutableStateOf(if (hasSaved) initial.totalWeeks.toString() else "") }
    var sd by remember(importTick) { mutableStateOf(if (hasSaved) initial.startDate else "") }
    var sch by remember(importTick) { mutableStateOf(if (hasSaved) initial.school else "") }
    var maj by remember(importTick) { mutableStateOf(if (hasSaved) initial.major else "") }

    ObStepPage(
        title = "学期设置",
        page = PAGE_INFO,
        onBack = onBack,
        primaryText = "下一步",
        // 四个框全填好才允许往下走：周数和开始日期决定课表按哪一周排版，
        // 学校/专业是标题栏的字，缺任何一项都不是一份完整的学期设置。
        primaryEnabled = tw.isNotBlank() && sd.isNotBlank() && sch.isNotBlank() && maj.isNotBlank(),
        onPrimary = {
            // 没填的字段退回默认值，保证课表能按周次正常显示
            saveConfigToPrefs(
                prefs,
                ScheduleConfig(
                    totalWeeks = tw.toIntOrNull()?.coerceIn(1, TOTAL_WEEKS_MAX) ?: DEFAULT_TOTAL_WEEKS,
                    startDate = sd.trim().ifBlank { defaultStartDate() },
                    school = sch.trim(),
                    major = maj.trim(),
                )
            )
            onNext()
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        ) {
            Text("这学期怎么安排", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AppC.titleDark)
            Spacer(Modifier.height(8.dp))
            Text(
                "本学期周数和开始日期决定了课程表按哪一周显示，学校、专业只是标题栏上的字。",
                fontSize = 14.sp, color = AppC.textSlate, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(20.dp))
            Surface(color = AppC.card, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormField("本学期周数", tw, "如 19") { tw = it }
                    DateField("学期开始日期", sd, "点击选择日期") { sd = it }
                    FormField("学校", sch, "显示在标题栏") { sch = it }
                    FormField("专业", maj, "显示在标题栏") { maj = it }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "这些以后在抽屉菜单的「学期设置」里也能改。",
                fontSize = 12.sp, color = AppC.textHint,
            )
            // 灰着的按钮得有个交代，否则用户不知道卡在哪
            if (tw.isBlank() || sd.isBlank() || sch.isBlank() || maj.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "四项都填好，「下一步」才会亮起。",
                    fontSize = 12.sp, color = AppC.orange,
                )
            }
        }
    }
}

// ====== 第 7 页：完成 ======

@Composable
private fun DonePage(
    courses: Int,
    slots: Int,
    reminderOn: Boolean,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    ObStepPage(
        title = "设置完成",
        page = PAGE_DONE,
        onBack = onBack,
        primaryText = "开始使用",
        onPrimary = onFinish,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .background(Brush.verticalGradient(0f to AppC.gradientTop, 0.5f to AppC.bg))
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Surface(color = AppC.card, shape = CircleShape, shadowElevation = 4.dp) {
                Text("🎉", fontSize = 44.sp, modifier = Modifier.padding(22.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("恭喜，都设置好了！", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AppC.titleDark)
            Spacer(Modifier.height(8.dp))
            Text(
                "打开 App 就是这学期的课表，左上角菜单里能改所有东西。",
                fontSize = 14.sp, color = AppC.textSlate, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Surface(color = AppC.card, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    ObSummaryRow("时间表", if (slots == 0) "还没有节次，记得去添加" else "$slots 个节次")
                    HorizontalDivider(color = AppC.divider)
                    ObSummaryRow("课程", if (courses == 0) "还没有课程，记得去添加" else "$courses 条课程安排")
                    HorizontalDivider(color = AppC.divider)
                    ObSummaryRow("上课提醒", if (reminderOn) "已开启" else "未开启，可在设置里打开")
                }
            }
            Spacer(Modifier.height(20.dp))
            ObTipCard("主页向左划或点左上角菜单，能进学期设置、时间表、课程管理和上课提醒。")
            Spacer(Modifier.height(12.dp))
            ObTipCard("手机管家类的软件容易把后台清掉，提醒不响时先去「上课提醒」页看看闹钟登记状态。")
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ObSummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = AppC.iconMuted, modifier = Modifier.width(80.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppC.titleDark, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ObTipCard(text: String) {
    Surface(color = AppC.tipBg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Lightbulb, null, tint = AppC.amber, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 12.sp, color = AppC.tipText, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        }
    }
}
