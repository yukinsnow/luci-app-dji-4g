package local.router.sms

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject

/** User-started LAN device monitoring. No cloud, phone SIM, microphone or call actions. */
class RouterNotificationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val manager get() = getSystemService(NotificationManager::class.java)
    private val prefs get() = getSharedPreferences("notification-monitor", MODE_PRIVATE)
    private var statusText = "正在连接路由器"

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannels(listOf(
            NotificationChannel(STATUS, "后台监听状态", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(SMS, "路由器新短信", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CALL, "路由器来电", NotificationManager.IMPORTANCE_HIGH)
        ))
    }

    private fun open(kind: String, number: String = ""): PendingIntent = PendingIntent.getActivity(this,
        when (kind) { "sms" -> 2; "calls" -> 3; else -> 1 },
        Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_kind", kind).putExtra("open_number", number),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun basic(channel: String, title: String, text: String, kind: String = "settings"): Notification.Builder =
        Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_router).setContentTitle(title)
            .setContentText(text).setContentIntent(open(kind)).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_router)
                .setContentTitle("路由器提醒").setContentText("解锁后打开 App 查看").build())

    private fun foreground(): Notification = basic(STATUS, "短信与来电监听", statusText)
        .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true)
        .addAction(Notification.Action.Builder(null, "停止监听", PendingIntent.getService(this, 4,
            Intent(this, RouterNotificationService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        .build()

    private fun updateStatus(text: String) {
        if (statusText != text) { statusText = text; manager.notify(FOREGROUND_ID, foreground()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 29) startForeground(FOREGROUND_ID, foreground(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(FOREGROUND_ID, foreground())
        running = true
        if (worker == null) worker = scope.launch { monitor() }
        return START_NOT_STICKY
    }

    private suspend fun monitor() {
        val token = SecureStore(this).load()
        if (token.isEmpty()) { stopSelf(); return }
        val api = RouterApi(token, 15000)
        val ledger = NotificationLedger(prefs.getString("generation", "") ?: "", prefs.getStringSet("seen_ids", emptySet())!!.toSet())
        var savedGeneration = ledger.generation; var savedIds = ledger.seen.toSet()
        val ringing = IncomingCallLedger()
        var lastSms = -20000L; var retry = 5000L
        var pendingCursor = 0
        while (currentCoroutineContext().isActive) {
            try {
                if (!manager.areNotificationsEnabled()) { stopSelf(); return }
                val calls = api.request("/calls").optJSONArray("details")
                currentCoroutineContext().ensureActive()
                val currentRinging = mutableSetOf<String>(); var active = false
                if (calls != null) for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val properties = call.optJSONObject("data")?.optJSONObject("call")?.optJSONObject("properties") ?: JSONObject()
                    if (properties.optString("state") == "ringing-in") currentRinging += call.getString("id")
                    if (properties.optString("state") in listOf("active", "held")) active = true
                }
                val (newCalls, endedRinging) = ringing.update(currentRinging)
                endedRinging.forEach { manager.cancel("router-call-$it", CALL_ID) }
                newCalls.forEach { id -> manager.notify("router-call-$id", CALL_ID,
                    basic(CALL, "路由器有来电", "点击查看来电；手机不提供通话音频", "calls")
                        .setCategory(Notification.CATEGORY_CALL).setOnlyAlertOnce(true).setOngoing(true).build()) }

                val now = SystemClock.elapsedRealtime()
                if (now - lastSms >= 20000L) {
                    val index = api.request("/sms/index")
                    currentCoroutineContext().ensureActive()
                    val array = index.getJSONArray("ids")
                    val ids = (0 until array.length()).map { array.getString(it) }
                    val generation = index.getString("generation")
                    if (ledger.generation != generation) { prefs.edit().putInt("pending_count", 0).apply(); manager.cancel(SMS_ID) }
                    val pending = ledger.candidates(generation, ids)
                    val received = mutableListOf<Sms>()
                    var itemFailure: Exception? = null
                    val batch = if (pending.size > 5) (0 until 5).map { pending[(pendingCursor + it) % pending.size] } else pending
                    pendingCursor = if (pending.isEmpty()) 0 else (pendingCursor + batch.size) % pending.size
                    for (id in batch) {
                        val raw = try { api.request("/sms/item?id=$id") }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            if (e is ApiException && e.code == 404) { ledger.consumed(id); continue }
                            itemFailure = e; break
                        }
                        currentCoroutineContext().ensureActive()
                        val sms = smsFrom(raw)
                        val properties = raw.getJSONObject("data").getJSONObject("sms").optJSONObject("properties") ?: JSONObject()
                        val pdu = properties.optString("pdu-type")
                        if (sms.state in listOf("receiving", "unknown", "--", "") || pdu in listOf("unknown", "--", "")) continue
                        if (pdu.contains("deliver") && sms.state != "received") continue
                        if (sms.state == "received" && pdu.contains("deliver")) received += sms
                        ledger.consumed(id)
                    }
                    if (received.isNotEmpty()) {
                        val count = (prefs.getInt("pending_count", 0) + received.size).coerceAtMost(9999)
                        prefs.edit().putInt("pending_count", count).apply()
                        val number = if (count == 1 && received.size == 1) received[0].number else ""
                        synchronized(NotificationPrivacy) {
                            val showContent = NotificationPrivacy.showContent(this)
                            val latest = received.last()
                            val display = smsNotificationText(showContent, count, latest.number, latest.text)
                            val notification = basic(SMS, display.title, display.body, "sms")
                                .setContentIntent(open("sms", number)).setCategory(Notification.CATEGORY_MESSAGE).setAutoCancel(true)
                            if (showContent) notification.setVisibility(Notification.VISIBILITY_PUBLIC).setPublicVersion(null)
                                .setStyle(Notification.BigTextStyle().bigText(display.body))
                            manager.notify(SMS_ID, notification.build())
                        }
                    }
                    if (savedGeneration != ledger.generation || savedIds != ledger.seen) {
                        prefs.edit().putString("generation", ledger.generation).putStringSet("seen_ids", ledger.seen.toSet()).apply()
                        savedGeneration = ledger.generation; savedIds = ledger.seen.toSet()
                    }
                    itemFailure?.let { throw it }
                    lastSms = if (pending.size > 5) -20000L else now
                }
                updateStatus(when { currentRinging.isNotEmpty() -> "路由器有来电，点击通知查看"; active -> "路由器通话中 · 手机无音频"; else -> "已连接 · 正在检查新短信和来电" })
                retry = 5000L
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ApiException && e.code in listOf(401, 404)) {
                    manager.notify(ERROR_ID, basic(STATUS, "监听已停止", if (e.code == 401) "连接已失效，请重新登录" else "请先升级配套通知后端").setAutoCancel(true).build())
                    stopSelf(); return
                }
                updateStatus("暂未连接路由器，将自动重试")
                retry = (retry * 2).coerceAtMost(60000L)
            }
            delay(retry)
        }
    }

    override fun onDestroy() {
        running = false; scope.cancel()
        manager.activeNotifications.filter { it.tag?.startsWith("router-call-") == true }.forEach { manager.cancel(it.tag, it.id) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val STATUS = "router-monitor"
        const val SMS = "router-sms"
        const val CALL = "router-incoming-call"
        const val FOREGROUND_ID = 700
        const val SMS_ID = 701
        const val CALL_ID = 702
        const val ERROR_ID = 703
        private const val STOP = "local.router.sms.STOP_MONITOR"
        @Volatile var running = false
            private set
        fun start(context: Context) { context.startForegroundService(Intent(context, RouterNotificationService::class.java)) }
        fun stop(context: Context) { context.stopService(Intent(context, RouterNotificationService::class.java)) }
    }
}
