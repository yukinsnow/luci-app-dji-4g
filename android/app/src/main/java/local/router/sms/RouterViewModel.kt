package local.router.sms

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.jcraft.jsch.JSchException
import java.util.Arrays

class RouterViewModel(application: Application): AndroidViewModel(application) {
    private val identity = application.getSharedPreferences("ssh-identity", 0)
    var trustedHost by mutableStateOf(identity.getString("known_host", "") ?: ""); private set
    var pendingHost by mutableStateOf(""); private set
    var pendingFingerprint by mutableStateOf(""); private set
    var ownNumber by mutableStateOf(""); private set
    private val store = SecureStore(application)
    private val api = RouterApi(store.load())
    var connected by mutableStateOf(api.token.isNotEmpty()); private set
    var online by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var messages by mutableStateOf<List<Sms>>(emptyList()); private set
    var status by mutableStateOf<JSONObject?>(null); private set
    var calls by mutableStateOf<JSONObject?>(null); private set
    var callTimings by mutableStateOf<Map<String, CallTiming>>(emptyMap()); private set
    var callPollError by mutableStateOf<String?>(null); private set
    private var pollingCalls = false
    private var callsGeneration = 0L
    val voiceEnabled get() = status?.optJSONObject("voice")?.optBoolean("controls_enabled") == true

    init { if (connected) refreshAll() }

    private fun <T> work(block: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { val value = withContext(Dispatchers.IO) { block() }; done(value); online = true }
            catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                online = false
                error = when {
                    e is ApiException && e.code == 401 -> {
                        store.clear(); api.token = ""; connected = false; messages = emptyList(); status = null; calls = null
                        "登录已失效，请重新输入路由器账号和密码。"
                    }
                    e is JSchException && (e.message?.contains("Auth fail", true) == true) -> "用户名或密码不正确，请使用路由器 SSH 登录密码。"
                    e is JSchException && (e.message?.contains("HostKey", true) == true || e.message?.contains("host key", true) == true) -> "路由器身份校验失败。设备公钥与已绑定的路由器不同，请核实设备，勿继续登录。"
                    e is java.net.ConnectException || e is java.net.SocketTimeoutException -> "无法连接路由器，请确认已连接其 Wi-Fi，且短信服务正在运行。操作超时后先刷新，避免重复发送或拨号。"
                    else -> (e.message ?: "操作未完成，请稍后重试。") + "\n发送或拨号失败后请先刷新，避免重复操作。"
                }
            } finally { busy = false }
        }
    }
    fun login(host: String, user: String, password: ByteArray) {
        if (busy) { Arrays.fill(password, 0.toByte()); return }
        work({
            val token = RouterConfig.token(RouterLogin.fetchConfiguration(host.trim(), user.trim(), password, trustedHost))
            val candidate = RouterApi(token)
            val result = try { candidate.request("/status") }
                catch (e: java.io.IOException) { throw java.io.IOException("读取短信服务状态失败：${e.message}", e) }
            // Commit only after the authenticated API status request succeeds.
            store.save(token)
            token to result
        }) { (token, result) -> api.token = token; status = result; connected = true; notice = "已安全登录，连接信息已保存。" }
    }
    fun logout() {
        if (busy) return
        callsGeneration++; callTimings = emptyMap(); callPollError = null
        store.clear(); api.token = ""; connected = false; online = false; status = null; calls = null; messages = emptyList()
    }
    fun inspectIdentity() = work({
        val key = RouterLogin.discoverHostKey()
        key to RouterLogin.fingerprint(key)
    }) { (key, fingerprint) -> pendingHost = key; pendingFingerprint = fingerprint }
    fun trustIdentity() {
        trustedHost = pendingHost
        identity.edit().putString("known_host", trustedHost).apply()
        cancelIdentity()
    }
    fun cancelIdentity() { pendingHost = ""; pendingFingerprint = "" }
    fun refreshNumber() = work({ api.request("/settings/number") }) { ownNumber = it.optString("own_number") }
    fun saveNumber(value: String) = work({
        api.request("/settings/number", JSONObject().put("own_number", value.trim()))
    }) { ownNumber = it.optString("own_number"); notice = "本机号码已保存，LuCI 与安卓端共用。" }
    private fun readMessages(): List<Sms> {
        val all = mutableListOf<Sms>(); var offset = 0
        repeat(40) {
            val result = api.request("/sms?offset=$offset")
            check(result.getJSONArray("errors").length() == 0) { "部分短信暂时无法读取，请稍后刷新。" }
            val array = result.getJSONArray("messages")
            for (i in 0 until array.length()) all += smsFrom(array.getJSONObject(i))
            if (result.isNull("next")) return all
            offset = result.getInt("next")
        }
        error("短信超过 1000 条，请先归档路由器中的短信。")
    }
    fun refreshAll() = work({ api.request("/status") to readMessages() }) { (s, list) -> status = s; ownNumber = s.optString("own_number"); messages = list }
    fun refreshMessages() = work({ readMessages() }) { messages = it }
    fun refreshStatus() = work({ api.request("/status") }) { status = it; ownNumber = it.optString("own_number") }
    private fun applyCalls(value: JSONObject) {
        callsGeneration++
        val timings = mutableMapOf<String, CallTiming>()
        val details = value.optJSONArray("details")
        val now = android.os.SystemClock.elapsedRealtime()
        if (details != null) for (i in 0 until details.length()) {
            val call = details.getJSONObject(i); val id = call.getString("id")
            val state = call.optJSONObject("data")?.optJSONObject("call")?.optJSONObject("properties")?.optString("state") ?: ""
            observeCall(callTimings[id], state, now)?.let { timings[id] = it }
        }
        calls = value; callTimings = timings; callPollError = null
    }
    fun pollCalls() {
        if (busy || pollingCalls || !connected) return
        pollingCalls = true
        val generation = callsGeneration
        viewModelScope.launch {
            try {
                val value = withContext(Dispatchers.IO) { api.request("/calls") }
                if (!busy && connected && generation == callsGeneration) applyCalls(value)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                callPollError = "呼叫状态更新失败，请手动刷新核对。"
            } finally { pollingCalls = false }
        }
    }
    fun refreshCalls() = work({ api.request("/calls") }) { applyCalls(it) }
    fun deleteSms(id: String) = deleteMessages(listOf(id))
    fun deleteMessages(ids: List<String>) = work({
        deleteSmsSequential(ids) { id -> check(api.request("/sms/delete", JSONObject().put("id", id)).optBoolean("ok")) { "删除未确认" } }
    }) { result ->
        messages = messages.filterNot { it.id in result.deleted }
        if (result.error == null) notice = "已从路由器模块删除 ${result.deleted.size} 条短信。"
        else error = "已删除 ${result.deleted.size} 条，${result.remaining.size} 条未确认删除。请先刷新核对，再选择剩余短信。\n${result.error}"
    }
    fun send(number: String, text: String, sent: () -> Unit) = work({
        api.request("/sms/send", JSONObject().put("number", number.trim()).put("text", text))
    }) { notice = "短信已提交至模块，尚非送达确认。"; sent() }
    fun call(action: String, number: String = "", id: String = "") = work({
        require(action in listOf("dial", "accept", "hangup"))
        api.request("/calls/$action", if (action == "dial") JSONObject().put("number", number.trim()) else JSONObject().put("id", id))
        api.request("/calls")
    }) { applyCalls(it); notice = "呼叫操作已提交。此 App 不提供通话音频。" }
}
