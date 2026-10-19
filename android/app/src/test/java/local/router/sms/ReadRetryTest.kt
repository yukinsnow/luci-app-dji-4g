package local.router.sms

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException

class ReadRetryTest {
    @Test fun retriesReadAfterClosedConnection() {
        var attempts = 0
        assertEquals("ok", requestWithReadRetry(true) { if (++attempts == 1) throw EOFException("unexpected end of stream"); "ok" })
        assertEquals(2, attempts)
    }
    @Test fun neverRetriesMutationOrAuthenticationFailure() {
        var attempts = 0
        try { requestWithReadRetry(false) { attempts++; throw EOFException() }; fail() } catch (_: EOFException) {}
        assertEquals(1, attempts)
        attempts = 0
        try { requestWithReadRetry(true) { attempts++; throw ApiException(401, "Unauthorized") }; fail() } catch (_: ApiException) {}
        assertEquals(1, attempts)
    }
}
