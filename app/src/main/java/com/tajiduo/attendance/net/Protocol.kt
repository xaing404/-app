package com.tajiduo.attendance.net

import okhttp3.HttpUrl.Companion.toHttpUrl

/** 请求签名与请求构造（对齐 TS 端 protocol.ts）。 */
object Protocol {

    const val TAYGEDO_BASE_URL = "https://bbs-api.tajiduo.com"
    const val LAOHU_BASE_URL = "https://user.laohu.com"
    const val APP_VER = "1.2.2"
    const val DS_SECRET = "pUds3dfMkl"
    const val H5_ORIGIN = "https://webstatic.tajiduo.com"

    private const val NATIVE_USER_AGENT = "Tajiduo/1.2.2 (iPhone; iOS 17.0; Scale/3.00)"
    private const val H5_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Mobile/15E148 Tajiduo/1.2.2"
    private const val NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** 统一的请求描述：url + method + headers + 表单参数（POST 时非空）。 */
    data class Request(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val form: Map<String, String>?,
    )

    /** ds = "秒级时间戳,8位随机串,md5(时间戳+随机串+版本+密钥)"。 */
    fun makeDs(timestampSeconds: Long = System.currentTimeMillis() / 1000): String {
        val nonce = makeNonce()
        val signature = Crypto.md5Hex("$timestampSeconds$nonce$APP_VER$DS_SECRET")
        return "$timestampSeconds,$nonce,$signature"
    }

    private fun makeNonce(): String {
        val sb = StringBuilder(8)
        while (sb.length < 8) {
            sb.append(NONCE_ALPHABET.random())
        }
        return sb.toString()
    }

    fun buildUrl(path: String, query: Map<String, Any?> = emptyMap()): String {
        val builder = (TAYGEDO_BASE_URL + path).toHttpUrl().newBuilder()
        for ((key, value) in query) {
            if (value != null) {
                builder.addQueryParameter(key, value.toString())
            }
        }
        return builder.build().toString()
    }

    fun buildNativeRequest(
        accessToken: String,
        uid: String,
        deviceId: String,
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: Map<String, Any?>? = null,
    ): Request {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Authorization" to accessToken,
            "appversion" to APP_VER,
            "platform" to "ios",
            "uid" to uid,
            "deviceid" to deviceId,
            "ds" to makeDs(),
            "User-Agent" to NATIVE_USER_AGENT,
        )
        val form = body?.toForm()
        if (form != null) {
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        }
        return Request(buildUrl(path, query), method, headers, form)
    }

    fun buildH5Request(
        accessToken: String,
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: Map<String, Any?>? = null,
    ): Request {
        val headers = linkedMapOf(
            "Accept" to "application/json",
            "Authorization" to accessToken,
            "Origin" to H5_ORIGIN,
            "Referer" to "$H5_ORIGIN/",
            "User-Agent" to H5_USER_AGENT,
        )
        val form = body?.toForm()
        if (form != null) {
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        }
        return Request(buildUrl(path, query), method, headers, form)
    }

    private fun Map<String, Any?>.toForm(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in this) {
            if (value != null) {
                out[key] = value.toString()
            }
        }
        return out
    }
}