package local.router.sms

data class CallTiming(val startMs: Long, val endMs: Long? = null)

fun observeCall(previous: CallTiming?, state: String, now: Long): CallTiming? = when (state) {
    "active", "held" -> if (previous == null || previous.endMs != null) CallTiming(now) else previous
    "terminated" -> previous?.let { if (it.endMs == null) it.copy(endMs = now) else it }
    else -> previous
}

fun callDuration(timing: CallTiming?, now: Long): String {
    val seconds = if (timing == null) 0 else ((timing.endMs ?: now) - timing.startMs).coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
