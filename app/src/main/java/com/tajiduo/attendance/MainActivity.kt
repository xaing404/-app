package com.tajiduo.attendance

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tajiduo.attendance.data.AccountStore
import com.tajiduo.attendance.data.SettingsStore
import com.tajiduo.attendance.data.StateStore
import com.tajiduo.attendance.databinding.ActivityMainBinding
import com.tajiduo.attendance.notify.NotificationHelper
import com.tajiduo.attendance.permission.PermissionHelper
import com.tajiduo.attendance.schedule.AlarmScheduler
import com.tajiduo.attendance.service.AttendanceService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore
    private lateinit var stateStore: StateStore
    private lateinit var accountStore: AccountStore

    private var isRunning = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val runFinishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            setRunning(false)
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsStore(this)
        stateStore = StateStore(this)
        accountStore = AccountStore(this)

        accountStore.ensureSeeded()
        NotificationHelper(this).ensureChannel()
        AlarmScheduler.scheduleFromSettings(this)

        bindUi()
        ContextCompat.registerReceiver(
            this,
            runFinishedReceiver,
            IntentFilter(AttendanceService.ACTION_RUN_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        maybeRequestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(runFinishedReceiver)
        }
        catch (_: Exception) {
            // 未注册时忽略
        }
        super.onDestroy()
    }

    // ---------------- 初始化 ----------------

    private fun bindUi() {
        // 先赋值初始值，再挂监听，避免触发回调
        binding.switchCoinTasks.isChecked = settings.coinTasks
        binding.switchCloudDuration.isChecked = settings.cloudDuration
        binding.switchKillAfterRun.isChecked = settings.killAfterRun
        binding.switchNotify.isChecked = settings.notifyEnabled
        binding.tvSignTime.text = formatHourMinute(settings.hour, settings.minute)
        binding.tvSharePlatform.text = settings.sharePlatform
        binding.etMaxRetries.setText(settings.maxRetries.toString())
        binding.etWebhook.setText(settings.notificationUrls)

        binding.rowSignTime.setOnClickListener { showTimePicker() }
        binding.rowSharePlatform.setOnClickListener { showPlatformDialog() }

        binding.switchCoinTasks.setOnCheckedChangeListener { _, value -> settings.coinTasks = value }
        binding.switchCloudDuration.setOnCheckedChangeListener { _, value -> settings.cloudDuration = value }
        binding.switchKillAfterRun.setOnCheckedChangeListener { _, value -> settings.killAfterRun = value }
        binding.switchNotify.setOnCheckedChangeListener { _, value ->
            settings.notifyEnabled = value
            if (value && !PermissionHelper.hasNotificationPermission(this)) {
                maybeRequestNotificationPermission()
            }
        }

        binding.btnSaveSettings.setOnClickListener { saveTextSettings() }
        binding.btnSignNow.setOnClickListener { startRun() }
        binding.btnPermission.setOnClickListener { PermissionHelper.openAutoStartSettings(this) }

        binding.btnPermNotify.setOnClickListener { onNotifyPermissionClick() }
        binding.btnPermAlarm.setOnClickListener { PermissionHelper.requestExactAlarmPermission(this) }
        binding.btnPermBattery.setOnClickListener { PermissionHelper.requestIgnoreBatteryOptimizations(this) }
        binding.btnPermAutostart.setOnClickListener { PermissionHelper.openAutoStartSettings(this) }

        binding.rvHistory.layoutManager = LinearLayoutManager(this)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onNotifyPermissionClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        else {
            PermissionHelper.openNotificationSettings(this)
        }
    }

    // ---------------- 刷新 UI ----------------

    private fun refresh() {
        updateNextRun()
        updateStatus()
        updatePermissions()
        updateDetail()
        updateAccount()
        updateHistory()
    }

    private fun updateNextRun() {
        binding.tvNextRun.text = "下次自动签到：${AlarmScheduler.formatNextTrigger(settings.hour, settings.minute)}"
    }

    private fun updateStatus() {
        val record = stateStore.lastRecord()
        if (isRunning) {
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_unknown)
            binding.tvStatus.text = getString(R.string.status_running)
            binding.tvStatusTime.visibility = android.view.View.GONE
            return
        }
        if (record == null) {
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_unknown)
            binding.tvStatus.text = getString(R.string.status_unknown)
            binding.tvStatusTime.visibility = android.view.View.GONE
            return
        }
        val (labelRes, colorRes) = when {
            record.failedCount > 0 -> R.string.status_failed to R.color.state_bad
            record.successCount > 0 -> R.string.status_success to R.color.state_ok
            else -> R.string.status_skipped to R.color.state_warn
        }
        binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
        binding.tvStatus.text = getString(labelRes)
        binding.tvStatusTime.text = "最近运行：${formatDateTime(record.finishedAt)}"
        binding.tvStatusTime.visibility = android.view.View.VISIBLE
    }

    private fun updatePermissions() {
        val notifyOk = PermissionHelper.hasNotificationPermission(this)
        val alarmOk = PermissionHelper.canScheduleExactAlarms(this)
        val batteryOk = PermissionHelper.isIgnoringBatteryOptimizations(this)

        setPermissionStatus(binding.tvPermNotifyStatus, notifyOk)
        setPermissionStatus(binding.tvPermAlarmStatus, alarmOk)
        setPermissionStatus(binding.tvPermBatteryStatus, batteryOk)
        binding.tvPermAutostartStatus.text = "需在系统设置中手动开启"

        val allOk = notifyOk && alarmOk && batteryOk
        binding.tvPermissionHint.text = if (allOk) {
            getString(R.string.permission_hint_ok)
        }
        else {
            getString(R.string.permission_hint_missing)
        }
    }

    private fun setPermissionStatus(textView: android.widget.TextView, granted: Boolean) {
        textView.text = getString(if (granted) R.string.state_granted else R.string.state_denied)
        val colorRes = if (granted) R.color.state_ok else R.color.state_bad
        textView.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun updateDetail() {
        val summary = stateStore.lastSummary()
        binding.tvDetail.text = summary.ifBlank { getString(R.string.no_detail) }
    }

    private fun updateAccount() {
        val accounts = try {
            accountStore.readAccounts()
        }
        catch (_: Exception) {
            emptyList()
        }
        if (accounts.isEmpty()) {
            binding.tvAccount.text = "内置账号读取失败"
            return
        }
        val lines = accounts.map { account ->
            buildString {
                append("${account.name}（${account.id}）\n")
                append("UID：${account.uid}\n")
                if (!account.roleName.isNullOrBlank()) {
                    append("角色：${account.roleName}（${account.roleId ?: "-"}）\n")
                }
                account.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    append("手机：${maskPhone(phone)}")
                }
            }.trim()
        }
        binding.tvAccount.text = lines.joinToString("\n\n")
    }

    private fun updateHistory() {
        val records = stateStore.loadRuns().take(5)
        if (records.isEmpty()) {
            binding.rvHistory.visibility = android.view.View.GONE
            binding.tvEmptyHistory.visibility = android.view.View.VISIBLE
            binding.rvHistory.adapter = null
            return
        }
        binding.rvHistory.visibility = android.view.View.VISIBLE
        binding.tvEmptyHistory.visibility = android.view.View.GONE
        binding.rvHistory.adapter = HistoryAdapter(records) { record ->
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.history_dialog_title))
                .setMessage(record.summary)
                .setPositiveButton(getString(R.string.dialog_close), null)
                .show()
        }
    }

    private fun setRunning(running: Boolean) {
        isRunning = running
        binding.progress.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnSignNow.isEnabled = !running
        binding.btnSignNow.text = if (running) getString(R.string.status_running) else getString(R.string.btn_sign_now)
        if (running) {
            binding.viewStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.state_unknown)
            binding.tvStatus.text = getString(R.string.status_running)
            binding.tvStatusTime.visibility = android.view.View.GONE
        }
    }

    // ---------------- 交互 ----------------

    private fun startRun() {
        if (isRunning) return
        setRunning(true)
        ContextCompat.startForegroundService(
            this,
            AttendanceService.buildIntent(this, force = true, killAfter = false),
        )
    }

    private fun showTimePicker() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(settings.hour)
            .setMinute(settings.minute)
            .setTitleText(getString(R.string.setting_sign_time))
            .build()
        picker.addOnPositiveButtonClickListener {
            settings.hour = picker.hour
            settings.minute = picker.minute
            binding.tvSignTime.text = formatHourMinute(picker.hour, picker.minute)
            AlarmScheduler.scheduleFromSettings(this)
            updateNextRun()
            Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
        }
        picker.show(supportFragmentManager, "sign_time_picker")
    }

    private fun showPlatformDialog() {
        val platforms = arrayOf("qq", "wechat", "weibo")
        val labels = arrayOf("QQ", "微信", "微博")
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_share_platform))
            .setItems(labels) { _, which ->
                val value = platforms[which]
                settings.sharePlatform = value
                binding.tvSharePlatform.text = value
            }
            .show()
    }

    private fun saveTextSettings() {
        val retries = binding.etMaxRetries.text?.toString()?.trim()?.toIntOrNull() ?: settings.maxRetries
        settings.maxRetries = retries.coerceIn(1, 10)
        settings.notificationUrls = binding.etWebhook.text?.toString()?.trim() ?: ""
        binding.etMaxRetries.setText(settings.maxRetries.toString())
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
    }

    // ---------------- 工具 ----------------

    private fun formatHourMinute(hour: Int, minute: Int): String =
        String.format(Locale.CHINA, "%02d:%02d", hour, minute)

    private fun formatDateTime(timestamp: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date(timestamp))

    private fun maskPhone(phone: String): String =
        if (phone.length >= 7) "${phone.take(3)}****${phone.takeLast(4)}" else phone
}