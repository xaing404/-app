package com.tajiduo.attendance.net

import org.json.JSONArray
import org.json.JSONObject

data class RefreshResult(val accessToken: String, val refreshToken: String, val uid: String?)
data class UserCenterResult(val accessToken: String, val refreshToken: String, val uid: String)
data class GameRole(val roleId: String, val roleName: String?)
data class GameRecordCard(val gameId: String, val roleId: String?, val roleName: String?, val gameName: String?)
data class CoinTask(val code: String, val completeTimes: Int, val limitTimes: Int)
data class RecommendPost(val postId: String, val liked: Boolean?)
data class Reward(val name: String, val num: Int)
data class AppSigninResult(val exp: Int, val goldCoin: Int)
data class CloudDurationResult(val gave: Int, val remained: Int?)

/** 塔吉多接口集合，对齐 TS 端 api.ts（登录部分见 LaohuApi）。 */
class TaygedoApi {

    // ---------------- 会话 ----------------

    fun refreshToken(refreshToken: String, deviceId: String): RefreshResult {
        val headers = linkedMapOf(
            "authorization" to refreshToken,
            "deviceid" to deviceId,
            "appversion" to Protocol.APP_VER,
            "platform" to "ios",
            "ds" to Protocol.makeDs(),
            "Content-Type" to "application/x-www-form-urlencoded",
            "User-Agent" to "okhttp/4.12.0",
        )
        val res = Http.execute(
            Protocol.Request(Protocol.TAYGEDO_BASE_URL + "/usercenter/api/refreshToken", "POST", headers, emptyMap()),
        )
        if (res.code == 402) {
            throw ApiException("REFRESH_REJECTED_402: refreshToken 已失效，请重新登录")
        }
        val data = JsonReader.readJson(res, "refreshToken")
        val payload = data.optJSONObject("data")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || payload == null ||
            payload.optString("accessToken").isBlank() || payload.optString("refreshToken").isBlank()
        ) {
            JsonReader.apiResponseError("refreshToken", res, data, "刷新登录令牌请求失败")
        }
        val resolved = requireNotNull(payload)
        return RefreshResult(
            resolved.optString("accessToken"),
            resolved.optString("refreshToken"),
            resolved.stringOrNull("uid"),
        )
    }

    fun userCenterLogin(token: String, userId: String, deviceId: String): UserCenterResult {
        val headers = linkedMapOf(
            "platform" to "ios",
            "deviceid" to deviceId,
            "authorization" to "",
            "appversion" to Protocol.APP_VER,
            "uid" to "10000000",
            "ds" to Protocol.makeDs(),
            "Content-Type" to "application/x-www-form-urlencoded",
            "User-Agent" to "okhttp/4.12.0",
        )
        val form = linkedMapOf(
            "token" to token,
            "userIdentity" to userId,
            "appId" to "10551",
        )
        val res = Http.execute(
            Protocol.Request(Protocol.TAYGEDO_BASE_URL + "/usercenter/api/login", "POST", headers, form),
        )
        val data = JsonReader.readJson(res, "userCenterLogin")
        val payload = data.optJSONObject("data")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || payload == null ||
            payload.optString("accessToken").isBlank() || payload.optString("refreshToken").isBlank() ||
            !payload.has("uid") || payload.isNull("uid")
        ) {
            JsonReader.apiResponseError("userCenterLogin", res, data, "塔吉多用户中心登录请求失败")
        }
        val resolved = requireNotNull(payload)
        return UserCenterResult(
            resolved.optString("accessToken"),
            resolved.optString("refreshToken"),
            resolved.opt("uid").toString(),
        )
    }

    // ---------------- 游戏角色 ----------------

    fun getGameRoles(accessToken: String, uid: String, deviceId: String, gameId: String = "1256"): List<GameRole> {
        val url = Protocol.buildUrl("/usercenter/api/v2/getGameRoles", mapOf("gameId" to gameId))
        val headers = linkedMapOf(
            "platform" to "android",
            "authorization" to accessToken,
            "uid" to uid,
            "deviceid" to deviceId,
            "appversion" to "1.1.0",
            "User-Agent" to "okhttp/4.12.0",
        )
        val res = Http.execute(Protocol.Request(url, "GET", headers, null))
        val data = JsonReader.readJson(res, "getGameRoles")
        val roles = data.optJSONObject("data")?.optJSONArray("roles")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || roles == null) {
            JsonReader.apiResponseError("getGameRoles", res, data, "获取游戏角色请求失败")
        }
        val out = ArrayList<GameRole>()
        val array = requireNotNull(roles)
        for (i in 0 until array.length()) {
            val role = array.optJSONObject(i) ?: continue
            if (!role.has("roleId") || role.isNull("roleId")) continue
            out.add(GameRole(role.opt("roleId").toString(), role.stringOrNull("roleName")))
        }
        return out
    }

    fun getGameRecordCards(accessToken: String, uid: String, deviceId: String): List<GameRecordCard> {
        val request = Protocol.buildNativeRequest(
            accessToken, uid, deviceId, "GET", "/apihub/api/getGameRecordCard", mapOf("uid" to uid),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "getGameRecordCards")
        val raw = data.opt("data")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || raw !is JSONArray) {
            JsonReader.apiResponseError("getGameRecordCards", res, data, "获取游戏角色卡请求失败")
        }
        val out = ArrayList<GameRecordCard>()
        val array = raw as JSONArray
        for (i in 0 until array.length()) {
            val card = array.optJSONObject(i) ?: continue
            val gameId = card.stringOrNull("gameId") ?: continue
            val bind = card.optJSONObject("bindRoleInfo")
            val roleId = bind?.let { if (it.has("roleId") && !it.isNull("roleId")) it.opt("roleId").toString() else null }
            out.add(
                GameRecordCard(
                    gameId = gameId,
                    roleId = roleId,
                    roleName = bind?.stringOrNull("roleName"),
                    gameName = card.stringOrNull("gameName"),
                ),
            )
        }
        return out
    }

    // ---------------- 签到 ----------------

    fun appSignin(accessToken: String, uid: String, deviceId: String): AppSigninResult {
        val headers = linkedMapOf(
            "authorization" to accessToken,
            "uid" to uid,
            "deviceid" to deviceId,
            "appversion" to Protocol.APP_VER,
            "platform" to "ios",
            "ds" to Protocol.makeDs(),
            "Content-Type" to "application/x-www-form-urlencoded",
            "User-Agent" to "okhttp/4.12.0",
        )
        val form = linkedMapOf("communityId" to "1")
        val res = Http.execute(
            Protocol.Request(Protocol.TAYGEDO_BASE_URL + "/apihub/api/signin", "POST", headers, form),
        )
        val data = JsonReader.readJson(res, "appSignin")
        val payload = data.optJSONObject("data")
        val exp = payload?.intOrNull("exp")
        val goldCoin = payload?.intOrNull("goldCoin")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || exp == null || goldCoin == null) {
            JsonReader.apiResponseError("appSignin", res, data, "APP 签到请求失败")
        }
        return AppSigninResult(exp = requireNotNull(exp), goldCoin = requireNotNull(goldCoin))
    }

    fun getSigninState(accessToken: String, gameId: String = "1256"): Int {
        val request = Protocol.buildH5Request(
            accessToken, "GET", "/apihub/awapi/signin/state", mapOf("gameId" to gameId),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "getSigninState")
        val days = data.optJSONObject("data")?.intOrNull("days")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || days == null) {
            JsonReader.apiResponseError("getSigninState", res, data, "获取游戏签到状态请求失败")
        }
        return requireNotNull(days)
    }

    fun getSigninRewards(accessToken: String, gameId: String = "1256"): List<Reward> {
        val request = Protocol.buildH5Request(
            accessToken, "GET", "/apihub/awapi/sign/rewards", mapOf("gameId" to gameId),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "getSigninRewards")
        val raw = data.opt("data")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || raw !is JSONArray) {
            JsonReader.apiResponseError("getSigninRewards", res, data, "获取游戏签到奖励请求失败")
        }
        val out = ArrayList<Reward>()
        val array = raw as JSONArray
        for (i in 0 until array.length()) {
            val reward = array.optJSONObject(i) ?: continue
            out.add(Reward(reward.optString("name", ""), reward.optInt("num")))
        }
        return out
    }

    fun gameSignin(accessToken: String, roleId: String, gameId: String = "1256") {
        val request = Protocol.buildH5Request(
            accessToken, "POST", "/apihub/awapi/sign",
            body = mapOf("roleId" to roleId, "gameId" to gameId),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "gameSignin")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data)) {
            JsonReader.apiResponseError("gameSignin", res, data, "游戏签到请求失败")
        }
    }

    // ---------------- 金币任务 ----------------

    fun getUserTasks(accessToken: String, uid: String, deviceId: String): List<CoinTask> {
        val request = Protocol.buildNativeRequest(
            accessToken, uid, deviceId, "GET", "/apihub/api/getUserTasks", mapOf("gid" to 1),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "getUserTasks")
        val list = data.optJSONObject("data")?.optJSONArray("task_list1")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || list == null) {
            JsonReader.apiResponseError("getUserTasks", res, data, "获取金币任务状态请求失败")
        }
        val out = ArrayList<CoinTask>()
        val array = requireNotNull(list)
        for (i in 0 until array.length()) {
            val task = array.optJSONObject(i) ?: continue
            val code = task.stringOrNull("code") ?: task.stringOrNull("taskKey") ?: ""
            if (code.isBlank()) continue
            out.add(
                CoinTask(
                    code = code,
                    completeTimes = toNumber(task.opt("completeTimes")),
                    limitTimes = toNumber(task.opt("limitTimes")),
                ),
            )
        }
        return out
    }

    fun bbsSignin(accessToken: String, uid: String, deviceId: String) {
        val request = Protocol.buildNativeRequest(
            accessToken, uid, deviceId, "POST", "/apihub/api/signin", body = mapOf("communityId" to 2),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "bbsSignin")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data)) {
            JsonReader.apiResponseError("bbsSignin", res, data, "BBS 金币签到请求失败")
        }
    }

    fun getRecommendPostList(
        accessToken: String,
        uid: String,
        deviceId: String,
        count: Int = 20,
        page: Int = 1,
    ): List<RecommendPost> {
        val request = Protocol.buildNativeRequest(
            accessToken, uid, deviceId, "GET", "/bbs/api/getRecommendPostList",
            mapOf("communityId" to 2, "count" to count, "page" to page),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "getRecommendPostList")
        val rawData = data.opt("data")
        val raw: JSONArray? = when {
            rawData is JSONArray -> rawData
            rawData is JSONObject -> rawData.optJSONArray("list") ?: rawData.optJSONArray("posts")
            else -> null
        }
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || raw == null) {
            JsonReader.apiResponseError("getRecommendPostList", res, data, "获取推荐帖子列表请求失败")
        }
        val array = requireNotNull(raw)
        val out = ArrayList<RecommendPost>()
        for (i in 0 until array.length()) {
            val post = array.optJSONObject(i) ?: continue
            toRecommendPost(post)?.let { out.add(it) }
        }
        return out
    }

    fun getPostFull(accessToken: String, uid: String, deviceId: String, postId: String): RecommendPost {
        val request = Protocol.buildNativeRequest(
            accessToken, uid, deviceId, "GET", "/bbs/api/getPostFull", mapOf("postId" to postId),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "getPostFull")
        val post = data.optJSONObject("data")?.let { toPostFull(it, postId) }
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || post == null) {
            JsonReader.apiResponseError("getPostFull", res, data, "获取帖子详情请求失败")
        }
        return requireNotNull(post)
    }

    fun likePost(accessToken: String, uid: String, deviceId: String, postId: String) {
        val request = Protocol.buildNativeRequest(
            accessToken, uid, deviceId, "POST", "/bbs/api/post/like", body = mapOf("postId" to postId),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "likePost")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data)) {
            JsonReader.apiResponseError("likePost", res, data, "点赞帖子请求失败")
        }
    }

    fun sharePost(accessToken: String, uid: String, deviceId: String, postId: String, platform: String) {
        val request = Protocol.buildNativeRequest(
            accessToken, uid, deviceId, "POST", "/bbs/api/post/share",
            body = mapOf("platform" to platform, "postId" to postId),
        )
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "sharePost")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data)) {
            JsonReader.apiResponseError("sharePost", res, data, "分享帖子请求失败")
        }
    }

    fun getUserCoinTaskState(accessToken: String): JSONObject {
        val request = Protocol.buildH5Request(accessToken, "GET", "/apihub/api/getUserCoinTaskState")
        val res = Http.execute(request)
        val data = JsonReader.readJson(res, "getUserCoinTaskState")
        val state = data.optJSONObject("data")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || state == null) {
            JsonReader.apiResponseError("getUserCoinTaskState", res, data, "获取金币状态请求失败")
        }
        return requireNotNull(state)
    }

    // ---------------- 云异环时长 ----------------

    fun cloudGetUserInfo(laohuToken: String, laohuUserId: String, deviceId: String): CloudDurationResult {
        val params = linkedMapOf(
            "appId" to CLOUD_APP_ID,
            "deviceId" to deviceId,
            "deviceType" to "Pixel 8",
            "deviceName" to "Pixel 8",
            "t" to (System.currentTimeMillis() / 1000).toString(),
            "channelId" to CLOUD_CHANNEL_ID,
            "deviceModel" to "Pixel 8",
            "deviceSys" to "14",
            "version" to CLOUD_APP_VERSION,
            "sdkVersion" to CLOUD_SDK_VERSION,
            "network" to "wifi",
            "bid" to CLOUD_BID,
            "provider" to "0",
            "idfa" to "",
            "userId" to laohuUserId,
            "token" to laohuToken,
        )
        val form = LinkedHashMap(params)
        form["sign"] = cloudSign(params)
        val headers = linkedMapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "User-Agent" to "okhttp/3.12.1",
            "Host" to "user.laohu.com",
        )
        val res = Http.execute(
            Protocol.Request(Protocol.LAOHU_BASE_URL + "/cloud/game/getUserInfo", "POST", headers, form),
        )
        val data = JsonReader.readJson(res, "cloudGetUserInfo")
        val result = data.optJSONObject("result")
        if (!JsonReader.isHttpOk(res) || !JsonReader.isOkCode(data) || result == null) {
            JsonReader.apiResponseError("cloudGetUserInfo", res, data, "云异环时长请求失败")
        }
        val resolved = requireNotNull(result)
        val gave = resolved.intOrNull("perDayFirstLoginGiveDuration") ?: 0
        return CloudDurationResult(gave = gave, remained = resolved.intOrNull("remainedDuration"))
    }

    private fun cloudSign(data: Map<String, String>): String {
        val values = data.keys.sorted().joinToString("") { data[it] ?: "" }
        return Crypto.md5Hex(values + CLOUD_APP_KEY)
    }

    private companion object {
        const val CLOUD_APP_ID = "10597"
        const val CLOUD_APP_KEY = "f1b7f11fc3774f898e387368cce4da04"
        const val CLOUD_CHANNEL_ID = "9"
        const val CLOUD_BID = "com.pwrd.cloud.yh.laohu"
        const val CLOUD_SDK_VERSION = "1.34.0"
        const val CLOUD_APP_VERSION = "1.1.0"

        fun toNumber(value: Any?): Int = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: 0
            else -> 0
        }
    }
}

private fun toRecommendPost(value: JSONObject): RecommendPost? {
    val postId = when {
        value.has("postId") && !value.isNull("postId") -> value.opt("postId").toString()
        value.has("id") && !value.isNull("id") -> value.opt("id").toString()
        else -> null
    } ?: return null
    val selfOperation = value.optJSONObject("selfOperation")
    val liked = selfOperation?.let {
        if (it.has("liked") && it.opt("liked") is Boolean) it.optBoolean("liked") else null
    }
    return RecommendPost(postId = postId, liked = liked)
}

private fun toPostFull(value: JSONObject, fallbackPostId: String): RecommendPost? {
    toRecommendPost(value)?.let { return it }
    val post = value.optJSONObject("post") ?: return null
    val merged = JSONObject()
    merged.put("postId", fallbackPostId)
    if (value.has("selfOperation")) {
        merged.put("selfOperation", value.opt("selfOperation"))
    }
    for (key in post.keys()) {
        merged.put(key, post.get(key))
    }
    return toRecommendPost(merged)
}