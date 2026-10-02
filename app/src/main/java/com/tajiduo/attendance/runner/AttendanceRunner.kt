package com.tajiduo.attendance.runner

import android.util.Log
import com.tajiduo.attendance.data.Account
import com.tajiduo.attendance.data.AccountRunSummary
import com.tajiduo.attendance.data.AccountStore
import com.tajiduo.attendance.data.AppSigninSummary
import com.tajiduo.attendance.data.CloudDurationSummary
import com.tajiduo.attendance.data.CoinTaskSummary
import com.tajiduo.attendance.data.GameSigninSummary
import com.tajiduo.attendance.data.RunAttendanceResult
import com.tajiduo.attendance.data.RunRecord
import com.tajiduo.attendance.data.StateStore
import com.tajiduo.attendance.net.ApiException
import com.tajiduo.attendance.net.Crypto
import com.tajiduo.attendance.net.LaohuApi
import com.tajiduo.attendance.net.RecommendPost
import com.tajiduo.attendance.net.TaygedoApi
import com.tajiduo.attendance.net.intOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** 运行参数（来自用户设置）。 */
data class RunnerOptions(
    val force: Boolean = false,
    val coinTasks: Boolean = true,
    val cloudDuration: Boolean = true,
    val sharePlatform: String = "qq",
    val maxRetries: Int = 3,
)

/**
 * 签到执行器：`tajiduo/src/runner.ts` 的 1:1 移植。
 *
 * 流程：读取账号 → 日期去重 → runAccount（会话恢复）→ signWithSession
 * （游戏角色 → APP 签到 → 逐角色游戏签到 → 金币任务 → 云异环时长）→ 汇总。
 */
