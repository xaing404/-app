package com.tajiduo.attendance.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tajiduo.attendance.data.SettingsStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 每日精确定时（AlarmManager）。API 31+ 无精确闹钟权限时退化为非精确调度。 */
object AlarmScheduler {

    private const val REQUEST_CODE = 2001
    const val ACTION_ALARM = "com.tajiduo.attendance.action.ALARM"

    fun scheduleNext(context: Context, hour: Int, minute: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerMillis(hour, minute)
        val pending = pendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canExact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
        else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun scheduleFromSettings(context: Context) {
        val settings = SettingsStore(context)
        scheduleNext(context, settings.hour, settings.minute)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pendingIntent(context))
    }

    /** 下一次触发时间戳：今天未到则今天，否则明天。 */
    fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= System.currentTimeMillis()) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun formatNextTrigger(hour: Int, minute: Int): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(nextTriggerMillis(hour, minute)))

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AttendanceAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}