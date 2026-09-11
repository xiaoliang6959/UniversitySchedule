package com.schedule.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 常驻焦点通知：从规定提前量那一刻起挂到连堂段下课，每分钟刷新剩余时间。
 * 判定逻辑在 FocusLogic.kt，这里只负责通知发送、心跳闹钟和"已关闭"记录。
 *
 * - ongoing：右滑划不掉，"清除全部"也带不走，只有点通知上的「关闭」才会消失
 * - 独立渠道（等级 HIGH 但通知级 setSilent，实际不响铃、不弹横幅；
 *   等级给高是为了能上锁屏）：到点提醒仍由 class_start / class_advance / class_end 三渠道负责
 * - 锁屏可见；灭屏也照常刷新 —— 这条通知在锁屏上是看得见的，
 *   冻住不动会被当成 bug。右上角的秒级倒计时由系统走（chronometer），
 *   标题里的"还有 X 分钟"则靠每分钟的心跳刷新，两者互补
 * - 标题只放"还有 X 分钟上课"，课名在正文第一行：课名再长也挤不掉关键信息
 * - 息屏显示（AOD）已放弃：本该靠 miui.focus.param 让那行字上息屏，但实测澎湃会把整条通知吞掉
 *   （见 [applyMiuiFocusParam] 的注释），所以现在走"亮屏锁屏"这条路
 * - 正文不设置点击跳转，跳 App 交给「查看」按钮，避免误触
 */
object FocusNotifier {

    /** 常驻静默条单独一个渠道，和会响的上课/下课通知分开，用户可在系统设置里单独关 */
    const val CHANNEL_FOCUS = "class_focus_v3"
    const val NOTIFICATION_ID = 780123

    const val ACTION_FOCUS_TICK = "com.schedule.app.action.FOCUS_TICK"
    const val ACTION_FOCUS_DISMISS = "com.schedule.app.action.FOCUS_DISMISS"
    const val EXTRA_BLOCK_KEY = "block_key"

    private const val REQ_TICK = 3200
    /** 一次排入多少个心跳点（互不依赖，某个被系统吞掉不影响后面的） */
    private const val TICK_QUEUE = 4
    private const val REQ_VIEW = 3201
    private const val REQ_DISMISS = 3202

    private const val KEY_DISMISSED = "focus_dismissed_keys"
    private const val KEY_ACTIVE = "focus_active_key"
    private const val SEPARATOR = "\u0001"

    // ---------------- 渠道 ----------------

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // 遗留渠道每次都幂等清掉（放在存在性判断之前，否则漏删）
        runCatching { nm.deleteNotificationChannel("class_focus_v1") }
        runCatching { nm.deleteNotificationChannel("class_focus_v2") }
        if (nm.getNotificationChannel(CHANNEL_FOCUS) != null) return
        // 重要性用 HIGH 而不是 LOW：MIUI/澎湃的锁屏与息屏（AOD）只认高等级通知，
        // 低等级的常驻条在息屏上根本不出现（用户实测对比系统自带通知后反馈）。
        // 声音/震动仍然没有：通知级 setSilent(true) + setOnlyAlertOnce(true) 压着，
        // 渠道等级这里只管"能不能上锁屏/息屏、要不要占状态栏图标"，不会真的响。
        val ch = NotificationChannel(CHANNEL_FOCUS, "焦点通知（常驻）", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "上课时段常驻的倒计时通知，不响铃、不弹出横幅"
        // 静默常驻：显式设成无声（澎湃对"没显式设置"的渠道会自作主张判成异常处理）
        ch.setSound(null, AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        ch.enableVibration(false)
        ch.enableLights(false)
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC   // 锁屏也显示（渠道首建定死，故换 id 重建）
        nm.createNotificationChannel(ch)
    }

    // ---------------- 对外入口 ----------------

    /**
     * 按当前时刻刷新通知 + 重排下一次心跳。
     * 调用时机：每次数据/配置变更后的 schedule()、每分钟心跳、亮屏、回前台。
     */
    fun update(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        ensureChannel(context)
        // 提醒配置属于"设置类"（不随测试课表切换）；课表数据与"本段已关闭"记录走 dataStore。
        val prefs = context.getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)
        val data = TestSchedule.dataStore(context)
        if (!activeFor(prefs, context)) {
            cancel(context)
            armTick(context, null)
            return
        }
        val reminder = loadReminderConfig(prefs)
        val advance = focusAdvance(reminder)
        val blocks = focusBlocks(data, reminder, now)
        val dismissed = dismissedKeys(data)
        val block = blocks.firstOrNull { it.activeAt(now, advance) && !dismissed.contains(it.key()) }
        val state = block?.let { focusStateFor(it, now, advance) }

        when {
            // 窗口外 → 收掉常驻条（锁屏时也要收，免得解锁后看到过期内容）
            state == null -> cancel(context)
            // 灭屏也刷新：熄屏显示/锁屏卡片上这条通知是"看得见"的，
            // 以前那句"灭屏不重发（省电）"会让上面的剩余时间冻住 —— 用户实测反馈过。
            // 秒级精度另有系统倒计时兜底（见 post 里的 chronometer），
            // 所以就算 Doze 把分钟心跳合并延后，锁屏上显示的剩余时间也不会错。
            else -> post(context, state)
        }
        armTicks(context, nextFocusWakeTimes(blocks, now, advance, dismissed, TICK_QUEUE))
    }

