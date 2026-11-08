package top.onlyquant.tingxie.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.StartOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import top.onlyquant.tingxie.AppModel
import top.onlyquant.tingxie.ui.theme.Tokens
import top.onlyquant.tingxie.voice.DictationTarget
import top.onlyquant.tingxie.voice.Phase

/**
 * The blue push-to-talk button (.hold). It turns red while recording, grey
 * with 「松开取消」 once the finger slides up past 64dp, and fades while
 * [off] (tidying). The first press asks for the microphone.
 */
@Composable
fun HoldButton(
    m: AppModel,
    target: DictationTarget,
    modifier: Modifier,
    off: Boolean = false,
    idleText: String = "按住说话",
    recText: String = "松开结束",
    fontSize: TextUnit = 16.sp,
    glow: Dp = 6.dp,
    shadow: Boolean = true,
) {
    val p = Tokens.palette
    val context = LocalContext.current
    val voice = m.voice
    val mine = voice.busyWith(target)
    val rec = mine && voice.phase == Phase.Recording
    val cancel = rec && voice.cancelling
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) m.toast("没有麦克风权限", "去设置") {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    val slide = with(LocalDensity.current) { 64.dp.toPx() }
    val offNow by rememberUpdatedState(off)
    val targetNow by rememberUpdatedState(target)

    val bg by animateColorAsState(
        when {
            cancel -> lerp(p.bg, p.ink, .62f)
            rec -> p.rec
            else -> p.accent
        }, tween(200), label = "hold",
    )
    val scale by animateFloatAsState(if (rec) .98f else 1f, tween(120), label = "scale")
    val fg = if (cancel) p.bg else Color.White

    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (off) .45f else 1f)
            .drawBehind {
                if (rec && !cancel) {
                    val g = glow.toPx()
                    drawRoundRect(p.rec.copy(alpha = .18f), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(size.height / 2 + g))
                }
            }
            .then(if (shadow && !rec && !off) Modifier.shadow(10.dp, CircleShape, ambientColor = p.accent, spotColor = p.accent.copy(alpha = .7f)) else Modifier)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (offNow) return@awaitEachGesture
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        ask.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    val api = m.api ?: return@awaitEachGesture
                    voice.press(api.http, api.server, targetNow)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        voice.cancelling = voice.phase == Phase.Recording && down.position.y - c.position.y > slide
                    }
                    voice.release()
                }
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        when {
            cancel -> Ico(Icons.X, fg)
            rec -> Wave(fg)
            else -> Ico(Icons.Mic, fg, if (fontSize < 16.sp) 18.dp else 22.dp)
        }
        Text(
            when {
                cancel -> "松开取消"
                rec -> recText
                else -> idleText
            },
            color = fg, fontSize = fontSize, fontWeight = FontWeight.SemiBold,
        )
        val left = voice.left
        if (rec && !cancel && left != null) {
            Text("${left / 60}:${(left % 60).toString().padStart(2, '0')}", color = fg.copy(alpha = .85f), fontSize = fontSize, fontWeight = FontWeight.Medium)
        }
    }
}

/** Five bars rising and falling (.wave). */
@Composable
private fun Wave(color: Color) {
    val t = rememberInfiniteTransition(label = "wave")
    val delays = listOf(800, 600, 400, 200, 500)
    Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (d in delays) {
            val h by t.animateFloat(
                4f, 4f,
                infiniteRepeatable(
                    keyframes {
                        durationMillis = 900
                        4f at 0 using FastOutSlowInEasing
                        18f at 450 using FastOutSlowInEasing
                        4f at 900
                    },
                    RepeatMode.Restart, StartOffset(900 - d),
                ),
                label = "bar",
            )
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/** 「上滑取消」 above the button while recording; hidden once cancelling. */
@Composable
fun CancelHint(m: AppModel, target: DictationTarget, modifier: Modifier) {
    val p = Tokens.palette
    val v = m.voice
    if (!v.busyWith(target) || v.phase != Phase.Recording || v.cancelling) return
    Row(
        modifier
            .height(30.dp)
            .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .06f), spotColor = Color.Black.copy(alpha = .06f))
            .background(p.surface.copy(alpha = .94f), CircleShape)
            .border(1.dp, p.line, CircleShape)
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Ico(Icons.ChevUp, p.ink2, 16.dp)
        Text("上滑取消", color = p.ink2, fontSize = 13.sp)
    }
}

/** The floating bar at the bottom (.dock): a side button either side of 按住说话. */
@Composable
fun DockBar(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val p = Tokens.palette
    Row(
        modifier
            .padding(start = 14.dp, end = 14.dp, bottom = bottomGap())
            .fillMaxWidth()
            .shadow(28.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .22f), spotColor = Color.Black.copy(alpha = if (p.dark) .6f else .22f))
            .background(p.surface.copy(alpha = .96f), CircleShape)
            .border(1.dp, p.line, CircleShape)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** A labelled side button of the dock (.dock-side). */
@Composable
fun DockSide(icon: ImageVector, label: String, disabled: Boolean = false, busy: Boolean = false, onClick: () -> Unit) {
    val p = Tokens.palette
    Column(
        Modifier
            .width(84.dp)
            .height(52.dp)
            .pressable(CircleShape, onClick = onClick)
            .dim(disabled),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        if (busy) Spinner(p.ink2, 18.dp) else Ico(icon, p.ink2, 18.dp)
        Text(label, color = p.ink2, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
