package local.router.sms

import org.junit.Assert.*
import org.junit.Test

class DialerTest {
    @Test fun buildsInternationalNumberAndDeletes() {
        var value = ""
        listOf("+", "8", "6", "1", "3", "8").forEach { value = dialerInput(value, it) }
        assertEquals("+86138", value)
        assertEquals("+8613", dialerInput(value, "删除"))
        assertEquals("", dialerInput("", "删除"))
    }
    @Test fun limitsDigitsAndRejectsServiceCodesOrMidNumberPlus() {
        assertEquals("12", dialerInput("12", "+"))
        assertEquals("12", dialerInput("12", "#"))
        assertEquals("12", dialerInput("12", "*"))
        val full = "+" + "1".repeat(20)
        assertEquals(full, dialerInput(full, "2"))
    }
}
