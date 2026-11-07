package top.onlyquant.tingxie.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.onlyquant.tingxie.ui.theme.Tokens

/** A press highlight that lingers long enough to be seen on a quick tap. */
@Composable
fun Modifier.pressable(
    shape: Shape,
    pressed: Color = Tokens.palette.selected,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(down) {
        if (down) lit = true else {
            kotlinx.coroutines.delay(120)
            lit = false
        }
    }
    return this
        .clip(shape)
        .background(if (lit) pressed else Color.Transparent)
        .clickable(source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun Ico(icon: ImageVector, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))

/** The prototype's .spinner: a ring with a gap, turning. */
@Composable
fun Spinner(color: Color, size: Dp = 16.dp, stroke: Dp = 2.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(modifier.size(size).rotate(angle)) {
        val w = stroke.toPx()
        drawArc(color, -45f, 270f, false, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w))
    }
}

enum class BtnKind { Plain, Ghost, Primary, Danger, Accent }

/** The prototype's .btn: 42dp, rounded 12. */
@Composable
fun Btn(text: String, kind: BtnKind = BtnKind.Plain, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val p = Tokens.palette
    val (bg, fg) = when (kind) {
        BtnKind.Plain -> p.chip to p.ink
        BtnKind.Ghost -> Color.Transparent to p.ink2
        BtnKind.Primary -> p.ink to p.surface
        BtnKind.Danger -> p.rec to Color.White
        BtnKind.Accent -> p.accent to Color.White
    }
    Row(
        Modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .pressable(RoundedCornerShape(12.dp), enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) Spinner(fg, 14.dp)
        Text(text, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** A centred card dialog (.dlg). */
@Composable
fun Dlg(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit) {
    val p = Tokens.palette
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().imePadding().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(24.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = .2f))
                    .clip(RoundedCornerShape(24.dp))
                    .background(p.surface)
                    .border(1.dp, p.line, RoundedCornerShape(24.dp))
                    .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 14.dp),
            ) {
                Text(title, color = p.ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                content()
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = actions)
            }
        }
    }
}

/** A one-line text field in a dialog (.dlg .field), focused with its text selected. */
@Composable
fun DlgField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, onDone: () -> Unit, keyboard: KeyboardOptions = KeyboardOptions.Default) {
    val p = Tokens.palette
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value, onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = p.ink, fontSize = 16.sp),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = keyboard.copy(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.chip)
                    .border(1.5.dp, p.accent, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
    Spacer(Modifier.height(18.dp))
}

fun selectAll(text: String) = TextFieldValue(text, TextRange(0, text.length))

/** What the snack bar shows; a new message replaces the old one. */
data class SnackMsg(
    val text: String,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
    /** Stays until replaced or withdrawn, as the download progress does. */
    val sticky: Boolean = false,
    val id: Long = System.nanoTime(),
)

/** The dark capsule at the bottom (.snack), light in dark mode. */
@Composable
fun Snack(msg: SnackMsg, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val p = Tokens.palette
    if (!msg.sticky) LaunchedEffect(msg.id) {
        kotlinx.coroutines.delay(if (msg.action != null) 6000 else 3000)
        onDone()
    }
    Row(
        modifier
            .widthIn(max = 372.dp)
            .heightIn(min = 44.dp)
            .shadow(16.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .35f), spotColor = Color.Black.copy(alpha = .35f))
            .clip(CircleShape)
            .background(p.ink.copy(alpha = .9f))
            .padding(start = 20.dp, end = if (msg.action != null) 6.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg.text, color = p.bg, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (msg.action != null) {
            val actionColor = if (p.dark) p.accent else lerp(p.accent, Color.White, .4f)
            Box(
                Modifier
                    .padding(start = 8.dp)
                    .height(34.dp)
                    .pressable(CircleShape, pressed = Color.White.copy(alpha = .12f)) {
                        onDone()
                        msg.onAction?.invoke()
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(msg.action, color = actionColor, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** A row in a menu or bottom sheet: icon, label; red when destructive. */
@Composable
fun MenuItem(icon: ImageVector, text: String, danger: Boolean = false, height: Dp = 48.dp, gap: Dp = 14.dp, onClick: () -> Unit) {
    val p = Tokens.palette
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .pressable(RoundedCornerShape(10.dp), onClick = onClick)
            .padding(horizontal = if (height > 48.dp) 16.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        Ico(icon, if (danger) p.rec else p.ink2, if (height > 48.dp) 22.dp else 20.dp)
        Text(text, color = if (danger) p.rec else p.ink, fontSize = if (height > 48.dp) 16.sp else 15.5.sp)
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(Tokens.palette.line))

/** The rounded avatar: the photo, or the name's first letter on periwinkle. */
@Composable
fun Avatar(name: String, url: String?, size: Dp, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFFB6C4F4)), contentAlignment = Alignment.Center) {
        if (url.isNullOrEmpty()) {
            Text(name.take(1).uppercase(), color = Color(0xFF1D1D1F), fontSize = fontSize, fontWeight = FontWeight.SemiBold)
        } else {
            coil3.compose.AsyncImage(url, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** Dims a disabled control the way the prototype does (.disabled). */
fun Modifier.dim(on: Boolean, alpha: Float = .45f) = if (on) this.alpha(alpha) else this

@Composable
fun Gap(w: Dp = 0.dp, h: Dp = 0.dp) = Spacer(Modifier.width(w).height(h))

/** The prototype's .bsheet: a 26dp-rounded sheet with a small grabber. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val p = Tokens.palette
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = p.surface,
        scrimColor = Color.Black.copy(alpha = if (p.dark) .5f else .32f),
        dragHandle = {
            Box(Modifier.padding(top = 9.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(p.ink3.copy(alpha = .5f)))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 32.dp), content = content)
    }
}
