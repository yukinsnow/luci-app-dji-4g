package local.router.sms

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SmsDeletionTest {
    @Test fun deletesOnlyUniqueSelectedIds() {
        val called = mutableListOf<String>()
        val result = deleteSmsSequential(listOf("2", "1", "2")) { called += it }
        assertEquals(listOf("2", "1"), called)
        assertEquals(called, result.deleted)
        assertTrue(result.remaining.isEmpty())
    }
    @Test fun failureStopsAndReportsPartialResultsWithoutRetry() {
        val called = mutableListOf<String>()
        val result = deleteSmsSequential(listOf("1", "2", "3")) { called += it; if (it == "2") throw IOException("timeout") }
        assertEquals(listOf("1", "2"), called)
        assertEquals(listOf("1"), result.deleted)
        assertEquals(listOf("2", "3"), result.remaining)
    }
    @Test fun invalidBatchNeverMutates() {
        for (ids in listOf(emptyList(), listOf("1", "bad"), (1..101).map { it.toString() })) {
            var calls = 0
            try { deleteSmsSequential(ids) { calls++ }; fail() } catch (_: IllegalArgumentException) {}
            assertEquals(0, calls)
        }
    }
}
