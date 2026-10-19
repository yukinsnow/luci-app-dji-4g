package local.router.sms

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

class ApiException(val code: Int, message: String): IOException(message)

internal fun <T> requestWithReadRetry(readOnly: Boolean, request: () -> T): T {
    try { return request() }
    catch (e: IOException) {
        if (!readOnly || e is ApiException || (e !is java.io.EOFException && e.message?.contains("unexpected end of stream", true) != true)) throw e
        // A stale connection can close without a response. Only reads may be retried.
        return request()
    }
}

object RouterConfig {
    fun token(config: String): String {
        val json = JSONObject(config)
        require(json.getString("bind") == RouterLogin.HOST) { "后端地址与当前路由器不一致" }
        return json.getString("token").also { require(it.matches(Regex("[a-f0-9]{64}"))) { "后端凭据格式不正确" } }
    }
}

class RouterApi(var token: String = "", private val readTimeoutMs: Int = 600000) {
    fun request(path: String, body: JSONObject? = null): JSONObject = requestWithReadRetry(body == null) { requestOnce(path, body) }
    private fun requestOnce(path: String, body: JSONObject?): JSONObject {
        check(token.matches(Regex("[a-f0-9]{64}"))) { "请先登录路由器" }
        val connection = URL("http://${RouterLogin.HOST}:8765/v1$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (out.size() + size > 2_000_000) throw IOException("路由器响应过大")
                    out.write(buffer, 0, size)
                }
                out.toByteArray()
            } ?: throw IOException("路由器未返回数据")
            val result = JSONObject(String(bytes, Charsets.UTF_8))
            if (code != 200) throw ApiException(code, result.optString("error", "请求失败"))
            return result
        } finally { connection.disconnect() }
    }
}

data class Sms(val id: String, val number: String, val text: String, val time: String, val state: String, val outgoing: Boolean)
fun smsFrom(json: JSONObject): Sms {
    val sms = json.getJSONObject("data").getJSONObject("sms")
    val content = sms.optJSONObject("content") ?: JSONObject()
    val props = sms.optJSONObject("properties") ?: JSONObject()
    fun String.clean() = if (this == "--") "" else this
    return Sms(json.getString("id"), content.optString("number").clean().ifEmpty { "未知号码" },
        content.optString("text").clean(), props.optString("timestamp").clean(), props.optString("state").clean(),
        props.optString("pdu-type").contains("submit"))
}
