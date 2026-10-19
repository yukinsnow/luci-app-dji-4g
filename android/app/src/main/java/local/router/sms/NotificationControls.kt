package local.router.sms

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*

@Composable fun NotificationControls(vm: RouterViewModel) {
    val context = LocalContext.current
    var showContent by remember { mutableStateOf(NotificationPrivacy.showContent(context)) }
    var active by remember { mutableStateOf(RouterNotificationService.running) }
    LaunchedEffect(Unit) { while (true) { active = RouterNotificationService.running; kotlinx.coroutines.delay(1000) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.enableNotifications() else vm.error = "通知权限未开启。请在系统通知设置中允许后，再开启后台监听。"
    }
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp), cornerRadius = 26.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("短信与来电通知")
            Text(if (active) "后台监听正在运行" else "后台监听未开启")
            Text("默认隐藏号码、正文与验证码。点通知可进入短信或电话页。需要保持连接路由器局域网；系统省电或强行停止可能延迟提醒。")
            TextButton(text = if (active) "停止后台监听" else "开启后台监听", enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(), onClick = {
                    if (active) { RouterNotificationService.stop(context); active = false }
                    else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                        vm.error = "系统已关闭此 App 的通知，请先到系统通知设置中开启。"
                    } else vm.enableNotifications()
                })
            Text("短信通知内容：" + if (showContent) "显示短信内容" else "隐私保护")
            TextButton(text = "隐私保护", enabled = showContent, modifier = Modifier.fillMaxWidth(), onClick = {
                NotificationPrivacy.setShowContent(context, false); showContent = false
            })
            TextButton(text = "显示短信内容", enabled = !showContent, modifier = Modifier.fillMaxWidth(), onClick = {
                NotificationPrivacy.setShowContent(context, true); showContent = true
            })
            Text("显示内容会在通知中展示发信号码、正文及验证码；锁屏显示仍受系统通知设置控制。切换后清除现有短信通知，从下一条新短信起生效；无需重启监听。")
            TextButton(text = "系统通知设置", modifier = Modifier.fillMaxWidth(), onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
            Text("来电约每 5 秒检查一次，短信约每 20 秒；首次启动及后端/模块重新识别时不补发历史短信。")
        }
    }
}

@Composable fun AtDiagnostics(vm: RouterViewModel) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp), cornerRadius = 26.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("AT 连通性测试")
            Text("通过 ModemManager 发送固定只读命令 AT，不直接占用串口。若固件或服务禁止 AT 命令，会显示真实错误；不会自动重启或修改模块配置。")
            TextButton(text = "测试 AT 并显示结果", enabled = !vm.busy, modifier = Modifier.fillMaxWidth(), onClick = { vm.testAt() })
            vm.atResult?.let { result -> androidx.compose.foundation.text.selection.SelectionContainer { Text(result) } }
        }
    }
}
