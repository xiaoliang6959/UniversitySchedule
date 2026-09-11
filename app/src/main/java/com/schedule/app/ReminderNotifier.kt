package com.schedule.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 上课提醒的通知渠道、通知发送与闹钟调度。
 */
object ReminderNotifier {

    // 渠道 id 带 _v2：小米/澎湃对"创建时没显式 setSound"的渠道显示"无"且不出声，
    // 而声音/振动/锁屏可见性都是创建后 App 改不动的用户锁定属性 → 只能换新 id 重建，
    // 旧 id（class_start / class_end / class_end_high）在版本升级块里统一删除。
    // 课前(提前N分钟)与准点(开始时)拆成两个渠道，用户可在系统设置里分别调声音/横幅；
    // class_start_v2 沿用为"准点"渠道，老用户已配置的声音等属性不丢。
    const val CHANNEL_START = "class_start_v2"
    const val CHANNEL_ADVANCE = "class_advance_v1"
    const val CHANNEL_END = "class_end_v2"

    /** 历史废弃渠道：常驻保活、无声音的 v1、下课悬浮过渡版，启动时统一清理 */
    private const val LEGACY_CHANNEL_SERVICE = "class_keep_alive"
    private val LEGACY_CHANNELS = listOf(LEGACY_CHANNEL_SERVICE, "class_start", "class_end", "class_end_high")

    /** 渠道配置版本号：改了渠道属性（声音/浮窗/锁屏可见性等）就 +1，触发旧渠道清理 */
    private const val KEY_CHANNEL_VERSION = "channel_version"
    private const val CHANNEL_VERSION = 5

    /** 提前提醒的最大分钟数（7 天，UI 滚轮与配置读取共用） */
    const val ADVANCE_MAX = 7 * 24 * 60

    private const val KEY_NOTIFIED_START = "notified_start_keys"
    private const val KEY_NOTIFIED_END = "notified_end_keys"
    private const val SEPARATOR = "\u0001"

    private const val REQ_START = 1001
    private const val REQ_END = 1002
    private const val REQ_HEARTBEAT = 1003

    /**
     * 一次性排入未来 N 个触发点（每个用独立 requestCode）。
     * 以前只排"下一个"，只要有一次触发丢失或被时间跳变冲掉，整条队列就断档了。
     */
    private const val QUEUE_SIZE = 12
    private const val REQ_QUEUE_BASE = 2000

    /** 时钟跳变检测：墙钟与单调时钟的偏差超过这个值就认为用户改了系统时间 */
    private const val CLOCK_JUMP_THRESHOLD_MS = 120_000L

    private const val KEY_JUMP_WALL = "clock_wall"
    private const val KEY_JUMP_ELAPSED = "clock_elapsed"
    private const val KEY_LAST_FIRE_AT = "last_fire_at"

    // ---------------- 渠道 ----------------

    /** 系统默认通知声（小米上即"水滴"）。显式设给渠道，避免 MIUI 把 null 当"无"且不出声 */
    private fun defaultNotifySound(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private val soundAudioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun buildStartChannel() = NotificationChannel(
        CHANNEL_START, "上课提醒（准点）", NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "上课开始时提醒课程、时间与教室"
        setSound(defaultNotifySound(), soundAudioAttrs)
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 400, 200, 400)
        lightColor = Color.parseColor("#FF29B6F6")
        enableLights(true)
        setShowBadge(true)
        // 锁屏上也完整显示（否则锁屏横幅会被折叠成"1 条通知"）
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        runCatching { setBypassDnd(true) }
    }

    /** 课前（提前 N 分钟）独立渠道：与准点分开后可在系统设置里各调声音/横幅/免打扰 */
    private fun buildAdvanceChannel() = NotificationChannel(
        CHANNEL_ADVANCE, "课前提醒", NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "提前若干分钟预告下节课"
        setSound(defaultNotifySound(), soundAudioAttrs)
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 400, 200, 400)
        lightColor = Color.parseColor("#FF29B6F6")
        enableLights(true)
        setShowBadge(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        runCatching { setBypassDnd(true) }
    }

