package local.router.sms

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.graphics.Color

/** Swipe left to reveal a trailing action; vertical scrolling does not reveal it. */
@Composable fun SwipeSmsDelete(id: String, enabled: Boolean, onDelete: () -> Unit, deleteLabel: String = "删除短信", content: @Composable () -> Unit) {
    var revealed by remember(id) { mutableStateOf(false) }
    val density = LocalDensity.current
    val threshold = with(density) { 48.dp.toPx() }
    val actionWidth = with(density) { 112.dp.toPx() }
    var dragging by remember(id) { mutableStateOf(false) }
    var distance by remember(id) { mutableFloatStateOf(0f) }
    val settledOffset by animateDpAsState(if (revealed) (-112).dp else 0.dp, label = "sms-delete-offset")
    val offset = if (dragging) with(density) { ((if (revealed) -actionWidth else 0f) + distance).coerceIn(-actionWidth, 0f).toDp() } else settledOffset
    LaunchedEffect(enabled) { if (!enabled) revealed = false }
    Box(Modifier.fillMaxWidth().clipToBounds()
        .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) {
            if (enabled) { onDelete(); true } else false
        }) }
        .pointerInput(id, enabled, threshold) {
            if (enabled) detectHorizontalDragGestures(
                onDragStart = { distance = 0f; dragging = true },
                onHorizontalDrag = { change, amount -> change.consume(); distance += amount },
                onDragCancel = { distance = 0f; dragging = false },
                onDragEnd = {
                    if (distance <= -threshold) revealed = true
                    else if (distance >= threshold) revealed = false
                    distance = 0f; dragging = false
                }
            )
        }) {
        if (revealed || (dragging && distance < 0)) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            TextButton(text = deleteLabel, enabled = enabled, onClick = onDelete,
                modifier = Modifier.width(104.dp).fillMaxHeight(),
                colors = ButtonDefaults.textButtonColors(color = Color(0xFFDE3030), textColor = Color.White))
        }
        Box(Modifier.fillMaxWidth().offset(x = offset)) { content() }
    }
}
