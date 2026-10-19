package local.router.sms

import org.junit.Assert.*
import org.junit.Test

class NotificationPrivacyTest {
    @Test fun privacyNeverIncludesSenderOrBody() {
        val result = smsNotificationText(false, 2, "synthetic-sender", "synthetic-secret")
        assertFalse(result.toString().contains("synthetic-sender"))
        assertFalse(result.toString().contains("synthetic-secret"))
        assertTrue(result.title.contains("2"))
    }
    @Test fun explicitContentModeShowsLatestSenderAndBody() {
        assertEquals(SmsNotificationText("sender", "body"), smsNotificationText(true, 1, "sender", "body"))
        assertEquals("2 条新短信 · 最新来自 sender", smsNotificationText(true, 2, "sender", "body").title)
    }
    @Test fun contentHandlesNonTextAndBoundsLength() {
        assertEquals("非文本短信", smsNotificationText(true, 1, "sender", " ").body)
        assertEquals(1000, smsNotificationText(true, 1, "sender", "a".repeat(1600)).body.length)
    }
}
