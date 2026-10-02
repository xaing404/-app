package com.tajiduo.attendance.notify

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 可选 webhook 通知，对齐 TS 端 notify.ts：
 * - Server 酱（sctapi.ftqq.com/xxx.send）使用表单 title/desp
 * - 其他地址使用 JSON {title, content}
 */
object NotifyWebhook {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun send(urls: List<String>, title: String, content: String): List<String> {
        val errors = ArrayList<String>()
        for (url in urls) {
            try {
                val body = if (isServerChan(url)) {
                    FormBody.Builder()
                        .add("title", title)
                        .add("desp", content)
                        .build()
                } else {
                    JSONObject()
                        .put("title", title)
                        .put("content", content)
                        .toString()
                        .toRequestBody(jsonMediaType)
                }
                val request = Request.Builder().url(url).post(body).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP 状态码 ${response.code}")
                    }
                }
            }
            catch (error: Exception) {
                errors.add("$url：${error.message}")
            }
        }
        return errors
    }

    private fun isServerChan(url: String): Boolean = try {
        val parsed = url.toHttpUrl()
        parsed.host == "sctapi.ftqq.com" && parsed.encodedPath.endsWith(".send")
    }
    catch (_: Exception) {
        false
    }
}