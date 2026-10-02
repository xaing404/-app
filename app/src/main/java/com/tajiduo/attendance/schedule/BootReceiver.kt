package com.tajiduo.attendance.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机 / 应用更新后重排每日闹钟。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            QUICKBOOT_POWERON -> {
                Log.i(TAG, "reschedule alarm on ${intent.action}")
                AlarmScheduler.scheduleFromSettings(context)
            }
        }
    }

    private companion object {
        const val TAG = "TajiduoAttendance"
        const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}