package local.router.sms

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.*
import org.json.JSONObject

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                RouterApp(viewModel())
            }
        }
    }
}

private val blue = Color(0xFF3482FF)
private val green = Color(0xFF20A477)

@Composable private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, lineHeight = 21.sp)
}
@Composable private fun Section(title: String) {
    Text(title, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp), fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontWeight = FontWeight.Medium)
}
@Composable private fun Panel(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(if (compact) 16.dp else 20.dp), cornerRadius = 26.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp), content = content)
    }
}
@Composable private fun Action(text: String, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, click: () -> Unit) {
    TextButton(text = text, onClick = click, enabled = enabled, modifier = modifier,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(), cornerRadius = 18.dp)
}
@Composable private fun Symbol(icon: ImageVector, color: Color = blue, size: Int = 26) {
    Image(icon, contentDescription = null, modifier = Modifier.size(size.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color))
}
@Composable private fun RoundSymbol(icon: ImageVector, color: Color = blue) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(color.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Symbol(icon, color) }
}
@Composable private fun Field(label: String, value: String, changed: (String) -> Unit, password: Boolean = false, phone: Boolean = false, multiline: Boolean = false, enabled: Boolean = true) {
    TextField(value = value, onValueChange = changed, label = label, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 5 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else if (phone) KeyboardType.Phone else KeyboardType.Text),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable fun RouterApp(vm: RouterViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var recipient by remember { mutableStateOf<String?>(null) }
    var selecting by remember(recipient) { mutableStateOf(false) }
    var selectedSms by remember(recipient) { mutableStateOf<Set<String>>(emptySet()) }
    var composing by remember { mutableStateOf(false) }
    var draftNumber by remember { mutableStateOf("") }
    var draftText by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val dialog = remember { mutableStateOf(false) }
    val errorDialog = remember { mutableStateOf(false) }
    val noticeDialog = remember { mutableStateOf(false) }
    val identityDialog = remember { mutableStateOf(false) }
    LaunchedEffect(vm.pendingHost) { identityDialog.value = vm.pendingHost.isNotEmpty() }
    LaunchedEffect(confirm) { dialog.value = confirm != null }
    LaunchedEffect(vm.error) { errorDialog.value = vm.error != null }
    LaunchedEffect(vm.notice) { noticeDialog.value = vm.notice != null }
    LaunchedEffect(vm.connected) {
        if (vm.connected && vm.messages.isEmpty()) vm.refreshAll()
        if (!vm.connected) { recipient = null; composing = false; draftText = ""; draftNumber = ""; tab = 0 }
    }
    LaunchedEffect(tab, vm.connected) {
        if (vm.connected) when(tab) { 1 -> vm.refreshCalls(); 2 -> vm.refreshStatus(); 3 -> vm.refreshNumber() }
    }
    BackHandler(vm.connected && (composing || recipient != null || tab != 0)) {
        if (!vm.busy) { if (composing) composing = false else if (recipient != null) recipient = null else tab = 0 }
    }
    val title = when {
        !vm.connected -> "连接路由器"
        composing -> "新短信"
        recipient != null -> recipient!!
        else -> listOf("短信", "电话", "设备", "连接")[tab]
    }
    Scaffold(
        topBar = { TopAppBar(title = title, navigationIcon = {
            if (recipient != null || composing) Action("返回", enabled = !vm.busy) { if (composing) composing = false else recipient = null }
        }, actions = {
            if (vm.connected && !composing) Action(if (vm.busy) "连接中" else "刷新", enabled = !vm.busy) {
                when(tab) { 0 -> vm.refreshAll(); 1 -> vm.refreshCalls(); else -> vm.refreshStatus() }
            }
        }) },
        bottomBar = {
            if (vm.connected && recipient == null && !composing) NavigationBar(
                items = listOf(NavigationItem("短信", Icons.Outlined.Email), NavigationItem("电话", Icons.Outlined.Phone),
                    NavigationItem("设备", Icons.Outlined.Info), NavigationItem("连接", Icons.Outlined.Settings)),
                selected = tab, onClick = { if (!vm.busy) tab = it }, showDivider = false)
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!vm.connected) {
                item { LoginPane(vm) }
            } else if (composing) {
                item {
                    Panel {
                        Field("收件号码", draftNumber, { draftNumber = it }, phone = true, enabled = !vm.busy)
                        Field("想说些什么…", draftText, { draftText = it }, multiline = true, enabled = !vm.busy)
                        Hint("${draftText.length} / 1600 字符 · 长短信可能拆分计费")
                        Action("发送短信", primary = true, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            if (!draftNumber.trim().matches(Regex("\\+?[0-9]{3,20}")) || draftText.isBlank() || draftText.length > 1600) {
                                vm.error = "请填写有效的号码和 1–1600 字符正文。"
                            } else if ('\\' in draftText || ('\'' in draftText && '"' in draftText)) {
                                vm.error = "正文暂不支持反斜杠或同时包含两种英文引号。"
                            } else confirm = ("发送到 ${draftNumber.trim()}？\n\n${draftText.replace(Regex("\\s+"), " ").take(200)}${if (draftText.length > 200) "…" else ""}" to {
                                vm.send(draftNumber, draftText) { draftText = ""; composing = false }
                            })
                        }
                    }
                }
                item { Hint("已提交不等于已送达。发送超时请先刷新会话，避免重复发送。") }
            } else if (recipient != null) {
                val thread = vm.messages.filter { it.number == recipient }.sortedWith(compareBy<Sms> { it.time }.thenBy { it.id.toIntOrNull() ?: 0 })
                val chosen = thread.filter { it.id in selectedSms }.map { it.id }
                item {
                    Hint("${thread.size} 条短信 · 保存在路由器中")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action(if (selecting) "取消多选" else "多选", enabled = !vm.busy) { selecting = !selecting; selectedSms = emptySet() }
                        if (selecting) {
                            Action("全选", enabled = !vm.busy) { selectedSms = thread.take(100).map { it.id }.toSet() }
                            Action("删除(${chosen.size})", enabled = !vm.busy && chosen.isNotEmpty()) {
                                val ids = chosen.toList()
                                confirm = "永久删除选中的 ${ids.size} 条短信？删除后两端都会消失，无法恢复。" to { vm.deleteMessages(ids) }
                            }
                        }
                    }
                    if (selecting) Hint("已选 ${chosen.size} 条 · 每次最多 100 条，仅选择当前会话")
                }
                items(thread, key = { it.id }) { sms ->
                    if (selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = sms.id in selectedSms, enabled = !vm.busy && (sms.id in selectedSms || chosen.size < 100),
                            onCheckedChange = { checked -> selectedSms = if (checked) selectedSms + sms.id else selectedSms - sms.id })
                        Hint("选择此短信", Modifier.padding(start = 10.dp))
                    }
                    SwipeSmsDelete(sms.id, !vm.busy && !selecting, onDelete = {
                        confirm = "从路由器模块永久删除这条短信？删除后两端都会消失，无法恢复。" to { vm.deleteSms(sms.id) }
                    }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (sms.outgoing) Arrangement.End else Arrangement.Start) {
                        Card(modifier = Modifier.fillMaxWidth(.92f), color = if (sms.outgoing) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.surface,
                            insideMargin = PaddingValues(18.dp), cornerRadius = 24.dp) {
                            Text(sms.text.ifEmpty { "非文本短信" }, fontSize = 17.sp, lineHeight = 26.sp)
                            Spacer(Modifier.height(10.dp))
                            Hint("${if (sms.outgoing) "发出" else "收到"} · ${sms.time.ifEmpty { "时间未知" }} · ${sms.state}")
                        }
                    }
                    }
                }
                item { Hint("对单条短信左滑可显示删除按钮；点击后还需确认。") }
                item { Action("回复短信", true, !vm.busy, Modifier.fillMaxWidth()) { draftNumber = recipient!!; composing = true } }
            } else when(tab) {
                0 -> {
                    item {
                        Panel {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                RoundSymbol(Icons.Outlined.Email)
                                Column(Modifier.weight(1f)) {
                                    Text("路由器收件箱", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                    Hint("${vm.messages.size} 条短信 · ${if (vm.online) "已连接" else "等待连接"}")
                                }
                                Action("新建", true, !vm.busy) { draftNumber = ""; composing = true }
                            }
                        }
                    }
                    item { Field("搜索号码或短信", search, { search = it }) }
                    val groups = vm.messages.groupBy { it.number }.entries.sortedByDescending { it.value.maxOf { s -> s.time } }
                        .filter { it.key.contains(search, true) || it.value.any { s -> s.text.contains(search, true) } }
                    if (groups.isEmpty()) item { EmptyPane(if (search.isBlank()) "这里还没有短信" else "没有找到相关短信", "点击右上角刷新，查看路由器中的最新消息。") }
                    items(groups, key = { it.key }) { (number, list) ->
                        val latest = list.maxWithOrNull(compareBy<Sms> { it.time }.thenBy { it.id.toIntOrNull() ?: 0 })!!
                        Card(modifier = Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { recipient = number },
                            insideMargin = PaddingValues(20.dp), cornerRadius = 24.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(Modifier.size(44.dp).clip(CircleShape).background(blue.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
                                    Text(number.takeLast(2), color = blue, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(number, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                        Hint("${list.size} 条")
                                    }
                                    Text(latest.text.ifEmpty { "非文本短信" }, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                                    if (latest.time.isNotEmpty()) Hint(latest.time.replace('T', ' ').take(16))
                                }
                            }
                        }
                    }
                    item { Hint("手动刷新 · 短信不保存到手机 · 不访问手机 SIM") }
                }
                1 -> item {
                    PhonePane(vm) { description, action -> confirm = description to action }
                }
                2 -> item { DevicePane(vm) }
                3 -> item {
                    OwnNumberPane(vm)
                    Spacer(Modifier.height(12.dp))
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            RoundSymbol(Icons.Outlined.Lock, green)
                            Column { Text("已登录路由器", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Hint("root @ ${RouterLogin.HOST}") }
                        }
                        InfoRow("登录方式", "SSH 加密验证")
                        InfoRow("路由器密码", "不保存")
                        InfoRow("连接凭据", "Keystore 加密保存")
                        Hint("下次打开会自动连接。路由器密码不会发送到 HTTP 接口。短信 API 仍仅供可信局域网使用。")
                        Action("退出并清除连接", enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            confirm = "退出后需要重新输入路由器密码。是否继续？" to { vm.logout() }
                        }
                    }
                    Spacer(Modifier.height(18.dp)); Hint("路由器短信 0.4.0\nCompose Miuix · 本地设备管理")
                }
            }
            if (vm.busy) item { Hint("正在与路由器通信，请稍候…", Modifier.padding(6.dp)) }
        }
        SuperDialog(show = identityDialog, title = "核对路由器身份", summary = "SSH Ed25519 指纹：\n${vm.pendingFingerprint}\n\n请与可信 SSH 客户端显示的指纹核对。确认后才会发送登录密码。已绑定设备的指纹变化时，请先查明原因。", onDismissRequest = { vm.cancelIdentity() }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("取消", modifier = Modifier.weight(1f)) { vm.cancelIdentity() }
                Action("指纹一致，信任", true, modifier = Modifier.weight(1f)) { vm.trustIdentity() }
            }
        }
        SuperDialog(show = dialog, title = "确认操作", summary = confirm?.first, onDismissRequest = { confirm = null }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action("取消", modifier = Modifier.weight(1f)) { dialog.value = false; confirm = null }
                Action("确认", true, !vm.busy, Modifier.weight(1f)) {
                    val action = confirm?.second; dialog.value = false; confirm = null; action?.invoke()
                }
            }
        }
        SuperDialog(show = errorDialog, title = "暂时无法完成", summary = vm.error, onDismissRequest = { vm.error = null }) {
            Action("知道了", true, modifier = Modifier.fillMaxWidth()) { errorDialog.value = false; vm.error = null }
        }
        SuperDialog(show = noticeDialog, title = "操作完成", summary = vm.notice, onDismissRequest = { vm.notice = null }) {
            Action("好的", true, modifier = Modifier.fillMaxWidth()) { noticeDialog.value = false; vm.notice = null }
        }
    }
}

