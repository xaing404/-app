package com.tajiduo.attendance.data

import android.content.Context

/** 用户设置（默认值与 TS 端 runtime 配置保持一致）。 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("tajiduo_settings", Context.MODE_PRIVATE)

    var hour: Int
        get() = prefs.getInt("hour", 8)
        set(value) = prefs.edit().putInt("hour", value).apply()

    var minute: Int
        get() = prefs.getInt("minute", 0)
        set(value) = prefs.edit().putInt("minute", value).apply()

    var coinTasks: Boolean
        get() = prefs.getBoolean("coin_tasks", true)
        set(value) = prefs.edit().putBoolean("coin_tasks", value).apply()

    var cloudDuration: Boolean
        get() = prefs.getBoolean("cloud_duration", true)
        set(value) = prefs.edit().putBoolean("cloud_duration", value).apply()

    var sharePlatform: String
        get() = prefs.getString("share_platform", "qq") ?: "qq"
        set(value) = prefs.edit().putString("share_platform", value).apply()

    var maxRetries: Int
        get() = prefs.getInt("max_retries", 3)
        set(value) = prefs.edit().putInt("max_retries", value).apply()

    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify_enabled", true)
        set(value) = prefs.edit().putBoolean("notify_enabled", value).apply()

    /** 签到完成后自我了结进程以省电（需求 4）。 */
    var killAfterRun: Boolean
        get() = prefs.getBoolean("kill_after_run", true)
        set(value) = prefs.edit().putBoolean("kill_after_run", value).apply()

    /** 可选：额外的 webhook 通知地址，多个用英文逗号分隔。 */
    var notificationUrls: String
        get() = prefs.getString("notification_urls", "") ?: ""
        set(value) = prefs.edit().putString("notification_urls", value).apply()

    /** 最近一次成功运行的日期（yyyy-MM-dd），用于 UI 提示。 */
    var lastRunDate: String
        get() = prefs.getString("last_run_date", "") ?: ""
        set(value) = prefs.edit().putString("last_run_date", value).apply()
}