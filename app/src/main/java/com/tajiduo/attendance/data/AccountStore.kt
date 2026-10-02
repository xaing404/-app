package com.tajiduo.attendance.data

import android.content.Context
import java.io.File

/**
 * 账号与密钥存储：
 * 首次启动时把 assets 中的 accounts.json / credential-key 释放到应用私有目录，
 * 之后 token 刷新会回写到私有目录，不再读取 assets。
 */
class AccountStore(private val context: Context) {

    private val accountsFile = File(context.filesDir, "accounts.json")
    private val keyFile = File(context.filesDir, "credential-key")

    /** 首次启动释放内置账号与密钥。 */
    fun ensureSeeded() {
        if (!accountsFile.exists()) {
            copyAsset("accounts.json", accountsFile)
        }
        if (!keyFile.exists()) {
            copyAsset("credential-key", keyFile)
        }
    }

    fun readAccountsRaw(): String = accountsFile.readText(Charsets.UTF_8)

    fun readAccounts(): List<Account> = Json.parseAccounts(readAccountsRaw())

    fun writeAccounts(accounts: List<Account>) {
        accountsFile.writeText(Json.accountsToJson(accounts), Charsets.UTF_8)
    }

    fun readCredentialKey(): String? {
        if (!keyFile.exists()) return null
        return keyFile.readText(Charsets.UTF_8).trim().ifBlank { null }
    }

    private fun copyAsset(name: String, target: File) {
        context.assets.open(name).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
}