@Composable private fun LoginPane(vm: RouterViewModel) {
    var host by remember { mutableStateOf(RouterLogin.HOST) }
    var user by remember { mutableStateOf("root") }
    // Deliberately not rememberSaveable: passwords must not enter saved-instance state.
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(100.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(32.dp)).background(blue.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Symbol(Icons.Outlined.Email, blue, 48)
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("让短信，触手可及", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Hint("连接 GL-MG1300，管理路由器里的消息")
        }
        Panel {
            Field("路由器地址", host, { host = it }, enabled = !vm.busy)
            Field("用户名", user, { user = it }, enabled = !vm.busy)
            Hint(if (vm.trustedHost.isEmpty()) "首次登录请先检测并核对 SSH 指纹。" else "SSH 身份已绑定，后续连接严格校验。")
            Action("检测路由器身份", enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { vm.inspectIdentity() }
            Field("路由器 SSH 密码", password, { password = it }, password = true, enabled = !vm.busy)
            Spacer(Modifier.height(2.dp))
            Action(if (vm.busy) "正在安全登录…" else "登录并连接", true, !vm.busy && vm.trustedHost.isNotEmpty(), Modifier.fillMaxWidth()) {
                val bytes = password.toByteArray(Charsets.UTF_8)
                password = ""
                vm.login(host, user, bytes)
            }
        }
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Symbol(Icons.Outlined.Lock, MiuixTheme.colorScheme.onSurfaceVariantSummary, 18)
            Hint("使用路由器账号登录，自动完成连接配置。密码仅用于本次 SSH 验证，不会保存。请先连接路由器 Wi-Fi。")
        }
    }
}

