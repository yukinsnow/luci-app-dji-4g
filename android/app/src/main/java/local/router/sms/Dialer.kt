package local.router.sms

fun dialerInput(current: String, key: String): String = when {
    key == "删除" -> current.dropLast(1)
    key == "+" -> if (current.isEmpty()) "+" else current
    key.length == 1 && key[0] in '0'..'9' && current.count { it in '0'..'9' } < 20 -> current + key
    else -> current
}
