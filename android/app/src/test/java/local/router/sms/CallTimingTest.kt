package local.router.sms

import org.junit.Assert.*
import org.junit.Test

class CallTimingTest {
    @Test fun startsOnObservedConnectionAndContinuesAcrossRefreshAndHold() {
        assertNull(observeCall(null, "dialing", 1000))
        val start = observeCall(null, "active", 5000)!!
        assertEquals(start, observeCall(start, "active", 9000))
        assertEquals(start, observeCall(start, "held", 10000))
        assertEquals("01:02", callDuration(start, 67000))
    }
    @Test fun stopsAtTerminationAndDoesNotInventEarlierDuration() {
        assertEquals("00:00", callDuration(null, 100000))
        val end = observeCall(CallTiming(1000), "terminated", 6000)!!
        assertEquals("00:05", callDuration(end, 100000))
        assertEquals("01:01:01", callDuration(CallTiming(0), 3661000))
    }
}
