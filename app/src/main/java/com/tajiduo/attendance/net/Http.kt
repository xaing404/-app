package com.tajiduo.attendance.net

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

/** OkHttp 执行 + JSON 解析/报错工具。 */
object Http {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class Res(val code: Int, val body: String)

    fun execute(request: Protocol.Request): Res {
        val builder = Request.Builder().url(request.url)
        for ((key, value) in request.headers) {
            builder.addHeader(key, value)
        }
        if (request.method == "POST") {
            val bodyBuilder = FormBody.Builder()
            request.form?.forEach { (key, value) -> bodyBuilder.add(key, value) }
            builder.post(bodyBuilder.build())
        }
        else {
            builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            return Res(response.code, response.body?.string() ?: "")
        }
    }
}

/** JSON 读取与错误信息构造（对齐 TS 端 readJson/apiResponseError）。 */
object JsonReader {

    fun readJson(res: Http.Res, endpoint: String): JSONObject {
        val text = res.body
        if (text.isBlank()) {
            throw ApiException("$endpoint 返回了无效 JSON（HTTP ${res.code}，响应为空）")
        }
        return try {
            JSONObject(text)
        }
        catch (_: Exception) {
            throw ApiException("$endpoint 返回了无效 JSON（HTTP ${res.code}，响应：${summarize(text)}）")
        }
    }

    fun summarize(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length > 160) normalized.substring(0, 157) + "..." else normalized
    }

    fun apiResponseError(endpoint: String, res: Http.Res, data: JSONObject, fallback: String): Nothing {
        val rawMsg = if (data.has("message") && !data.isNull("message")) {
            data.optString("message")
        }
        else {
            data.optString("msg")
        }
        val msg = rawMsg.trim()
        if (msg.isNotEmpty() && !msg.equals("ok", ignoreCase = true)) {
            throw ApiException(msg)
        }
        val code = if (data.has("code")) data.opt("code").toString() else "unknown"
        val msgText = if (msg.isNotEmpty()) "，msg=$msg" else ""
        throw ApiException("$endpoint 请求失败（HTTP ${res.code}，code=$code$msgText，响应：${summarize(data.toString())}）")
    }

    /** data.code === 0 */
    fun isOkCode(data: JSONObject): Boolean = data.has("code") && data.optInt("code", Int.MIN_VALUE) == 0

    fun isHttpOk(res: Http.Res): Boolean = res.code in 200..299
}