package com.tajiduo.attendance.net

import android.util.Base64
import com.tajiduo.attendance.data.EncryptedPassword
import org.bouncycastle.crypto.generators.SCrypt
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 与 TS 端 node:crypto 等价的加解密工具。 */
object Crypto {

    private val HEX = "0123456789abcdef".toCharArray()
    private const val SCRYPT_N = 16384
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            sb.append(HEX[value shr 4])
            sb.append(HEX[value and 0x0F])
        }
        return sb.toString()
    }

    fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    /** laohu 手机号/密码/验证码字段：AES-128-ECB + base64（密钥为 secret 后 16 字节）。 */
    fun aes128EcbBase64(value: String, secret: String): String {
        val key = secret.substring(secret.length - 16).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun deriveScryptKey(credentialKey: String, salt: ByteArray): ByteArray =
        SCrypt.generate(credentialKey.toByteArray(Charsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, 32)

    /** 解密账号中的 encryptedPassword（v1: sha256 派生；v2: scrypt 派生）。 */
    fun decryptPassword(encryptedPassword: EncryptedPassword, credentialKey: String): String {
        try {
            if (encryptedPassword.alg != "AES-256-GCM") {
                throw IllegalStateException("不支持的加密密码格式")
            }
            val key = if (encryptedPassword.v == 1) {
                sha256(credentialKey)
            } else {
                val salt = base64UrlDecode(encryptedPassword.salt ?: throw IllegalStateException("缺少 salt"))
                deriveScryptKey(credentialKey, salt)
            }
            val iv = base64UrlDecode(encryptedPassword.iv)
            val tag = base64UrlDecode(encryptedPassword.tag)
            val data = base64UrlDecode(encryptedPassword.data)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(data + tag)
            return String(plain, Charsets.UTF_8)
        }
        catch (error: Exception) {
            throw IllegalStateException("存储密码解密失败，请检查 credential-key", error)
        }
    }
}