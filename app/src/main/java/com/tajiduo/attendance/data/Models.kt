package com.tajiduo.attendance.data

import org.json.JSONArray
import org.json.JSONObject

/** 加密后的密码（对齐 TS 端 EncryptedPassword）。 */
data class EncryptedPassword(
    val v: Int,
    val alg: String,
    val kdf: String? = null,
    val salt: String? = null,
    val iv: String,
    val tag: String,
    val data: String,
)

/** 塔吉多账号（对齐 TS 端 TaygedoAccount）。 */
data class Account(
    val id: String,
    val name: String,
    val uid: String,
    val deviceId: String,
    var refreshToken: String,
    var accessToken: String? = null,
    var laohuToken: String? = null,
    var laohuUserId: String? = null,
    var tokenUpdatedAt: String? = null,
    var phone: String? = null,
    var openudid: String? = null,
    var vendorid: String? = null,
    var roleId: String? = null,
    var roleName: String? = null,
    var encryptedPassword: EncryptedPassword? = null,
)

data class AppSigninSummary(
    val alreadySigned: Boolean = false,
    val exp: Int? = null,
    val goldCoin: Int? = null,
)

data class GameSigninSummary(
    val gameId: String,
    val roleName: String,
    val days: Int? = null,
    val rewardName: String? = null,
    val rewardNum: Int? = null,
    val alreadySigned: Boolean = false,
)

data class CoinTaskSummary(
    val bbsSignin: Boolean = false,
    val browseDone: Int = 0,
    val browseTarget: Int = 0,
    val likeDone: Int = 0,
    val likeTarget: Int = 0,
    val shareDone: Int = 0,
    val shareTarget: Int = 0,
    val sharePlatform: String = "qq",
    val todayCoin: Int? = null,
    val limitCoin: Int? = null,
    val error: String? = null,
)

data class CloudDurationSummary(
    /** success | skipped | failed */
    val status: String,
    val gave: Int? = null,
    val remained: Int? = null,
    val error: String? = null,
    val skippedReason: String? = null,
)

data class AccountRunSummary(
    val id: String,
    val name: String,
    /** success | failed | skipped */
    val status: String,
    val appSignin: AppSigninSummary? = null,
    val gameSignins: List<GameSigninSummary> = emptyList(),
    val coinTasks: CoinTaskSummary? = null,
    val cloudDuration: CloudDurationSummary? = null,
    val error: String? = null,
    val skippedReason: String? = null,
)

/** 一次完整运行的历史记录。 */
data class RunRecord(
    val startedAt: Long,
    val finishedAt: Long,
    val force: Boolean,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val summary: String,
    val accounts: List<AccountRunSummary>,
)

data class RunAttendanceResult(
    val updatedAccounts: List<Account>,
    val summary: String,
    val startedAt: Long,
    val finishedAt: Long,
    val force: Boolean,
    val accounts: List<AccountRunSummary>,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
)

/** 账号 JSON 与运行记录的解析/序列化。 */
object Json {

    // ---------- 账号 ----------

