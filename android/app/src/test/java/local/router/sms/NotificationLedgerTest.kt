package local.router.sms

import org.junit.Assert.*
import org.junit.Test

class NotificationLedgerTest {
    @Test fun baselineSkipsHistoryAndPersistsOnlyIds() {
        val ledger = NotificationLedger()
        assertTrue(ledger.candidates("one", listOf("1", "2")).isEmpty())
        assertEquals(listOf("3"), ledger.candidates("one", listOf("1", "2", "3")))
        // Receiving/unreadable items are not consumed, so remain eligible next time.
        assertEquals(listOf("3"), ledger.candidates("one", listOf("1", "2", "3")))
        ledger.consumed("3")
        val restored = NotificationLedger(ledger.generation, ledger.seen)
        assertTrue(restored.candidates("one", listOf("1", "2", "3")).isEmpty())
        assertTrue(restored.candidates("two", listOf("1", "4")).isEmpty())
    }
    @Test fun invalidIdsNeverBecomeRequestPaths() {
        val ledger = NotificationLedger()
        try { ledger.candidates("one", listOf("1&other=bad")); fail() } catch (_: IllegalArgumentException) {}
        assertEquals("", ledger.generation)
    }
    @Test fun ringsOnceAndCancelsWhenAnsweredOrEnded() {
        val ledger = IncomingCallLedger()
        assertEquals(setOf("7"), ledger.update(setOf("7")).first)
        assertTrue(ledger.update(setOf("7")).first.isEmpty())
        assertEquals(setOf("7"), ledger.update(emptySet()).second)
        assertTrue(ledger.update(emptySet()).second.isEmpty())
    }
}
