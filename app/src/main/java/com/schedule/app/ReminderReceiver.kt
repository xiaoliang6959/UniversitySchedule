package com.schedule.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime

/**
 * 闹钟触发点：检查有哪些课到点了就发通知，然后递归安排下一次。
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PROCESS = "com.schedule.app.action.PROCESS_REMINDER"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // 焦点通知心跳：update() 内部判断锁屏不重发、按屏幕状态排下一次唤醒
        if (intent?.action == FocusNotifier.ACTION_FOCUS_TICK) {
            FocusNotifier.update(context)
            return
        }

        // 焦点通知上的「关闭」按钮：本次不常驻了，下一个连堂块再出现
        if (intent?.action == FocusNotifier.ACTION_FOCUS_DISMISS) {
            FocusNotifier.dismiss(context, intent.getStringExtra(FocusNotifier.EXTRA_BLOCK_KEY))
            return
        }

        // prefs = 设置类（提醒配置、上次触发时间戳）；data = 课表类 + 去重记录。
        // 去重记录必须跟着课表走：它的 key 由课程信息拼成，和真实课表的记录混在一起会误判。
        val prefs = context.getSharedPreferences(TestSchedule.REAL_PREFS, Context.MODE_PRIVATE)
        val data = TestSchedule.dataStore(context)
        val reminder = loadReminderConfig(prefs)
        prefs.edit().putLong("last_fire_at", System.currentTimeMillis()).apply()
        if (!reminder.enabled) return

        // 时间跳变由 schedule() 统一检测并清理去重记录（这里不再重复处理）

        val now = LocalDateTime.now()
        val courses = loadCoursesFromPrefs(data)
        val config = loadConfigFromPrefs(data)
        val slots = loadSlots(data)
        val events = upcomingClassEvents(courses, config, slots, reminder, now.minusDays(1), horizonDays = 3, holidays = loadHolidays(data))

        // 每个提前量各查一遍，补发 30 分钟内到点但未提醒的（防止设备休眠导致闹钟迟到/丢失）。
        // 下课提醒开着时，被前一节下课通知覆盖的课前点跳过（与 schedule() 的排期规则一致）。
        for (e in events) {
            for (adv in reminder.advances) {
                if (reminder.endReminder && advanceCoveredByPrevEnd(e, adv, events)) continue
                val trig = e.triggerAt(adv)
                if (!trig.isAfter(now) && now.isBefore(trig.plusMinutes(30)) &&
                    ReminderNotifier.markStartIfNeeded(data, e, adv)
                ) {
                    ReminderNotifier.notifyClassStart(context, e, minutesUntilNow(e, now, adv))
                }
            }
        }

        // 下课提醒：每节课下课都弹一条；只有下节课紧接着本节时通知里才带它的信息。
        if (reminder.endReminder) {
            for (e in events) {
                val end = LocalDateTime.of(e.date, e.endTime)
                val info = classEndInfo(events, e)
                // shouldFireClassEnd 含"下一节还没上课"守卫：防止闹钟迟到时，
                // 在下一节上课时刻补弹上一节下课通知，与上课通知同屏重复。
                if (shouldFireClassEnd(end, info.nextStart, now) &&
                    ReminderNotifier.markEndIfNeeded(data, e)
                ) {
                    ReminderNotifier.notifyClassEnd(context, e, info.info)
                }
            }
        }

        // 安排下一次
        ReminderNotifier.schedule(context, now)
    }

    /** 触发可能滞后几秒，剩余分钟数向上取整（4分59秒应显示5而不是4） */
    private fun minutesUntilNow(e: ClassEvent, now: LocalDateTime, advance: Int): Int {
        val sec = java.time.Duration.between(now, e.startDateTime).seconds
        val mins = if (sec <= 0) 0L else (sec + 59) / 60
        return mins.coerceIn(0L, advance.toLong()).toInt()
    }
}

/**
 * 开机后恢复闹钟（AlarmManager 设置的闹钟重启后会丢失）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ReminderNotifier.ensureChannels(context)
        ReminderNotifier.schedule(context)
    }
}

/**
 * 亮屏时立刻刷一次焦点通知。
 * 锁屏期间心跳照常到点但"不重发"，通知内容会停在灭屏前那一刻；
 * 亮屏这一发补上，用户看到的就永远是准时的倒计时。
 * （ACTION_SCREEN_ON 在 Android 8 隐式广播豁免名单里，可以 Manifest 静态注册；
 *  Doze 中到点的闹钟本身也会在退出空闲时立即补发，这条是双保险。）
 */
class ScreenOnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FocusNotifier.update(context)
    }
}
