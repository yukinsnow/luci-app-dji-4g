package local.router.sms

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color

/** Horizontal drag is detected after touch slop, leaving vertical list scrolling intact. */
@Composable fun SwipeSmsDelete(id: String, enabled: Boolean, onDelete: () -> Unit, content: @Composable () -> Unit) {
    var revealed by remember(id) { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    var distance by remember(id) { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth()
        .semantics { customActions = listOf(CustomAccessibilityAction("删除短信") {
            if (enabled) { onDelete(); true } else false
        }) }
        .pointerInput(id, enabled, threshold) {
            if (enabled) detectHorizontalDragGestures(
                onDragStart = { distance = 0f },
                onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
                onDragCancel = { distance = 0f },
                onDragEnd = {
                    if (distance <= -threshold) revealed = true
                    else if (distance >= threshold) revealed = false
                    distance = 0f
                }
            )
        }) {
        content()
        if (revealed) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(text = "收起", enabled = enabled, onClick = { revealed = false })
            Spacer(Modifier.width(8.dp))
            TextButton(text = "删除短信", enabled = enabled, onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(color = Color(0xFFFFE5E5), textColor = Color(0xFFB3261E)))
        }
    }
}