    private fun buildEndChannel() = NotificationChannel(
        CHANNEL_END, "下课提醒", NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "课程结束时提示下一节课"
        setSound(defaultNotifySound(), soundAudioAttrs)
        enableVibration(true)
        vibrationPattern = longArrayOf(0, 400, 200, 400)
        setShowBadge(true)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

        if (nm.getNotificationChannel(CHANNEL_START) == null) {
            nm.createNotificationChannel(buildStartChannel())
        } else {
            // 渠道名/描述允许更新（importance/声音不行）：老用户把旧"上课提醒"改名成"准点"
            runCatching { nm.createNotificationChannel(buildStartChannel()) }
        }
        if (nm.getNotificationChannel(CHANNEL_ADVANCE) == null) {
            nm.createNotificationChannel(buildAdvanceChannel())
        }
        if (nm.getNotificationChannel(CHANNEL_END) == null) {
            nm.createNotificationChannel(buildEndChannel())
        }

        // 历史渠道（无声音的 v1、下课悬浮过渡版、废弃保活渠道）每次启动都尝试删——
        // deleteNotificationChannel 对不存在的 id 是 no-op，幂等且能兜住"上次删除没跑成"的情况，
        // 避免设置页残留重复条目。
        LEGACY_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        if (prefs.getInt(KEY_CHANNEL_VERSION, 1) < CHANNEL_VERSION) {
            prefs.edit().putInt(KEY_CHANNEL_VERSION, CHANNEL_VERSION).apply()
        }
    }

    // ---------------- 悬浮通知 / 渠道跳转 ----------------

    /** 指定渠道当前的横幅等级（IMPORTANCE_HIGH 才会悬浮） */
    fun channelImportance(context: Context, channelId: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return NotificationManager.IMPORTANCE_HIGH
        val nm = context.getSystemService(NotificationManager::class.java) ?: return NotificationManager.IMPORTANCE_HIGH
        return nm.getNotificationChannel(channelId)?.importance ?: NotificationManager.IMPORTANCE_NONE
    }

    /** 上课提醒渠道当前的横幅等级（准点+课前取较低者：任一被降级就不算完全悬浮可用） */
    fun startChannelImportance(context: Context): Int =
        minOf(channelImportance(context, CHANNEL_START), channelImportance(context, CHANNEL_ADVANCE))

    /** 悬浮通知是否可用：渠道等级为高 且 应用总通知开关打开 */
    fun headsUpReady(context: Context): Boolean =
        notificationsGranted(context) && startChannelImportance(context) >= NotificationManager.IMPORTANCE_HIGH

    /** 跳到本应用的通知类别列表（课前提醒/上课准点/下课提醒三个类别都在这里分别调） */
    fun openChannelSettings(context: Context) {
        openNotificationSettings(context)
    }

    // ---------------- 权限 ----------------

