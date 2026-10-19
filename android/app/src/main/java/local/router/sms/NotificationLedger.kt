package local.router.sms

/** IDs only, no message bodies. A new daemon/modem generation establishes a fresh baseline. */
class NotificationLedger(var generation: String = "", seen: Set<String> = emptySet()) {
    val seen = seen.toMutableSet()
    fun candidates(currentGeneration: String, ids: List<String>): List<String> {
        require(currentGeneration.isNotEmpty() && ids.size <= 10000 && ids.all { it.matches(Regex("[0-9]{1,10}")) })
        if (generation != currentGeneration) {
            generation = currentGeneration; seen.clear(); seen.addAll(ids)
            return emptyList()
        }
        seen.retainAll(ids.toSet())
        return ids.filterNot { it in seen }
    }
    fun consumed(id: String) { seen += id }
}

class IncomingCallLedger {
    private var ringing = emptySet<String>()
    fun update(current: Set<String>): Pair<Set<String>, Set<String>> {
        val added = current - ringing; val removed = ringing - current
        ringing = current.toSet()
        return added to removed
    }
}
