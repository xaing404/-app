package com.tajiduo.attendance.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.tajiduo.attendance.data.AccountStore
import com.tajiduo.attendance.data.SettingsStore
import com.tajiduo.attendance.data.StateStore
import com.tajiduo.attendance.notify.NotificationHelper
import com.tajiduo.attendance.notify.NotifyWebhook
import com.tajiduo.attendance.runner.AttendanceRunner
import com.tajiduo.attendance.runner.RunnerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 前台签到服务（dataSync）：
 * 常驻通知 + WakeLock → 执行签到 → 写状态/历史 → 广播结果 → 发送通知 →
 * 释放资源 → 定时任务完成后自杀了结进程（需求 4）。
 */
class AttendanceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) {
            return START_NOT_STICKY
        }
        running = true
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        val killAfter = intent?.getBooleanExtra(EXTRA_KILL_AFTER, false) ?: false

        val helper = NotificationHelper(this)
        helper.ensureChannel()
        ServiceCompat.startForeground(
            this,
            NotificationHelper.ONGOING_ID,
            helper.buildOngoing(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireWakeLock()

        scope.launch { execute(force, killAfter) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun execute(force: Boolean, killAfter: Boolean) {
        val settings = SettingsStore(this)
        var summary = "签到未执行"
        var success = false
        try {
            val runner = AttendanceRunner(AccountStore(this), StateStore(this))
            val result = runner.run(
                RunnerOptions(
                    force = force,
                    coinTasks = settings.coinTasks,
                    cloudDuration = settings.cloudDuration,
                    sharePlatform = settings.sharePlatform,
                    maxRetries = settings.maxRetries,
                ),
            )
            summary = result.summary
            success = result.failedCount == 0
            settings.lastRunDate = shanghaiDate()
            if (settings.notifyEnabled) {
                NotificationHelper(this).notifyResult(summary, success)
            }
            sendWebhook(settings, summary)
        }
        catch (error: Exception) {
            summary = "签到执行失败：${error.message}"
            success = false
            try {
                NotificationHelper(this).notifyError(summary)
            }
            catch (_: Exception) {
                // 忽略通知失败
            }
        }
        finally {
            broadcastFinished(summary, success)
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (killAfter && settings.killAfterRun) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun sendWebhook(settings: SettingsStore, summary: String) {
        val urls = settings.notificationUrls
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (urls.isEmpty()) return
        try {
            NotifyWebhook.send(urls, "塔吉多每日签到", summary)
        }
        catch (_: Exception) {
            // 可选功能，失败不影响主流程
        }
    }

    private fun broadcastFinished(summary: String, success: Boolean) {
        val intent = Intent(ACTION_RUN_FINISHED).apply {
            setPackage(packageName)
            putExtra(EXTRA_SUMMARY, summary)
            putExtra(EXTRA_SUCCESS, success)
        }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TajiduoAttendance:run").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
            }
        }
        wakeLock = null
    }

    private fun shanghaiDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date())

    companion object {
        const val EXTRA_FORCE = "force"
        const val EXTRA_KILL_AFTER = "kill_after"
        const val ACTION_RUN_FINISHED = "com.tajiduo.attendance.action.RUN_FINISHED"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_SUCCESS = "success"

        fun buildIntent(context: Context, force: Boolean, killAfter: Boolean): Intent =
            Intent(context, AttendanceService::class.java).apply {
                putExtra(EXTRA_FORCE, force)
                putExtra(EXTRA_KILL_AFTER, killAfter)
            }
    }
}