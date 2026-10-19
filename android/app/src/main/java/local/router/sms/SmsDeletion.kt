package local.router.sms

data class SmsDeletionResult(val deleted: List<String>, val remaining: List<String>, val error: String? = null)

/** Stop on first uncertain result. Never repeat a mutation automatically. */
fun deleteSmsSequential(ids: List<String>, remove: (String) -> Unit): SmsDeletionResult {
    val unique = ids.distinct()
    require(unique.size in 1..100 && unique.all { it.matches(Regex("[0-9]{1,10}")) })
    val deleted = mutableListOf<String>()
    for ((index, id) in unique.withIndex()) {
        try { remove(id); deleted += id }
        catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            return SmsDeletionResult(deleted.toList(), unique.drop(index), e.message ?: "删除未完成")
        }
    }
    return SmsDeletionResult(deleted, emptyList())
}
