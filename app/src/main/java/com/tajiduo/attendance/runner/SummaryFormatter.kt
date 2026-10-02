package com.tajiduo.attendance.runner

import com.tajiduo.attendance.data.AccountRunSummary
import com.tajiduo.attendance.data.CloudDurationSummary
import com.tajiduo.attendance.data.CoinTaskSummary

/** 结果文本格式化，对齐 TS 端 runner.ts 的 buildSummary / formatCoinTasks / formatCloudDuration。 */
object SummaryFormatter {

    fun buildSummary(accounts: List<AccountRunSummary>): String {
        val successCount = accounts.count { it.status == "success" }
        val failedCount = accounts.count { it.status == "failed" }
        val skippedCount = accounts.count { it.status == "skipped" }
        val lines = ArrayList<String>()
        lines.add("塔吉多每日签到结果")
        lines.add("总账号：${accounts.size}，成功：$successCount，失败：$failedCount，跳过：$skippedCount")
        lines.add("")

        for (account in accounts) {
            lines.add("${account.name}（${account.id}）：${statusLabel(account.status)}")
            account.appSignin?.let { app ->
                if (app.alreadySigned) {
                    lines.add("- APP 签到：今日已签到")
                } else {
                    lines.add("- APP 签到：获得 ${app.goldCoin} 金币，${app.exp} 经验")
                }
            }
            for (game in account.gameSignins) {
                val reward = if (game.rewardName != null && game.rewardNum != null) {
                    "，奖励 ${game.rewardName} x${game.rewardNum}"
                } else {
                    ""
                }
                val days = if (game.days != null) "，本月第 ${game.days} 天" else ""
                val signinStatus = if (game.alreadySigned) "今日已签到" else "签到成功"
                lines.add("- 游戏 ${game.gameId} / ${game.roleName}：$signinStatus$days$reward")
            }
            account.coinTasks?.let { lines.add("- ${formatCoinTasks(it)}") }
            account.cloudDuration?.let { lines.add("- ${formatCloudDuration(it)}") }
            account.error?.let { lines.add("- 失败原因：$it") }
            account.skippedReason?.let { lines.add("- 跳过原因：$it") }
            lines.add("")
        }

        return lines.joinToString("\n").trim()
    }

    private fun formatCloudDuration(cloud: CloudDurationSummary): String {
        if (cloud.status == "skipped") {
            return "云异环时长：跳过（${cloud.skippedReason ?: "未执行"}）"
        }
        if (cloud.status == "failed") {
            return "云异环时长：失败（${cloud.error ?: "未知错误"}）"
        }
        val gave = cloud.gave ?: 0
        val remained = if (cloud.remained != null) "，剩余 ${cloud.remained} 分钟" else ""
        return if (gave > 0) {
            "云异环时长：+$gave 分钟$remained"
        } else {
            "云异环时长：今日已领$remained"
        }
    }

    private fun formatCoinTasks(coin: CoinTaskSummary): String {
        val bbsSignin = if (coin.bbsSignin) "✓" else "×"
        val share = if (coin.shareDone >= coin.shareTarget) "✓" else "${coin.shareDone}/${coin.shareTarget}"
        val coinText = if (coin.todayCoin != null && coin.limitCoin != null) {
            " 今日金币${coin.todayCoin}/${coin.limitCoin}"
        } else {
            ""
        }
        return "金币任务：签到$bbsSignin 浏览${coin.browseDone}/${coin.browseTarget} " +
            "点赞${coin.likeDone}/${coin.likeTarget} 分享$share$coinText"
    }

    private fun statusLabel(status: String): String = when (status) {
        "success" -> "成功"
        "skipped" -> "跳过"
        else -> "失败"
    }
}