    fun parseAccounts(json: String): List<Account> {
        val array = JSONArray(json)
        val ids = HashSet<String>()
        val accounts = ArrayList<Account>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: throw IllegalArgumentException("第 $i 个账号必须是对象")
            val id = requireString(obj, "id", i)
            require(ids.add(id)) { "账号 id 重复：$id" }
            val account = Account(
                id = id,
                name = requireString(obj, "name", id),
                uid = requireString(obj, "uid", id),
                deviceId = requireString(obj, "deviceId", id),
                refreshToken = requireString(obj, "refreshToken", id),
            )
            account.accessToken = optionalString(obj, "accessToken")
            account.openudid = optionalString(obj, "openudid")
            account.vendorid = optionalString(obj, "vendorid")
            account.laohuToken = optionalString(obj, "laohuToken")
            account.laohuUserId = optionalString(obj, "laohuUserId")
            account.tokenUpdatedAt = optionalString(obj, "tokenUpdatedAt")
            account.phone = optionalString(obj, "phone")
            account.roleId = optionalString(obj, "roleId")
            account.roleName = optionalString(obj, "roleName")
            account.encryptedPassword = parseEncryptedPassword(obj, id)
            accounts.add(account)
        }
        return accounts
    }

    fun accountsToJson(accounts: List<Account>): String {
        val array = JSONArray()
        for (account in accounts) {
            val obj = JSONObject()
            obj.put("id", account.id)
            obj.put("name", account.name)
            obj.put("uid", account.uid)
            obj.put("deviceId", account.deviceId)
            obj.put("refreshToken", account.refreshToken)
            account.accessToken?.let { obj.put("accessToken", it) }
            account.openudid?.let { obj.put("openudid", it) }
            account.vendorid?.let { obj.put("vendorid", it) }
            account.laohuToken?.let { obj.put("laohuToken", it) }
            account.laohuUserId?.let { obj.put("laohuUserId", it) }
            account.tokenUpdatedAt?.let { obj.put("tokenUpdatedAt", it) }
            account.phone?.let { obj.put("phone", it) }
            account.roleId?.let { obj.put("roleId", it) }
            account.roleName?.let { obj.put("roleName", it) }
            account.encryptedPassword?.let { ep ->
                val epObj = JSONObject()
                epObj.put("v", ep.v)
                epObj.put("alg", ep.alg)
                ep.kdf?.let { epObj.put("kdf", it) }
                ep.salt?.let { epObj.put("salt", it) }
                epObj.put("iv", ep.iv)
                epObj.put("tag", ep.tag)
                epObj.put("data", ep.data)
                obj.put("encryptedPassword", epObj)
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    private fun parseEncryptedPassword(obj: JSONObject, id: String): EncryptedPassword? {
        val value = obj.optJSONObject("encryptedPassword") ?: return null
        val v = value.optInt("v", -1)
        val alg = value.optString("alg", "")
        val kdf = value.optString("kdf", "").ifBlank { null }
        val salt = value.optString("salt", "").ifBlank { null }
        val iv = value.optString("iv", "")
        val tag = value.optString("tag", "")
        val data = value.optString("data", "")
        val valid = (v == 1 || v == 2) && alg == "AES-256-GCM" &&
            iv.isNotBlank() && tag.isNotBlank() && data.isNotBlank() &&
            (v != 2 || (kdf == "scrypt" && !salt.isNullOrBlank()))
        require(valid) { "账号 $id 的可选字段 encryptedPassword 格式无效" }
        return EncryptedPassword(v = v, alg = alg, kdf = kdf, salt = salt, iv = iv, tag = tag, data = data)
    }

    private fun requireString(obj: JSONObject, field: String, id: Any): String {
        if (!obj.has(field) || obj.isNull(field)) {
            throw IllegalArgumentException("账号 $id 缺少必填字段 $field")
        }
        val value = obj.optString(field)
        if (value.isBlank()) {
            throw IllegalArgumentException("账号 $id 缺少必填字段 $field")
        }
        return value
    }

    private fun optionalString(obj: JSONObject, field: String): String? {
        if (!obj.has(field) || obj.isNull(field)) return null
        val value = obj.optString(field)
        return value.ifBlank { null }
    }

    // ---------- 运行记录 ----------

    fun runsToJson(records: List<RunRecord>): String {
        val array = JSONArray()
        for (record in records) array.put(runToJson(record))
        return array.toString()
    }

    fun runsFromJson(json: String): List<RunRecord> {
        if (json.isBlank()) return emptyList()
        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val list = ArrayList<RunRecord>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            list.add(runFromJson(obj))
        }
        return list
    }

    fun runToJson(record: RunRecord): JSONObject {
        val obj = JSONObject()
        obj.put("startedAt", record.startedAt)
        obj.put("finishedAt", record.finishedAt)
        obj.put("force", record.force)
        obj.put("successCount", record.successCount)
        obj.put("failedCount", record.failedCount)
        obj.put("skippedCount", record.skippedCount)
        obj.put("summary", record.summary)
        val accounts = JSONArray()
        for (account in record.accounts) accounts.put(accountSummaryToJson(account))
        obj.put("accounts", accounts)
        return obj
    }

    fun runFromJson(obj: JSONObject): RunRecord {
        val accounts = ArrayList<AccountRunSummary>()
        val array = obj.optJSONArray("accounts")
        if (array != null) {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { accounts.add(accountSummaryFromJson(it)) }
            }
        }
        return RunRecord(
            startedAt = obj.optLong("startedAt"),
            finishedAt = obj.optLong("finishedAt"),
            force = obj.optBoolean("force", false),
            successCount = obj.optInt("successCount"),
            failedCount = obj.optInt("failedCount"),
            skippedCount = obj.optInt("skippedCount"),
            summary = obj.optString("summary", ""),
            accounts = accounts,
        )
    }

    private fun accountSummaryToJson(summary: AccountRunSummary): JSONObject {
        val obj = JSONObject()
        obj.put("id", summary.id)
        obj.put("name", summary.name)
        obj.put("status", summary.status)
        summary.appSignin?.let {
            obj.put("appSignin", JSONObject().apply {
                put("alreadySigned", it.alreadySigned)
                it.exp?.let { v -> put("exp", v) }
                it.goldCoin?.let { v -> put("goldCoin", v) }
            })
        }
        val games = JSONArray()
        for (game in summary.gameSignins) {
            games.put(JSONObject().apply {
                put("gameId", game.gameId)
                put("roleName", game.roleName)
                game.days?.let { v -> put("days", v) }
                game.rewardName?.let { v -> put("rewardName", v) }
                game.rewardNum?.let { v -> put("rewardNum", v) }
                put("alreadySigned", game.alreadySigned)
            })
        }
        obj.put("gameSignins", games)
        summary.coinTasks?.let { coin ->
            obj.put("coinTasks", JSONObject().apply {
                put("bbsSignin", coin.bbsSignin)
                put("browseDone", coin.browseDone)
                put("browseTarget", coin.browseTarget)
                put("likeDone", coin.likeDone)
                put("likeTarget", coin.likeTarget)
                put("shareDone", coin.shareDone)
                put("shareTarget", coin.shareTarget)
                put("sharePlatform", coin.sharePlatform)
                coin.todayCoin?.let { v -> put("todayCoin", v) }
                coin.limitCoin?.let { v -> put("limitCoin", v) }
                coin.error?.let { v -> put("error", v) }
            })
        }
        summary.cloudDuration?.let { cloud ->
            obj.put("cloudDuration", JSONObject().apply {
                put("status", cloud.status)
                cloud.gave?.let { v -> put("gave", v) }
                cloud.remained?.let { v -> put("remained", v) }
                cloud.error?.let { v -> put("error", v) }
                cloud.skippedReason?.let { v -> put("skippedReason", v) }
            })
        }
        summary.error?.let { obj.put("error", it) }
        summary.skippedReason?.let { obj.put("skippedReason", it) }
        return obj
    }

    private fun accountSummaryFromJson(obj: JSONObject): AccountRunSummary {
        val appSignin = obj.optJSONObject("appSignin")?.let {
            AppSigninSummary(
                alreadySigned = it.optBoolean("alreadySigned", false),
                exp = it.optIntOrNull("exp"),
                goldCoin = it.optIntOrNull("goldCoin"),
            )
        }
        val games = ArrayList<GameSigninSummary>()
        obj.optJSONArray("gameSignins")?.let { array ->
            for (i in 0 until array.length()) {
                val g = array.optJSONObject(i) ?: continue
                games.add(
                    GameSigninSummary(
                        gameId = g.optString("gameId", ""),
                        roleName = g.optString("roleName", ""),
                        days = g.optIntOrNull("days"),
                        rewardName = g.optString("rewardName", "").ifBlank { null },
                        rewardNum = g.optIntOrNull("rewardNum"),
                        alreadySigned = g.optBoolean("alreadySigned", false),
                    ),
                )
            }
        }
        val coinTasks = obj.optJSONObject("coinTasks")?.let {
            CoinTaskSummary(
                bbsSignin = it.optBoolean("bbsSignin", false),
                browseDone = it.optInt("browseDone"),
                browseTarget = it.optInt("browseTarget"),
                likeDone = it.optInt("likeDone"),
                likeTarget = it.optInt("likeTarget"),
                shareDone = it.optInt("shareDone"),
                shareTarget = it.optInt("shareTarget"),
                sharePlatform = it.optString("sharePlatform", "qq"),
                todayCoin = it.optIntOrNull("todayCoin"),
                limitCoin = it.optIntOrNull("limitCoin"),
                error = it.optString("error", "").ifBlank { null },
            )
        }
        val cloudDuration = obj.optJSONObject("cloudDuration")?.let {
            CloudDurationSummary(
                status = it.optString("status", "failed"),
                gave = it.optIntOrNull("gave"),
                remained = it.optIntOrNull("remained"),
                error = it.optString("error", "").ifBlank { null },
                skippedReason = it.optString("skippedReason", "").ifBlank { null },
            )
        }
        return AccountRunSummary(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            status = obj.optString("status", "failed"),
            appSignin = appSignin,
            gameSignins = games,
            coinTasks = coinTasks,
            cloudDuration = cloudDuration,
            error = obj.optString("error", "").ifBlank { null },
            skippedReason = obj.optString("skippedReason", "").ifBlank { null },
        )
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null
}