    /** Android 13+ 需要运行时授权通知 */
    fun notificationsGranted(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 跳转到本应用的通知设置页 */
    fun openNotificationSettings(context: Context) {
        val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 跳转到"闹钟和提醒"系统设置页（Android 12+）。
     * Android 14 起 SCHEDULE_EXACT_ALARM 默认拒绝，光在 Manifest 声明不够，必须用户手动打开。
     */
    fun openAlarmSettings(context: Context) {
        // ACTION_REQUEST_SCHEDULE_EXACT_ALARM 需要 API 31，低版本直接跳应用详情页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val direct = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(direct) }.isSuccess) return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 跳转到电池优化白名单设置（国产 ROM 上省电策略会杀掉闹钟） */
    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
        openNotificationSettings(context)
    }

    /** 跳转到 MIUI/澎湃OS 的自启动管理页（找不到就退回应用详情页） */
    fun openAutostartSettings(context: Context) {
        val candidates = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .putExtra("extra_pkgname", context.packageName)
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---------------- 最近任务加锁提示 ----------------

    private const val KEY_LOCK_ADVISED = "lock_advised"

    /** 是否已在提醒页向用户展示过"手动加锁"引导（展示一次后收起） */
    fun lockAdvised(context: Context): Boolean =
        context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCK_ADVISED, false)

    fun setLockAdvised(context: Context, value: Boolean) {
        context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCK_ADVISED, value).apply()
    }

    // ---------------- 发送通知 ----------------

    private fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun notifyClassStart(context: Context, event: ClassEvent, advanceMinutes: Int) {
        if (!notificationsGranted(context)) return
        val c = event.course
        val whenText = when (advanceMinutes) {
            0 -> "现在上课"
            else -> "还有 ${advanceDurationText(advanceMinutes)}上课"
        }
        val body = buildString {
            // 澎湃OS/MIUI 的横幅默认只展两行，正文越短越不容易被截断
            append("${event.dayLabel()} ${event.timeRangeText()} · ${event.compactSlotLabel()}")
            // 教师行不放通知里：多一行会把教室截断
            if (c.room.isNotBlank()) append("\n教室：${c.room}")
        }
        // 提前量 0 = 准点渠道；>0 = 课前渠道（两者可在系统设置里分别调声音/横幅）
        val channel = if (advanceMinutes == 0) CHANNEL_START else CHANNEL_ADVANCE
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_class)
            .setContentTitle("《${c.name}》$whenText")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(event.notificationId(advanceMinutes), notification)
        }
    }

    /** 下课通知：每节课下课都发；[next] 非空表示下节课紧接着本节，通知里带上它的信息 */
    fun notifyClassEnd(context: Context, event: ClassEvent, next: ClassEvent?) {
        if (!notificationsGranted(context)) return
        val body = if (next == null) {
            "《${event.course.name}》已下课"
        } else {
            "《${event.course.name}》已下课\n下一节：《${next.course.name}》${next.timeRangeText()} · ${next.compactSlotLabel()}" +
                if (next.course.room.isNotBlank()) "\n教室：${next.course.room}" else ""
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_END)
            .setSmallIcon(R.drawable.ic_notification_class)
            .setContentTitle("下课啦")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // 与上课通知一致：高优先级 + ALARM 类别，才能悬浮弹出
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(event.notificationId(0) + 7, notification) }
    }

    /** 设置页里的"发送测试通知" */
    fun notifyTest(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_START)
            .setSmallIcon(R.drawable.ic_notification_class)
            .setContentTitle("通知权限已开启 ✅")
            .setContentText("上课前会在这里提醒你课程和教室")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(999999, notification) }
    }

    // ---------------- 闹钟调度 ----------------

    fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun toEpochMillis(time: LocalDateTime): Long =
        time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * 排入未来若干个触发点（互不依赖），外加一个心跳兜底。
     * 每次数据/配置/时间变化后重排。
     */
    fun schedule(context: Context, now: LocalDateTime = LocalDateTime.now()) {
        ensureChannels(context)
        // 提醒配置/时间跳变基线属于"设置类"，固定放真实文件（切测试课表不该改动用户的提醒设置）；
        // 课表数据与"已提醒"去重记录走 dataStore —— 去重 key 由课程信息拼成，
        // 和真实记录混在一份里会让切换课表时误判"已提醒过"（或重复提醒）。
        val prefs = context.getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)
        val data = TestSchedule.dataStore(context)
        val reminder = loadReminderConfig(prefs)

        // 必须在重写基线前检测：用户改完系统时间回 App 时，接收器往往还没跑过
        val jump = clockJumpMillis(prefs)
        if (jump != null) {
            data.edit().remove(KEY_NOTIFIED_START).remove(KEY_NOTIFIED_END).apply()
        }

        cancel(context)
        // 三选一是互斥的：这里按模式只启动一套，另一套的残留（响过的普通通知、旧的常驻条）
        // 顺手清掉，避免"两种通知同时说话"。
        when (reminder.mode) {
            MODE_FOCUS -> {
                // 只挂常驻倒计时条（静默），不排任何会响的到点提醒
                FocusNotifier.update(context, now)
                return
            }
            MODE_OFF -> {
                FocusNotifier.cancel(context)
                FocusNotifier.armTick(context, null)
                return
            }
            else -> {
                // 普通通知：任何焦点条残留都要收掉
                FocusNotifier.cancel(context)
                FocusNotifier.armTick(context, null)
            }
        }

        val events = upcomingClassEvents(
            loadCoursesFromPrefs(data), loadConfigFromPrefs(data), loadSlots(data), reminder, now,
            holidays = loadHolidays(data)
        )

        // 每节课按每个提前量各排一个触发点；触发后由 Receiver 递归排期，避免注册大量闹钟。
        // 下课提醒开着时，提前量≥前两节间隔的课前点会被下课通知覆盖 → 不排（见 advanceCoveredByPrevEnd）。
        val triggerTimes = ArrayList<LocalDateTime>()
        events.forEach { e ->
            reminder.advances.forEach { adv ->
                if (reminder.endReminder && advanceCoveredByPrevEnd(e, adv, events)) return@forEach
                val t = e.triggerAt(adv)
                if (t.isAfter(now)) triggerTimes.add(t)
            }
            if (reminder.endReminder) {
                val end = LocalDateTime.of(e.date, e.endTime)
                if (end.isAfter(now)) triggerTimes.add(end)
            }
        }
        val am = alarmManager(context)
        triggerTimes.sort()
        // 排入前 QUEUE_SIZE 个触发点，互不依赖：某一个丢失/被时间跳变冲掉，后面的仍能到点触发。
        // 以前只排最小值一个，整条链路全靠它"活着并递归"，一断就全断。
        triggerTimes.take(QUEUE_SIZE).forEachIndexed { i, t ->
            setAlarmClock(am, pendingIntent(context, ReminderReceiver.ACTION_PROCESS, REQ_QUEUE_BASE + i), toEpochMillis(t))
        }
        val heartbeat = maxOf(toEpochMillis(now.plusHours(12)), toEpochMillis(now) + 60_000L)
        setFallbackAlarm(am, pendingIntent(context, ReminderReceiver.ACTION_PROCESS, REQ_HEARTBEAT), heartbeat)
        prefs.edit()
            .putLong(KEY_JUMP_WALL, System.currentTimeMillis())
            .putLong(KEY_JUMP_ELAPSED, android.os.SystemClock.elapsedRealtime())
            .apply()
    }

    /**
     * 检测系统时间是否被手动改动：墙钟流逝量与单调时钟流逝量偏差过大即为跳变。
     * 返回 null 表示无跳变；否则返回带符号的偏移毫秒（正 = 时间被调快）。
     */
    fun clockJumpMillis(prefs: SharedPreferences): Long? {
        if (!prefs.contains(KEY_JUMP_WALL) || !prefs.contains(KEY_JUMP_ELAPSED)) return null
        val wallDelta = System.currentTimeMillis() - prefs.getLong(KEY_JUMP_WALL, 0L)
        val monoDelta = android.os.SystemClock.elapsedRealtime() - prefs.getLong(KEY_JUMP_ELAPSED, 0L)
        val drift = wallDelta - monoDelta
        return if (Math.abs(drift) > CLOCK_JUMP_THRESHOLD_MS) drift else null
    }

    /** 诊断：下次已注册闹钟的真实时间（AlarmManager 原话，不是我们算的） */
    fun nextRegisteredAlarm(context: Context): Long? =
        runCatching { alarmManager(context).nextAlarmClock?.triggerTime }.getOrNull()

    fun exactAlarmsAllowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager(context).canScheduleExactAlarms() else true

    /** setAlarmClock 无需 SCHEDULE_EXACT_ALARM 权限，且能穿透 Doze，最适合按点上课提醒 */
    private fun setAlarmClock(am: AlarmManager, pi: PendingIntent, atMillis: Long) {
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, pi), pi)
        }.onFailure {
            setFallbackAlarm(am, pi, atMillis)
        }
    }

    private fun setFallbackAlarm(am: AlarmManager, pi: PendingIntent, atMillis: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        }.onFailure {
            runCatching { am.set(AlarmManager.RTC_WAKEUP, atMillis, pi) }
        }
    }

    fun cancel(context: Context) {
        val am = alarmManager(context)
        am.cancel(pendingIntent(context, ReminderReceiver.ACTION_PROCESS, REQ_HEARTBEAT))
        (0 until QUEUE_SIZE).forEach { i ->
            am.cancel(pendingIntent(context, ReminderReceiver.ACTION_PROCESS, REQ_QUEUE_BASE + i))
        }
        // 清掉旧的单点闹钟（REQ_START/REQ_END 已停用，兼容升级前留下的排期）
        am.cancel(pendingIntent(context, ReminderReceiver.ACTION_PROCESS, REQ_START))
        am.cancel(pendingIntent(context, ReminderReceiver.ACTION_PROCESS, REQ_END))
    }

    // ---------------- 去重记录 ----------------

    private fun notifiedKeys(prefs: SharedPreferences, key: String): MutableSet<String> =
        (prefs.getString(key, "") ?: "").split(SEPARATOR).filter { it.isNotBlank() }.toMutableSet()

    private fun putNotifiedKeys(prefs: SharedPreferences, key: String, set: Set<String>) {
        // commit 同步落盘：MIUI 后台杀进程时 apply() 的异步写可能丢记录，
        // 丢了就会重复弹通知，这里宁可慢几毫秒也要保证写入。
        prefs.edit().putString(key, set.joinToString(SEPARATOR)).commit()
    }

    /** 若该课的这一个提前量尚未提醒过则返回 true 并记录（同一节课可有多条提醒） */
    fun markStartIfNeeded(prefs: SharedPreferences, event: ClassEvent, advance: Int): Boolean {
        val key = event.key() + "@" + advance
        val set = notifiedKeys(prefs, KEY_NOTIFIED_START)
        if (set.contains(key)) return false
        set.add(key)
        putNotifiedKeys(prefs, KEY_NOTIFIED_START, prune(set))
        return true
    }

    fun markEndIfNeeded(prefs: SharedPreferences, event: ClassEvent): Boolean {
        val set = notifiedKeys(prefs, KEY_NOTIFIED_END)
        if (set.contains(event.key())) return false
        set.add(event.key())
        putNotifiedKeys(prefs, KEY_NOTIFIED_END, prune(set))
        return true
    }

    /** 只保留昨天/今天/明天的记录（跨零点的课不会被重复提醒），避免无限增长 */
    private fun prune(set: Set<String>): Set<String> {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val tomorrow = today.plusDays(1)
        val kept = set.filter {
            it.startsWith(today.toString()) || it.startsWith(yesterday.toString()) || it.startsWith(tomorrow.toString())
        }
        return if (kept.size > 300) kept.takeLast(300).toSet() else kept.toSet()
    }

    /** 数据/配置变更后调用：清掉旧去重记录并重新排期 */
    fun reschedule(context: Context) {
        // 去重记录跟着"当前生效的那份课表"走
        TestSchedule.dataStore(context).edit()
            .remove(KEY_NOTIFIED_START).remove(KEY_NOTIFIED_END).apply()
        schedule(context)
    }

    // ---------------- 诊断 ----------------

    private val diagTime = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    /** 提醒页的"闹钟登记状态"文本，用来判断系统到底排没排上（只三行，多了页面太啰嗦） */
    fun diagnose(context: Context): String {
        val prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        sb.append("系统精确闹钟：").append(if (exactAlarmsAllowed(context)) "已允许 ✅" else "被禁止 ❌（提醒会不准）")
        val next = nextRegisteredAlarm(context)
        sb.append("\n已登记的下一个触发：")
        sb.append(if (next != null && next > 0) {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(next), ZoneId.systemDefault()).format(diagTime)
        } else "无（没有闹钟在等）")
        val last = prefs.getLong(KEY_LAST_FIRE_AT, 0L)
        sb.append("\n上次真正触发：")
        sb.append(if (last > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(last), ZoneId.systemDefault()).format(diagTime) else "从未")
        return sb.toString()
    }
}
