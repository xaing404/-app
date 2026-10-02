package com.tajiduo.attendance.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.tajiduo.attendance.service.AttendanceService

/** 闹钟触发：先重排下一次，再启动前台服务执行签到（force=false、完成后自杀）。 */
class AttendanceAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "alarm fired at ${System.currentTimeMillis()}")
        AlarmScheduler.scheduleFromSettings(context)
        ContextCompat.startForegroundService(
            context,
            AttendanceService.buildIntent(context, force = false, killAfter = true),
        )
    }

    private companion object {
        const val TAG = "TajiduoAttendance"
    }
}