@Composable private fun OwnNumberPane(vm: RouterViewModel) {
    var value by remember(vm.ownNumber) { mutableStateOf(vm.ownNumber) }
    Panel {
        Text("本机号码", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Field("SIM 卡号码（可留空）", value, { value = it }, phone = true, enabled = !vm.busy)
        Hint("手动备注，保存在路由器并与 LuCI 共用。不会修改 SIM 或改变发信号码；留空保存可清除。")
        Action("保存本机号码", true, !vm.busy, Modifier.fillMaxWidth()) {
            if (value.trim().isNotEmpty() && !value.trim().matches(Regex("\\+?[0-9]{3,20}"))) vm.error = "请输入 3–20 位数字，可用 + 开头。"
            else vm.saveNumber(value)
        }
    }
}

@Composable private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Hint(title, Modifier.weight(1f)); Text(value, fontSize = 15.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
@Composable private fun EmptyPane(title: String, summary: String) {
    Panel {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundSymbol(Icons.Outlined.Email)
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Hint(summary)
        }
    }
}
@Composable private fun DevicePane(vm: RouterViewModel) {
    val generic = vm.status?.optJSONObject("modem")?.optJSONObject("modem")?.optJSONObject("generic") ?: JSONObject()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            RoundSymbol(Icons.Outlined.Info)
            Text("GL-MG1300", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(if (vm.online) "已连接路由器" else "暂未连接", color = if (vm.online) green else MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 14.sp)
            InfoRow("模块", generic.optString("model", "—"))
            InfoRow("厂商", generic.optString("manufacturer", "—"))
            InfoRow("固件", generic.optString("revision", "—"))
        }
        Section("移动网络")
        Panel {
            InfoRow("注册状态", when(generic.optString("state")) { "registered" -> "已注册"; "connected" -> "已连接"; else -> generic.optString("state", "—") })
            InfoRow("网络类型", generic.optJSONArray("access-technologies")?.join(" / ")?.replace("\"", "")?.uppercase() ?: "—")
            InfoRow("信号强度", generic.optJSONObject("signal-quality")?.optString("value")?.let { "$it%" } ?: "—")
            InfoRow("短信数量", "${vm.messages.size} 条")
        }
        Hint("状态来自 ModemManager 实时查询。刷新不会重启或重置模块。")
    }
}
@Composable private fun PhonePane(vm: RouterViewModel, confirm: (String, () -> Unit) -> Unit) {
    var number by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    val lifecycle = (androidx.activity.compose.LocalActivity.current as? ComponentActivity)?.lifecycle
    LaunchedEffect(vm.connected) {
        while (vm.connected) {
            if (lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) != false) vm.pollCalls()
            kotlinx.coroutines.delay(2000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1000); now = android.os.SystemClock.elapsedRealtime() }
    }
    val result = vm.calls
    val enabled = result?.optBoolean("controls_enabled") ?: vm.voiceEnabled
    val details = result?.optJSONArray("details")
    val all = if (details == null) emptyList() else (0 until details.length()).map { details.getJSONObject(it) }
    fun properties(call: JSONObject) = call.optJSONObject("data")?.optJSONObject("call")?.optJSONObject("properties") ?: JSONObject()
    val live = all.firstOrNull { properties(it).optString("state") != "terminated" }
    val props = live?.let { properties(it) }
    val state = props?.optString("state") ?: ""
    val liveId = live?.optString("id") ?: ""
    val connected = state in listOf("active", "held")
    val incoming = state == "ringing-in"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(compact = true) {
            Text(if (live != null) props?.optString("number", "未知号码") ?: "未知号码" else number.ifEmpty { "输入要拨打的号码" },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2,
                fontSize = if (live == null && number.isEmpty()) 20.sp else 28.sp)
            if (live != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                    RoundSymbol(Icons.Outlined.Phone, green)
                }
                Text(when(state) { "active" -> "通话中"; "held" -> "已保持"; "dialing" -> "正在拨号"; "ringing-out" -> "对方振铃"; "ringing-in" -> "来电"; else -> "呼叫进行中" },
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 20.sp)
                if (connected) {
                    Text(callDuration(vm.callTimings[liveId], now), modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 32.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Hint("从本次检测到接通开始计时", Modifier.align(Alignment.CenterHorizontally))
                }
                Spacer(Modifier.height(16.dp))
                if (incoming) TextButton(text = "接听", enabled = enabled && !vm.busy,
                    colors = ButtonDefaults.textButtonColors(color = green, textColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(58.dp), cornerRadius = 29.dp,
                    onClick = { confirm("接听当前来电？此 App 不提供音频。") { vm.call("accept", id = liveId) } })
                TextButton(text = if (incoming) "拒接" else "挂断", enabled = enabled && !vm.busy,
                    colors = ButtonDefaults.textButtonColors(color = Color(0xFFE5484D), textColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(64.dp), cornerRadius = 32.dp,
                    onClick = { confirm("挂断当前呼叫？") { vm.call("hangup", id = liveId) } })
            } else {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("+", "0", "删除")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { key ->
                            TextButton(text = key, enabled = enabled && !vm.busy,
                                onClick = { number = dialerInput(number, key) },
                                modifier = Modifier.weight(1f).height(52.dp), cornerRadius = 26.dp)
                        }
                    }
                }
                TextButton(text = "拨打电话", enabled = enabled && !vm.busy && number.matches(Regex("\\+?[0-9]{3,20}")),
                    colors = ButtonDefaults.textButtonColors(color = green, textColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(58.dp), cornerRadius = 29.dp,
                    onClick = {
                        val target = number
                        confirm("向 $target 发起呼叫？可能产生费用。没有通话音频，勿用于紧急电话。") { vm.call("dial", target) }
                    })
            }
            Hint(if (enabled) "仅控制呼叫，手机不提供通话音频。" else "电话控制未启用，请在 LuCI 中启用实验电话控制。")
            vm.callPollError?.let { Hint(it) }
        }
        val ended = all.filter { properties(it).optString("state") == "terminated" }
        if (ended.isNotEmpty()) Section("已结束的呼叫")
        ended.forEach { call ->
            Panel {
                InfoRow("号码", properties(call).optString("number", "未知号码"))
                InfoRow("状态", "已结束")
                vm.callTimings[call.optString("id")]?.let { InfoRow("本次观察时长", callDuration(it, now)) }
            }
        }
    }
}