    /** 焦点通知当前是否处于"应该工作"的状态 */
    fun activeFor(prefs: SharedPreferences, context: Context): Boolean {
        val reminder = loadReminderConfig(prefs)
        return reminder.enabled && reminder.focusEnabled && notificationsGranted(context)
    }

    /** 只看今天和明天：连堂段不会跨天，隔天没有常驻的必要 */
    private fun focusBlocks(
        prefs: SharedPreferences,
        reminder: ReminderConfig,
        now: LocalDateTime,
    ): List<ClassBlock> = buildClassBlocks(
        upcomingClassEvents(
            loadCoursesFromPrefs(prefs), loadConfigFromPrefs(prefs), loadSlots(prefs),
            reminder, now.toLocalDate().atStartOfDay(), horizonDays = 2,
            holidays = loadHolidays(prefs)
        )
    )

    /** 通知上的「关闭」：本次连堂段不再常驻，下一段照常出现 */
    fun dismiss(context: Context, blockKey: String?) {
        val prefs = TestSchedule.dataStore(context)
        val key = blockKey ?: prefs.getString(KEY_ACTIVE, "")
        if (!key.isNullOrBlank()) {
            val set = dismissedKeys(prefs).toMutableSet()
            set.add(key)
            prefs.edit().putString(KEY_DISMISSED, prune(set).joinToString(SEPARATOR)).commit()
        }
        cancel(context)
        update(context)   // 重排心跳：本块已关，直接排到下一段的窗口开启点
    }

