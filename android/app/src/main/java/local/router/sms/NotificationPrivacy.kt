package local.router.sms

import android.app.NotificationManager
import android.content.Context

/** The same lock guards preference changes and posting, so an old plaintext post cannot race a privacy change. */
object NotificationPrivacy {
    fun showContent(context: Context): Boolean = context.getSharedPreferences("notification-display", 0).getBoolean("show_sms_content", false)
    fun setShowContent(context: Context, enabled: Boolean) = synchronized(this) {
        context.getSharedPreferences("notification-display", 0).edit().putBoolean("show_sms_content", enabled).apply()
        // Do not retain a previously exposed notification after switching back to privacy.
        context.getSystemService(NotificationManager::class.java).cancel(RouterNotificationService.SMS_ID)
    }
}

data class SmsNotificationText(val title: String, val body: String)
fun smsNotificationText(showContent: Boolean, count: Int, number: String, text: String): SmsNotificationText =
    if (!showContent) SmsNotificationText("路由器有 $count 条新短信", "号码、正文和验证码已隐藏，点击查看")
    else SmsNotificationText(if (count == 1) number else "$count 条新短信 · 最新来自 $number", text.ifBlank { "非文本短信" }.take(1000))
