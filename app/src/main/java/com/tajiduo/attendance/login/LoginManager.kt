package com.tajiduo.attendance.login

import android.util.Log
import com.tajiduo.attendance.data.Account
import com.tajiduo.attendance.data.AccountStore
import com.tajiduo.attendance.net.LaohuApi
import com.tajiduo.attendance.net.TaygedoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 账号登录：短信验证码登录 → 换取塔吉多会话 → 写入本地账号。
 *
 * 流程与原 TS 项目一致：
 *   1. sendCaptcha(手机号)                  发送短信验证码
 *   2. loginWithCaptcha(手机号, 验证码)      得到 laohuToken / laohuUserId
 *   3. userCenterLogin(laohuToken, 用户ID)   得到 accessToken / refreshToken / uid
 *
 * 实例为「一次登录会话」：发送验证码与登录必须使用同一个 deviceId，
 * 因此由调用方在打开登录界面时创建、关闭时丢弃。
 */
class LoginManager(private val accountStore: AccountStore) {

    private val laohuApi = LaohuApi()
    private val api = TaygedoApi()

    /** 本次登录会话使用的设备标识，首次使用时确定并复用。 */
    private var deviceId: String? = null

    /** 第 1 步：发送短信验证码。 */
    suspend fun sendCaptcha(phone: String) = withContext(Dispatchers.IO) {
        laohuApi.sendCaptcha(phone, resolveDeviceId(phone))
    }

    /** 第 2、3 步：验证码登录，成功后把账号写入本地存储。 */
    suspend fun login(phone: String, captcha: String): Account = withContext(Dispatchers.IO) {
        val id = resolveDeviceId(phone)
        val laohu = laohuApi.loginWithCaptcha(phone, captcha, id)
        val session = api.userCenterLogin(laohu.token, laohu.userId, id)

        val account = Account(
            id = "acc_${session.uid}",
            name = "账号 ${maskPhone(phone)}",
            uid = session.uid,
            deviceId = id,
            refreshToken = session.refreshToken,
            accessToken = session.accessToken,
            laohuToken = laohu.token,
            laohuUserId = laohu.userId,
            tokenUpdatedAt = shanghaiDateTime(),
            phone = phone,
        )
        save(account)
        Log.i(TAG, "login succeeded: uid=${session.uid}")
        account
    }

    /**
     * 写入账号：覆盖同 uid 的旧记录，并清理模板里未配置凭据的占位账号，
     * 其余账号保留（支持多账号）。
     */
    private fun save(account: Account) {
        val kept = accountStore.readAccounts()
            .filter { !it.accessToken.isNullOrBlank() || !it.refreshToken.isNullOrBlank() }
            .filter { it.uid != account.uid }
        accountStore.writeAccounts(kept + account)
    }

    /** 同一手机号已登录过则复用其 deviceId，避免重复登录导致设备标识漂移。 */
    private fun resolveDeviceId(phone: String): String {
        deviceId?.let { return it }
        val reused = accountStore.readAccounts()
            .firstOrNull { it.phone == phone && it.deviceId.isNotBlank() }
            ?.deviceId
        val resolved = reused ?: randomDeviceId()
        deviceId = resolved
        return resolved
    }

    /** 生成 40 位十六进制设备标识（与原账号中的格式一致）。 */
    private fun randomDeviceId(): String {
        val bytes = ByteArray(20)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun maskPhone(phone: String): String =
        if (phone.length >= 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone

    private fun shanghaiDateTime(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+08:00", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
            .format(Date())

    private companion object {
        const val TAG = "TajiduoAttendance"
    }
}