    /** 设置里重新打开焦点通知时，清掉历史"已关闭"记录，避免旧的静默状态跟着走 */
    fun clearDismissed(context: Context) {
        TestSchedule.dataStore(context).edit().remove(KEY_DISMISSED).remove(KEY_ACTIVE).apply()
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    // ---------------- 心跳闹钟 ----------------

    private fun tickIntent(context: Context, index: Int): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_TICK + index,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FOCUS_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 排下一次心跳（单点版本）。
     * 传 null 表示撤销全部心跳。
     */
    fun armTick(context: Context, at: LocalDateTime?) {
        armTicks(context, if (at == null) emptyList() else listOf(at))
    }

    /**
     * 排入未来若干个心跳点（互不依赖）：以前只排"下一分钟"一个，整条链靠它递归续命，
     * 熄屏/被系统限制时一断就全断（实测：锁屏几分钟后剩余时间不再刷新，只能等亮屏或改时间才更正）。
     * 用 setExactAndAllowWhileIdle 而不是 setAlarmClock：每分钟一次不该在状态栏挂闹钟图标。
     */
    fun armTicks(context: Context, times: List<LocalDateTime>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (i in 0 until TICK_QUEUE) {
            val pi = tickIntent(context, i)
            val at = times.getOrNull(i)
            if (at == null) {
                runCatching { am.cancel(pi) }
                continue
            }
            val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, millis, pi)
                }
            }.onFailure { runCatching { am.set(AlarmManager.RTC_WAKEUP, millis, pi) } }
        }
    }

    // ---------------- 通知内容 ----------------

    private fun viewIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQ_VIEW,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun dismissIntent(context: Context, blockKey: String): PendingIntent = PendingIntent.getBroadcast(
        context, REQ_DISMISS,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FOCUS_DISMISS)
            .putExtra(EXTRA_BLOCK_KEY, blockKey),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 标题：
     *   课前/课上 → "《课名》还有 20 分钟上课 / 还有 1小时5分钟下课 / 马上上课"
     *   上课后 15 秒 → "《课名》上课了"；下课后 15 秒 → "《课名》下课了"
     *   下课后 15~30 秒 → 有连堂："下一节《下一门课》"；没有：继续"下课了"
     */
    /**
     * 息屏显示（AOD）那行字：短，别被截断。
     * 生效要满足两个条件：① 通知 extras 带 miui.focus.param（见 [applyMiuiFocusParam]）；
     * ② 应用拿到小米的"焦点通知权限"（见 [hasFocusPermission]）。
     */
    private fun aodTextFor(st: FocusState): String = when (st.phase) {
        FocusPhase.START_ACK -> "上课了"
        FocusPhase.END_ACK -> "下课了"
        FocusPhase.END_NEXT -> st.next?.let { "下一节《${it.course.name}》" } ?: "下课了"
        FocusPhase.PRE -> "还有${advanceDurationText(st.minutesLeft)}上课"
        else -> "还有${advanceDurationText(st.minutesLeft)}下课"
    }

    /** 仅供（已停用的）焦点参数注入使用，将来恢复时一起用上 */
    @Suppress("unused")
    private fun escJson(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * 【已停用】小米澎湃的"超级岛 / 焦点通知"参数注入。
     *
     * 官方文档给的客户端本地通知接入方式：extras 里放 miui.focus.param（JSON）+
     * miui.focus.pics（Bundle），param_v2.aodTitle 那行字就会出现在息屏显示上。
     *
     * 但在澎湃OS 3（红米 K70 至尊版）上实测的结果是：**带了这个参数的通知哪都不显示** ——
     * dumpsys 里通知明明还在（且被打上 focusType=PARAMS，证明系统认了参数），
     * 通知栏和锁屏上却一条都看不到，连原本好好的常驻条也一起消失；
     * 系统的 canShowFocus 查询返回 true（也就是"有焦点通知权限"）也救不回来。
     * 把注入去掉后（focusType 变回 null），常驻条立刻在锁屏上正常出现。
     *
     * 结论：息屏显示这条路走不通，通知回归"普通常驻条"。
     * 恢复方式：把下面注释里记录的原实现填回来，同时确认小米已经修好这个通道。
     *
     *   原实现（勿删，恢复时照抄）：
     *   runCatching {
     *       val json = "{\"param_v2\":{\"ticker\":\"${escJson(ticker)}\",\"tickerPic\":\"miui.focus.pic_ticker\"," +
     *           "\"aodTitle\":\"${escJson(aodTitle)}\",\"aodPic\":\"miui.focus.pic_aod\"}}"
     *       val icon = IconCompat.createWithResource(context, R.drawable.ic_notification_class).toIcon(context)
     *       val pics = Bundle().apply {
     *           putParcelable("miui.focus.pic_ticker", icon)
     *           putParcelable("miui.focus.pic_aod", icon)
     *       }
     *       b.addExtras(Bundle().apply {
     *           putString("miui.focus.param", json)
     *           putBundle("miui.focus.pics", pics)
     *       })
     *   }
     */
    private fun applyMiuiFocusParam(context: Context, b: NotificationCompat.Builder, ticker: String, aodTitle: String) {
        // 故意留空：见上面注释。调用点保留着，将来恢复只改这里。
    }

    /**
     * 查小米的"焦点通知权限"（澎湃OS 独有）。
     * true/false；null = 查不到（非小米系统 / 接口不存在 / 被系统挡了）。
     * 注意：必须由应用自己带 package 名去查 —— 用 adb shell 查会被 SecurityException 挡住。
     * （Bundle.get 只为"不知道返回值的类型、挨个探"而用，是唯一能遍历未知类型 Bundle 的办法）
     */
    @Suppress("DEPRECATION")
    fun hasFocusPermission(context: Context): Boolean? = runCatching {
        val uri = android.net.Uri.parse("content://miui.statusbar.notification.public")
        val args = Bundle().apply { putString("package", context.packageName) }
        val res = context.contentResolver.call(uri, "canShowFocus", null, args) ?: return@runCatching null
        for (k in res.keySet()) {
            val v = res.get(k)
            if (v is Boolean) return@runCatching v
        }
        res.getString("result")?.lowercase()?.let { if (it == "true") true else if (it == "false") false else null }
    }.getOrNull()

    /** 开发者页展示用：把权限查询的原始返回也带上，方便判断"到底能不能上息屏" */    @Suppress("DEPRECATION")
    fun focusPermissionDebug(context: Context): String = runCatching {
        val uri = android.net.Uri.parse("content://miui.statusbar.notification.public")
        val args = Bundle().apply { putString("package", context.packageName) }
        val res = context.contentResolver.call(uri, "canShowFocus", null, args)
        val ok = hasFocusPermission(context)
        if (res == null) "查询无返回（非小米系统？）"
        else "canShowFocus=" + (ok?.toString() ?: "未解析") + "｜原始=" + res.keySet().joinToString(",") { "$it=${res.get(it)}" }
    }.getOrElse { "查询失败：${it.message}" }

    /** 开发者页的"焦点通知测试"：发一条和常驻条同渠道的通知，验证通知栏/锁屏会不会显示（90 秒后自动消失） */
    fun notifyFocusTest(context: Context) {
        ensureChannel(context)
        if (!notificationsGranted(context)) return
        val text = "还有 20 分钟上课"
        val b = NotificationCompat.Builder(context, CHANNEL_FOCUS)
            .setSmallIcon(R.drawable.ic_notification_class)
            .setContentTitle("《焦点通知测试》$text")
            .setContentText("测试 · 通知栏/锁屏上应该能看到这条通知")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setTimeoutAfter(90_000L)
        applyMiuiFocusParam(context, b, "《焦点通知测试》$text", text)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + 1, b.build()) }
    }

    /**
     * 标题只放"状态 + 剩余时间"，**不放课名**：
     *   课前/课上 → "还有 20 分钟上课 / 还有 1小时5分钟下课 / 马上上课"
     *   上课后 15 秒 → "上课了"；下课后 15 秒 → "下课了"
     *   下课后 15~30 秒 → 有连堂："下一节课"；没有：继续"下课了"
     *
     * 课名挪到正文第一行（见 [post]）。原因是标题和课名挤在同一行时，
     * 课名一长就折行，把"还有 X 分钟上课"顶到第二行去——关键信息反被课名挡住。
     * 分开之后课名再长也只影响它自己那行，标题永远只有十来个字。
     */
    private fun titleText(st: FocusState): String = when (st.phase) {
        FocusPhase.START_ACK -> "上课了"
        FocusPhase.END_ACK -> "下课了"
        FocusPhase.END_NEXT -> if (st.next != null) "下一节课" else "下课了"
        else -> {
            val suffix = if (st.phase == FocusPhase.PRE) "上课" else "下课"
            if (st.minutesLeft <= 0) "马上$suffix" else "还有 ${advanceDurationText(st.minutesLeft)}$suffix"
        }
    }

    private fun post(context: Context, st: FocusState) {
        if (!notificationsGranted(context)) return
        val e = st.event
        // 显示"下一节"那一段时，正文换成下一节课的信息
        val shown = if (st.phase == FocusPhase.END_NEXT) (st.next ?: e) else e
        // 正文两行：第一行课名（多长都只影响自己这行），第二行时间·节次·教室。
        // 课名放这里而不是标题，就不会再把"还有 X 分钟上课"挤到第二行去。
        val detail = buildString {
            append(shown.timeRangeText()).append(" · ").append(shown.compactSlotLabel())
            if (shown.course.room.isNotBlank()) append(" · ").append(shown.course.room)
        }
        val body = shown.course.name + "\n" + detail
        TestSchedule.dataStore(context)
            .edit().putString(KEY_ACTIVE, st.block.key()).apply()

        val builder = NotificationCompat.Builder(context, CHANNEL_FOCUS)
            .setSmallIcon(R.drawable.ic_notification_class)
            .setContentTitle(titleText(st))
            .setContentText(body)
            // 不设展开样式：长按/下拉都不出"课表详情"，只显示正文 + 查看/关闭
            .setSubText(if (st.block.events.size > 1) "连堂 ${st.indexInBlock}/${st.block.events.size} 节" else null)
            // 常驻：右滑不掉、清全部也不掉，只有点「关闭」才消失
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .addAction(0, "查看", viewIntent(context))
            .addAction(0, "关闭", dismissIntent(context, st.block.key()))
        // 右上角的剩余时间交给系统自己走（chronometer）：锁屏上每秒刷新，不依赖我们的分钟心跳 ——
        // 熄屏/Doze 把心跳延后时，那串倒计时也不会停在旧值上。
        val countdownTo = when (st.phase) {
            FocusPhase.PRE -> st.event.startDateTime
            FocusPhase.IN -> LocalDateTime.of(st.event.date, st.event.endTime)
            else -> null    // 上课了/下课了/下一节 这几段是瞬时状态，不显示倒计时
        }
        if (countdownTo != null) {
            builder.setWhen(countdownTo.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) builder.setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        // 小米澎湃的息屏/超级岛：把当前要显示的那行字也塞进 miui.focus.param
        applyMiuiFocusParam(context, builder, titleText(st), aodTextFor(st))
        // 故意不设 setContentIntent：点通知本体不跳 App（避免误触），跳转走「查看」

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun notificationsGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    // ---------------- "本段已关闭"记录 ----------------

    private fun dismissedKeys(prefs: SharedPreferences): Set<String> =
        (prefs.getString(KEY_DISMISSED, "") ?: "").split(SEPARATOR).filter { it.isNotBlank() }.toSet()

    /** 只留昨天/今天/明天的记录 */
    private fun prune(set: Set<String>): Set<String> {
        val today = LocalDate.now()
        val days = listOf(today, today.minusDays(1), today.plusDays(1)).map { it.toString() }
        val kept = set.filter { s -> days.any { s.startsWith(it) } }
        return if (kept.size > 100) kept.takeLast(100).toSet() else kept.toSet()
    }

    /** 屏幕是否亮着（锁屏期间不重发通知） */
    fun screenInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isInteractive }.getOrDefault(true)
    }
}
