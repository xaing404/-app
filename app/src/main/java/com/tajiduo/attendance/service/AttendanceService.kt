package com.tajiduo.attendance.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.tajiduo.attendance.data.AccountStore
import com.tajiduo.attendance.data.SettingsStore
import com.tajiduo.attendance.data.StateStore
import com.tajiduo.attendance.notify.EmailNotifier
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
        Log.i(TAG, "service start: force=$force killAfter=$killAfter")

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
        val runStartedAt = System.currentTimeMillis()
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
            Log.i(
                TAG,
                "run finished: success=$success successCount=${result.successCount} " +
                    "failedCount=${result.failedCount} skippedCount=${result.skippedCount}",
            )
            if (settings.notifyEnabled) {
                NotificationHelper(this).notifyResult(summary, success)
            }
            sendWebhook(settings, summary)
            sendEmailSafe(
                settings = settings,
                summary = summary,
                successCount = result.successCount,
                failedCount = result.failedCount,
                skippedCount = result.skippedCount,
                startedAt = result.startedAt,
                finishedAt = result.finishedAt,
            )
        }
        catch (error: Exception) {
            summary = "签到执行失败：${error.message}"
            success = false
            Log.e(TAG, "run failed", error)
            try {
                NotificationHelper(this).notifyError(summary)
            }
            catch (_: Exception) {
                // 忽略通知失败
            }
            sendEmailSafe(
                settings = settings,
                summary = summary,
                successCount = 0,
                failedCount = 1,
                skippedCount = 0,
                startedAt = runStartedAt,
                finishedAt = System.currentTimeMillis(),
            )
        }
        finally {
            broadcastFinished(summary, success)
            releaseWakeLock()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (killAfter && settings.killAfterRun) {
                Log.i(TAG, "kill process for power saving")
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

    /**
     * 邮件通知（QQ 邮箱）：同步发送，位于杀进程之前完成；
     * 未配置时静默跳过，失败仅记录日志，不影响签到主流程与日志脱敏。
     */
    private fun sendEmailSafe(
        settings: SettingsStore,
        summary: String,
        successCount: Int,
        failedCount: Int,
        skippedCount: Int,
        startedAt: Long,
        finishedAt: Long,
    ) {
        if (!settings.emailConfigured) return
        val recipient = settings.emailRecipient.ifBlank { settings.emailSender }
        val status = when {
            failedCount > 0 -> "失败"
            successCount > 0 -> "成功"
            else -> "跳过"
        }
        val subject = "塔吉多签到 | $status | ${formatEmailTime(startedAt)}"
        val content = buildString {
            appendLine("塔吉多每日签到结果（自动发送）")
            appendLine()
            appendLine("签到开始：${formatEmailTime(startedAt, withSeconds = true)}")
            appendLine("签到结束：${formatEmailTime(finishedAt, withSeconds = true)}")
            appendLine("结果状态：$status（成功 $successCount / 失败 $failedCount / 跳过 $skippedCount）")
            appendLine()
            appendLine("——————————")
            appendLine(summary)
            appendLine("——————————")
            append("本邮件由「塔吉多签到」App 自动发送，请勿直接回复。")
        }
        try {
            val errors = EmailNotifier.send(settings.emailSender, settings.emailAuthCode, recipient, subject, content)
            if (errors.isEmpty()) {
                Log.i(TAG, "email sent")
            } else {
                Log.e(TAG, "email send failed: ${errors.joinToString("; ")}")
            }
        }
        catch (error: Exception) {
            Log.e(TAG, "email send failed: ${error.message}")
        }
    }

    private fun formatEmailTime(timestamp: Long, withSeconds: Boolean = false): String =
        SimpleDateFormat(if (withSeconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date(timestamp))

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
        private const val TAG = "TajiduoAttendance"
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