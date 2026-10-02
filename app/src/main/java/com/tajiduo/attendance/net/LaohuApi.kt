package com.tajiduo.attendance.net

import org.json.JSONObject

data class LaohuLoginResult(val token: String, val userId: String)

/** laohu（老虎）登录相关接口，对齐 TS 端 api.ts 的登录部分。 */
class LaohuApi {

    fun sendCaptcha(phone: String, deviceId: String) {
        val data = linkedMapOf(
            "deviceType" to "LGE-AN10",
            "type" to "16",
            "deviceId" to deviceId,
            "deviceName" to "LGE-AN10",
            "versionCode" to "1",
            "t" to (System.currentTimeMillis() / 1000).toString(),
            "areaCodeId" to "1",
            "appId" to "10550",
            "deviceSys" to "12",
            "cellphone" to phone,
            "deviceModel" to "LGE-AN10",
            "sdkVersion" to "4.129.0",
            "bid" to "com.pwrd.htassistant",
            "channelId" to "1",
        )
        postSigned("/m/newApi/sendPhoneCaptchaWithOutLogin", data, "sendCaptcha", "发送短信验证码请求失败")
    }

    fun checkCaptcha(phone: String, captcha: String, deviceId: String) {
        val data = linkedMapOf(
            "deviceType" to "LGE-AN10",
            "deviceId" to deviceId,
            "deviceName" to "LGE-AN10",
            "t" to (System.currentTimeMillis() / 1000).toString(),
            "areaCodeId" to "1",
            "appId" to "10550",
            "deviceSys" to "12",
            "cellphone" to phone,
            "captcha" to captcha,
            "deviceModel" to "LGE-AN10",
            "sdkVersion" to "4.129.0",
            "bid" to "com.pwrd.htassistant",
            "channelId" to "1",
        )
        postSigned("/m/newApi/checkPhoneCaptchaWithOutLogin", data, "checkCaptcha", "校验短信验证码请求失败")
    }

    fun loginWithCaptcha(phone: String, captcha: String, deviceId: String): LaohuLoginResult {
        val data = linkedMapOf(
            "deviceType" to "LGE-AN10",
            "idfa" to "",
            "sign" to "",
            "adm" to "",
            "type" to "16",
            "deviceId" to deviceId,
            "version" to "1",
            "deviceName" to "LGE-AN10",
            "mac" to "",
            "t" to System.currentTimeMillis().toString(),
            "areaCodeId" to "1",
            "captcha" to Crypto.aes128EcbBase64(captcha, LAOHU_SECRET),
            "appId" to "10550",
            "deviceSys" to "12",
            "cellphone" to Crypto.aes128EcbBase64(phone, LAOHU_SECRET),
            "deviceModel" to "LGE-AN10",
            "sdkVersion" to "4.129.0",
            "bid" to "com.pwrd.htassistant",
            "channelId" to "1",
        )
        return postLogin("/openApi/sms/new/login", data, "loginWithCaptcha", "短信验证码登录请求失败")
    }

    fun loginWithPassword(phone: String, password: String, deviceId: String): LaohuLoginResult {
        val data = linkedMapOf(
            "deviceType" to "LGE-AN10",
            "idfa" to "",
            "sign" to "",
            "adm" to "",
            "deviceId" to deviceId,
            "version" to "1",
            "deviceName" to "LGE-AN10",
            "mac" to "",
            "t" to System.currentTimeMillis().toString(),
            "appId" to "10550",
            "deviceSys" to "12",
            "username" to Crypto.aes128EcbBase64(phone, LAOHU_SECRET),
            "password" to Crypto.aes128EcbBase64(password, LAOHU_SECRET),
            "deviceModel" to "LGE-AN10",
            "sdkVersion" to "4.129.0",
            "bid" to "com.pwrd.htassistant",
            "channelId" to "1",
        )
        return postLogin("/openApi/secureLogin", data, "loginWithPassword", "账号密码登录请求失败")
    }

    private fun postSigned(path: String, data: LinkedHashMap<String, String>, endpoint: String, fallback: String) {
        val form = LinkedHashMap(data)
        form["sign"] = sign(data)
        val res = execute(path, form)
        val json = JsonReader.readJson(res, endpoint)
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(json)) {
            JsonReader.apiResponseError(endpoint, res, json, fallback)
        }
    }

    private fun postLogin(
        path: String,
        data: LinkedHashMap<String, String>,
        endpoint: String,
        fallback: String,
    ): LaohuLoginResult {
        val form = LinkedHashMap(data)
        form["sign"] = sign(data)
        val res = execute(path, form)
        val json = JsonReader.readJson(res, endpoint)
        val result = json.optJSONObject("result")
        val token = result?.optString("token", "") ?: ""
        val userId = result?.let { if (it.has("userId") && !it.isNull("userId")) it.opt("userId").toString() else "" } ?: ""
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(json) || token.isBlank() || userId.isBlank()) {
            JsonReader.apiResponseError(endpoint, res, json, fallback)
        }
        return LaohuLoginResult(token, userId)
    }

    private fun execute(path: String, form: Map<String, String>): Http.Res {
        val headers = linkedMapOf(
            "platform" to "android",
            "Content-Type" to "application/x-www-form-urlencoded",
        )
        return Http.execute(Protocol.Request(Protocol.LAOHU_BASE_URL + path, "POST", headers, form))
    }

    /** sign = md5(参数值按 key 升序拼接 + LAOHU_SECRET)。 */
    private fun sign(data: Map<String, String>): String {
        val values = data.keys.sorted().joinToString("") { data[it] ?: "" }
        return Crypto.md5Hex(values + LAOHU_SECRET)
    }

    private companion object {
        const val LAOHU_SECRET = "89155cc4e8634ec5b1b6364013b23e3e"
    }
}

/** 便于共享 JSONObject 取值。 */
internal fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}

internal fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key)
    return value.ifBlank { null }
}