class AttendanceRunner(
    private val accountStore: AccountStore,
    private val stateStore: StateStore,
) {

    private val api = TaygedoApi()
    private val laohuApi = LaohuApi()

    suspend fun run(options: RunnerOptions): RunAttendanceResult {
        val startedAt = System.currentTimeMillis()
        val runDate = shanghaiDate(startedAt)
        val accounts = accountStore.readAccounts()
        val credentialKey = accountStore.readCredentialKey()

        val results = ArrayList<AccountRunResult>(accounts.size)
        for (account in accounts) {
            results.add(runSingleAccount(account, runDate, credentialKey, options))
        }
        results.forEach { item ->
            val state = item.summary.status
            val detail = item.summary.error ?: item.summary.skippedReason ?: ""
            Log.i(TAG, "account ${item.summary.id}: $state $detail")
        }

        val updatedAccounts = results.map { it.updatedAccount }
        if (results.any { it.shouldUpdateSecret }) {
            try {
                accountStore.writeAccounts(updatedAccounts)
            }
            catch (_: Exception) {
                // 回写失败不影响本次签到结果
            }
        }

        val summaries = results.map { it.summary }
        val summary = SummaryFormatter.buildSummary(summaries)
        val successCount = summaries.count { it.status == "success" }
        val failedCount = summaries.count { it.status == "failed" }
        val skippedCount = summaries.count { it.status == "skipped" }
        val finishedAt = System.currentTimeMillis()

        stateStore.saveSummary(summary)
        stateStore.appendRun(
            RunRecord(
                startedAt = startedAt,
                finishedAt = finishedAt,
                force = options.force,
                successCount = successCount,
                failedCount = failedCount,
                skippedCount = skippedCount,
                summary = summary,
                accounts = summaries,
            ),
        )

        return RunAttendanceResult(
            updatedAccounts = updatedAccounts,
            summary = summary,
            startedAt = startedAt,
            finishedAt = finishedAt,
            force = options.force,
            accounts = summaries,
            successCount = successCount,
            failedCount = failedCount,
            skippedCount = skippedCount,
        )
    }

    private suspend fun runSingleAccount(
        account: Account,
        runDate: String,
        credentialKey: String?,
        options: RunnerOptions,
    ): AccountRunResult {
        return try {
            if (!options.force && stateStore.isSignedToday(account.id, runDate)) {
                return AccountRunResult(
                    updatedAccount = account,
                    shouldUpdateSecret = false,
                    summary = AccountRunSummary(
                        id = account.id,
                        name = account.name,
                        status = "skipped",
                        skippedReason = "今天已成功签到",
                    ),
                )
            }
            val result = withRetries(options.maxRetries) {
                runAccount(account, credentialKey, options)
            }
            stateStore.markSigned(account.id, account.name, runDate)
            result
        }
        catch (error: Exception) {
            AccountRunResult(
                updatedAccount = account,
                shouldUpdateSecret = false,
                summary = AccountRunSummary(
                    id = account.id,
                    name = account.name,
                    status = "failed",
                    error = errorMessage(error),
                ),
            )
        }
    }

    private suspend fun runAccount(
        account: Account,
        credentialKey: String?,
        options: RunnerOptions,
    ): AccountRunResult {
        val accessToken = account.accessToken
        if (!accessToken.isNullOrBlank()) {
            return signWithRecoverableSession(account, accessToken, credentialKey, false, options)
        }
        val session = refreshOrRebuildSession(account, credentialKey)
        return signWithRecoverableSession(session.account, session.accessToken, credentialKey, true, options)
    }

    private data class Session(val account: Account, val accessToken: String)

    private fun refreshOrRebuildSession(account: Account, credentialKey: String?): Session {
        val password = resolveAccountPassword(account, credentialKey)
        val phone = account.phone
        if (!phone.isNullOrBlank() && !password.isNullOrBlank()) {
            try {
                val login = laohuApi.loginWithPassword(phone, password, account.deviceId)
                val rebuilt = api.userCenterLogin(login.token, login.userId, account.deviceId)
                val updated = withSession(
                    account,
                    accessToken = rebuilt.accessToken,
                    refreshToken = rebuilt.refreshToken,
                    uid = rebuilt.uid,
                    laohuToken = login.token,
                    laohuUserId = login.userId,
                )
                return Session(updated, rebuilt.accessToken)
            }
            catch (_: Exception) {
                // 回退到 refreshToken / 已存 laohu 凭据
            }
        }

        try {
            val refreshed = api.refreshToken(account.refreshToken, account.deviceId)
            val updated = withSession(
                account,
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken,
                uid = refreshed.uid,
            )
            return Session(updated, refreshed.accessToken)
        }
        catch (error: Exception) {
            val laohuToken = account.laohuToken
            val laohuUserId = account.laohuUserId
            if (!isRefreshRejected(error) || laohuToken.isNullOrBlank() || laohuUserId.isNullOrBlank()) {
                throw error
            }
            val rebuilt = api.userCenterLogin(laohuToken, laohuUserId, account.deviceId)
            val updated = withSession(
                account,
                accessToken = rebuilt.accessToken,
                refreshToken = rebuilt.refreshToken,
                uid = rebuilt.uid,
            )
            return Session(updated, rebuilt.accessToken)
        }
    }

    /** 无环境变量密码，使用内置 encryptedPassword + credential-key 解密作为唯一来源。 */
    private fun resolveAccountPassword(account: Account, credentialKey: String?): String? {
        val encrypted = account.encryptedPassword ?: return null
        if (credentialKey.isNullOrBlank()) return null
        return try {
            Crypto.decryptPassword(encrypted, credentialKey)
        }
        catch (_: Exception) {
            null
        }
    }

    private suspend fun signWithRecoverableSession(
        account: Account,
        accessToken: String,
        credentialKey: String?,
        shouldUpdateSecret: Boolean,
        options: RunnerOptions,
    ): AccountRunResult {
        return try {
            signWithSession(account, accessToken, credentialKey, shouldUpdateSecret, options)
        }
        catch (error: Exception) {
            if (!isAuthError(error)) {
                throw error
            }
            val session = refreshOrRebuildSession(account, credentialKey)
            signWithSession(session.account, session.accessToken, credentialKey, true, options)
        }
    }

    private suspend fun signWithSession(
        account: Account,
        accessToken: String,
        credentialKey: String?,
        shouldUpdateSecret: Boolean,
        options: RunnerOptions,
    ): AccountRunResult {
        val gameRoles = getAllGameRoles(accessToken, account.uid, account.deviceId)
        val firstRole = gameRoles.firstOrNull()
        val roleId = firstRole?.roleId ?: account.roleId

        val appSignin = signAppIdempotently(accessToken, account)
        val gameSignins = coroutineScope {
            gameRoles.map { role ->
                async { signGameRole(accessToken, account, role) }
            }.awaitAll()
        }

        var updatedAccount = account.copy()
        if (roleId != null) {
            updatedAccount.roleId = roleId
        }
        val resolvedRoleName = firstRole?.roleName ?: account.roleName
        if (resolvedRoleName != null) {
            updatedAccount.roleName = resolvedRoleName
        }

        val coinTasks = if (options.coinTasks) {
            runCoinTasks(account, accessToken, options)
        } else {
            null
        }
        val cloudResult = if (options.cloudDuration) {
            runCloudDuration(updatedAccount, credentialKey)
        } else {
            null
        }
        if (cloudResult?.updatedAccount != null) {
            updatedAccount = cloudResult.updatedAccount
        }

        return AccountRunResult(
            updatedAccount = updatedAccount,
            shouldUpdateSecret = shouldUpdateSecret || cloudResult?.shouldUpdateSecret == true,
            summary = AccountRunSummary(
                id = account.id,
                name = account.name,
                status = "success",
                appSignin = appSignin,
                gameSignins = gameSignins,
                coinTasks = coinTasks,
                cloudDuration = cloudResult?.summary,
            ),
        )
    }

    private data class RoleRef(val gameId: String, val roleId: String, val roleName: String?)

    private fun signGameRole(accessToken: String, account: Account, role: RoleRef): GameSigninSummary {
        val signin = signGameIdempotently(accessToken, role.roleId, role.gameId)
        val days = try {
            api.getSigninState(accessToken, role.gameId)
        }
        catch (_: Exception) {
            null
        }
        val reward = days?.let { day ->
            try {
                api.getSigninRewards(accessToken, role.gameId).getOrNull(day - 1)
            }
            catch (_: Exception) {
                null
            }
        }
        return GameSigninSummary(
            gameId = role.gameId,
            roleName = role.roleName ?: role.roleId,
            days = days,
            rewardName = reward?.name,
            rewardNum = reward?.num,
            alreadySigned = signin.alreadySigned,
        )
    }

    private fun signAppIdempotently(accessToken: String, account: Account): AppSigninSummary {
        return try {
            val result = api.appSignin(accessToken, account.uid, account.deviceId)
            AppSigninSummary(alreadySigned = false, exp = result.exp, goldCoin = result.goldCoin)
        }
        catch (error: Exception) {
            if (!isAlreadySignedError(error)) {
                throw error
            }
            AppSigninSummary(alreadySigned = true)
        }
    }

    private fun signGameIdempotently(accessToken: String, roleId: String, gameId: String): GameSigninMark {
        return try {
            api.gameSignin(accessToken, roleId, gameId)
            GameSigninMark(alreadySigned = false)
        }
        catch (error: Exception) {
            if (!isAlreadySignedError(error)) {
                throw error
            }
            GameSigninMark(alreadySigned = true)
        }
    }

    private fun signBbsIdempotently(accessToken: String, account: Account) {
        try {
            api.bbsSignin(accessToken, account.uid, account.deviceId)
        }
        catch (error: Exception) {
            if (!isAlreadySignedError(error)) {
                throw error
            }
        }
    }

    private suspend fun runCoinTasks(
        account: Account,
        accessToken: String,
        options: RunnerOptions,
    ): CoinTaskSummary {
        val sharePlatform = options.sharePlatform
        val tasks = api.getUserTasks(accessToken, account.uid, account.deviceId)
        val bbsTarget = remainingTaskCount(tasks, "signin_c", 1)
        val browseTarget = remainingTaskCount(tasks, "browse_post_c", 5)
        val likeTarget = remainingTaskCount(tasks, "like_post_c", 5)
        val shareTarget = remainingTaskCount(tasks, "share", 1)

        var bbsSignin = bbsTarget <= 0
        var browseDone = 0
        var likeDone = 0
        var shareDone = 0
        val errors = ArrayList<String>()

        if (bbsTarget > 0) {
            signBbsIdempotently(accessToken, account)
            bbsSignin = true
        }

        val posts = if (browseTarget > 0 || likeTarget > 0 || shareTarget > 0) {
            api.getRecommendPostList(accessToken, account.uid, account.deviceId, 20, 1)
        } else {
            emptyList()
        }
        val browsedPosts = ArrayList<RecommendPost>()

        for (post in posts) {
            if (browseDone >= browseTarget) break
            delay(randomDelay(700, 1500))
            try {
                val fullPost = api.getPostFull(accessToken, account.uid, account.deviceId, post.postId)
                browsedPosts.add(fullPost)
                browseDone++
            }
            catch (error: Exception) {
                errors.add("浏览帖子 ${post.postId} 失败：${errorMessage(error)}")
            }
        }

        val likeCandidates = browsedPosts + posts
        val seenPostIds = HashSet<String>()
        for (post in likeCandidates) {
            if (likeDone >= likeTarget) break
            if (!seenPostIds.add(post.postId)) continue
            if (post.liked == true) continue
            delay(randomDelay(500, 1000))
            try {
                api.likePost(accessToken, account.uid, account.deviceId, post.postId)
                likeDone++
            }
            catch (error: Exception) {
                errors.add("点赞帖子 ${post.postId} 失败：${errorMessage(error)}")
            }
        }

        val sharePost = browsedPosts.firstOrNull() ?: posts.firstOrNull()
        if (shareTarget > 0 && sharePost != null) {
            try {
                api.sharePost(accessToken, account.uid, account.deviceId, sharePost.postId, sharePlatform)
                shareDone = 1
            }
            catch (error: Exception) {
                errors.add("分享帖子 ${sharePost.postId} 失败：${errorMessage(error)}")
            }
        }

        val coinState = try {
            api.getUserCoinTaskState(accessToken)
        }
        catch (_: Exception) {
            null
        }

        return CoinTaskSummary(
            bbsSignin = bbsSignin,
            browseDone = browseDone,
            browseTarget = browseTarget,
            likeDone = likeDone,
            likeTarget = likeTarget,
            shareDone = shareDone,
            shareTarget = shareTarget,
            sharePlatform = sharePlatform,
            todayCoin = coinState?.intOrNull("todayCoin"),
            limitCoin = coinState?.intOrNull("limitCoin"),
            error = if (errors.isEmpty()) null else errors.joinToString("；"),
        )
    }

    private data class CloudResult(
        val summary: CloudDurationSummary,
        val updatedAccount: Account? = null,
        val shouldUpdateSecret: Boolean = false,
    )

    private fun runCloudDuration(account: Account, credentialKey: String?): CloudResult {
        var laohuToken = account.laohuToken
        var laohuUserId = account.laohuUserId
        var updatedAccount: Account? = null

        if (laohuToken.isNullOrBlank() || laohuUserId.isNullOrBlank()) {
            val password = resolveAccountPassword(account, credentialKey)
            val phone = account.phone
            if (phone.isNullOrBlank() || password.isNullOrBlank()) {
                return CloudResult(
                    summary = CloudDurationSummary(
                        status = "skipped",
                        skippedReason = "账号缺少 laohuToken/laohuUserId",
                    ),
                )
            }
            try {
                val login = laohuApi.loginWithPassword(phone, password, account.deviceId)
                laohuToken = login.token
                laohuUserId = login.userId
                updatedAccount = account.copy(
                    laohuToken = laohuToken,
                    laohuUserId = laohuUserId,
                    tokenUpdatedAt = shanghaiDateTime(),
                )
            }
            catch (error: Exception) {
                return CloudResult(
                    summary = CloudDurationSummary(
                        status = "failed",
                        error = "老虎登录失败：${errorMessage(error)}",
                    ),
                )
            }
        }

        if (laohuToken.isNullOrBlank() || laohuUserId.isNullOrBlank()) {
            return CloudResult(
                summary = CloudDurationSummary(
                    status = "skipped",
                    skippedReason = "账号缺少 laohuToken/laohuUserId",
                ),
            )
        }

        return try {
            val result = api.cloudGetUserInfo(laohuToken, laohuUserId, account.deviceId)
            CloudResult(
                summary = CloudDurationSummary(
                    status = "success",
                    gave = result.gave,
                    remained = result.remained,
                ),
                updatedAccount = updatedAccount,
                shouldUpdateSecret = updatedAccount != null,
            )
        }
        catch (error: Exception) {
            CloudResult(
                summary = CloudDurationSummary(
                    status = "failed",
                    error = errorMessage(error),
                ),
                updatedAccount = updatedAccount,
                shouldUpdateSecret = updatedAccount != null,
            )
        }
    }

    private fun getAllGameRoles(accessToken: String, uid: String, deviceId: String): List<RoleRef> {
        val roles = ArrayList<RoleRef>()
        val seenRoleIds = HashSet<String>()

        for (gameId in TAYGEDO_GAME_IDS) {
            val gameRoles = api.getGameRoles(accessToken, uid, deviceId, gameId)
            for (role in gameRoles) {
                if (role.roleId.isBlank() || !seenRoleIds.add(role.roleId)) continue
                roles.add(RoleRef(gameId = gameId, roleId = role.roleId, roleName = role.roleName))
            }
        }

        if (roles.isEmpty()) {
            val cards = api.getGameRecordCards(accessToken, uid, deviceId)
            for (card in cards) {
                val roleId = card.roleId ?: continue
                if (!seenRoleIds.add(roleId)) continue
                roles.add(RoleRef(gameId = card.gameId, roleId = roleId, roleName = card.roleName ?: card.gameName))
            }
        }

        return roles
    }

    private fun withSession(
        account: Account,
        accessToken: String,
        refreshToken: String,
        uid: String?,
        laohuToken: String? = null,
        laohuUserId: String? = null,
    ): Account {
        return account.copy(
            uid = uid ?: account.uid,
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenUpdatedAt = shanghaiDateTime(),
            laohuToken = laohuToken ?: account.laohuToken,
            laohuUserId = laohuUserId ?: account.laohuUserId,
        )
    }

    private fun remainingTaskCount(
        tasks: List<com.tajiduo.attendance.net.CoinTask>,
        code: String,
        fallback: Int,
    ): Int {
        val task = tasks.firstOrNull { it.code == code } ?: return fallback
        return maxOf(0, task.limitTimes - task.completeTimes)
    }

    private suspend fun <T> withRetries(maxAttempts: Int, operation: suspend () -> T): T {
        val attempts = maxOf(1, maxAttempts)
        var lastError: Exception? = null
        for (attempt in 1..attempts) {
            try {
                return operation()
            }
            catch (error: Exception) {
                lastError = error
                if (attempt < attempts) {
                    delay(minOf(30_000L, 1000L * (1L shl (attempt - 1))))
                }
            }
        }
        throw lastError ?: ApiException("重试失败")
    }

    private data class AccountRunResult(
        val updatedAccount: Account,
        val shouldUpdateSecret: Boolean,
        val summary: AccountRunSummary,
    )

    private data class GameSigninMark(val alreadySigned: Boolean)

    private companion object {
        const val TAG = "TajiduoAttendance"
        val TAYGEDO_GAME_IDS = listOf("1256", "1257", "1289")
        val ALREADY_SIGNED_REGEX = Regex("已.*签到|签到.*过|重复签到|already.*sign", RegexOption.IGNORE_CASE)
        val AUTH_ERROR_REGEX = Regex(
            "AUTH_EXPIRED|HTTP 40[123]|登录|token|未授权|请先|过期|失效|invalid_token|invalid request",
            RegexOption.IGNORE_CASE,
        )

        fun isAlreadySignedError(error: Throwable): Boolean =
            ALREADY_SIGNED_REGEX.containsMatchIn(errorMessage(error))

        fun isAuthError(error: Throwable): Boolean =
            AUTH_ERROR_REGEX.containsMatchIn(errorMessage(error))

        fun isRefreshRejected(error: Throwable): Boolean =
            errorMessage(error).contains("REFRESH_REJECTED_402")

        fun errorMessage(error: Throwable): String = error.message ?: error.toString()

        fun randomDelay(min: Int, max: Int): Long =
            (min + Random.nextInt(max - min + 1)).toLong()

        fun shanghaiDateTime(date: Date = Date()): String =
            formatter("yyyy-MM-dd'T'HH:mm:ss'+08:00'").format(date)

        fun shanghaiDate(timestamp: Long): String =
            formatter("yyyy-MM-dd").format(Date(timestamp))

        private fun formatter(pattern: String): SimpleDateFormat =
            SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
    }
}