package local.router.sms

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ConnectionTest {
    @Test fun requiresConfirmedHostKeyAndWipesPassword() {
        val secret = "temporary-password".toByteArray()
        try { RouterLogin.fetchConfiguration(RouterLogin.HOST, "root", secret, ""); fail("Unconfirmed key accepted") }
        catch (expected: java.io.IOException) { assertTrue(secret.all { it == 0.toByte() }) }
    }
    @Test fun refusesOtherHostsBeforeConnectingAndWipesPassword() {
        val secret = "temporary-password".toByteArray()
        try { RouterLogin.fetchConfiguration("example.com", "root", secret, ""); fail("Unexpected host accepted") }
        catch (expected: java.io.IOException) { assertTrue(secret.all { it == 0.toByte() }) }
    }
    @Test fun refusesUnprivilegedOrInjectedUserBeforeConnecting() {
        val secret = "temporary-password".toByteArray()
        try { RouterLogin.fetchConfiguration(RouterLogin.HOST, "root; reboot", secret, ""); fail("Invalid user accepted") }
        catch (expected: java.io.IOException) { assertTrue(secret.all { it == 0.toByte() }) }
    }
    @Test fun validatesConfigBeforeSavingCredentials() {
        val token = "a".repeat(64)
        assertEquals(token, RouterConfig.token(JSONObject().put("bind", RouterLogin.HOST).put("token", token).toString()))
        for ((host, value) in listOf("0.0.0.0" to token, RouterLogin.HOST to "bad-token")) {
            try { RouterConfig.token(JSONObject().put("bind",host).put("token",value).toString()); fail("Unsafe config accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun parsesActualMmcliSmsSchemaWithoutLosingUnicode() {
        val source = """{"id":"4","data":{"sms":{"content":{"number":"10000","text":"中文消息\n第二行"},"properties":{"timestamp":"--","state":"received","pdu-type":"deliver"}}}}"""
        val sms = smsFrom(JSONObject(source))
        assertEquals("中文消息\n第二行", sms.text)
        assertEquals("", sms.time)
        assertFalse(sms.outgoing)
        val outgoing = source.replace("deliver", "submit")
        assertTrue(smsFrom(JSONObject(outgoing)).outgoing)
    }
}
