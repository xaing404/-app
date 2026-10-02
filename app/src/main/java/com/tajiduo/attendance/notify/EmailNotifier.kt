package com.tajiduo.attendance.notify

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * QQ 邮箱 SMTP 邮件通知（smtp.qq.com:465，SSL）：
 * - 认证账号 = 发件邮箱，密码 = SMTP 授权码（非 QQ 密码）
 * - 纯文本 UTF-8 正文，固定超时 15 秒
 * - 与 NotifyWebhook 风格一致：返回错误列表、不抛出，失败不影响签到主流程
 */
object EmailNotifier {

    private const val SMTP_HOST = "smtp.qq.com"
    private const val SMTP_PORT = "465"
    private const val TIMEOUT_MS = "15000"

    /** 返回空列表表示发送成功；否则每项为一条错误描述（不含授权码等敏感信息）。 */
    fun send(sender: String, authCode: String, recipient: String, subject: String, content: String): List<String> {
        val errors = ArrayList<String>()
        try {
            val props = Properties().apply {
                put("mail.smtp.host", SMTP_HOST)
                put("mail.smtp.port", SMTP_PORT)
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.auth", "true")
                put("mail.smtp.connectiontimeout", TIMEOUT_MS)
                put("mail.smtp.timeout", TIMEOUT_MS)
                put("mail.smtp.writetimeout", TIMEOUT_MS)
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(sender, authCode)
            })
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(sender, "塔吉多签到", "UTF-8"))
                setRecipient(Message.RecipientType.TO, InternetAddress(recipient))
                setSubject(subject, "UTF-8")
                setText(content, "UTF-8")
            }
            Transport.send(message)
        }
        catch (error: Exception) {
            errors.add(error.message ?: error.javaClass.simpleName)
        }
        return errors
    }
}