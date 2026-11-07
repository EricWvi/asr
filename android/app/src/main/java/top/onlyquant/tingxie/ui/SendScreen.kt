package top.onlyquant.tingxie.ui

import android.content.ClipData
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.onlyquant.tingxie.AppModel
import top.onlyquant.tingxie.data.Device
import top.onlyquant.tingxie.data.SendRecord
import top.onlyquant.tingxie.data.SendState
import top.onlyquant.tingxie.data.activeAgo
import top.onlyquant.tingxie.data.autoTarget
import top.onlyquant.tingxie.data.sentTime
import top.onlyquant.tingxie.data.usedAgo
import top.onlyquant.tingxie.ui.theme.Tokens
import top.onlyquant.tingxie.ui.theme.tint
import top.onlyquant.tingxie.voice.DictationTarget
import java.time.Instant

private const val FRESH_MS = 2500L
private const val FADE_MS = 1200

/**
 * Dictation for the send page: what is heard shows in a live card, and the
 * final text goes to the Mac as it is. As in a document, sentences already
 * final still count when recognition fails.
 */
class SendDictation(private val m: AppModel) : DictationTarget {
    var live by mutableStateOf<String?>(null)
        private set

    override fun begin() {
        live = ""
    }

    override fun interim(text: String) {
        if (live != null) live = text
    }

    override fun commit(text: String, error: String?) {
        live = null
        if (error != null) m.toast(error)
        if (text.isNotBlank()) m.sends.send(text)
    }

    override fun cancel() {
        live = null
    }
}

/**
 * 发送到电脑: the target Mac on top, the records below, newest first; the
 * talk button at the bottom sends on letting go. What is being heard shows
 * first of all, pushing the records down. The list is lazy, as 30 days of
 * records can run long.
 */
@Composable
fun SendScreen(m: AppModel) {
    val p = Tokens.palette
    val sends = m.sends
    val talk = remember { SendDictation(m) }
    var picking by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    DisposableEffect(talk) { onDispose { if (m.voice.busyWith(talk)) m.voice.abort() } }

    // A Mac's online state stays current while the page is open, as on the devices page.
    LaunchedEffect(Unit) {
        sends.prune()
        while (true) {
            sends.refreshMacs()
            delay(10_000)
        }
    }

    val live = talk.live
    val records = sends.records
    LaunchedEffect(live != null, records.lastOrNull()?.id) { list.scrollToItem(0) }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            AppBar(m, "发送到电脑")
            if (!m.online) OfflineBar()
            TargetCard(m) {
                picking = true
                scope.launch { sends.refreshMacs() }
            }
            if (live == null && records.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                    Text("还没有发送过", color = p.ink3, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomGap() + 108.dp),
                ) {
                    item(key = "label") {
                        Text("最近", color = p.ink3, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp))
                    }
                    if (live != null) item(key = "live") { LiveCard(live, m.voice.cancelling) }
                    items(records.asReversed(), key = { it.id }) { r ->
                        SentCard(
                            r,
                            onCopy = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("听写", r.text)))
                                    m.toast("已复制")
                                }
                            },
                            onResend = {
                                if (!m.online) m.toast("离线，只能查看")
                                else sends.send(r.text, replacing = r.id.takeIf { r.state == SendState.Failed })
                            },
                        )
                    }
                }
            }
        }
        if (m.online) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, .25f to p.bg))
                    .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = bottomGap()),
            ) {
                HoldButton(m, talk, Modifier.fillMaxWidth().height(60.dp), idleText = "按住说话", recText = "松开发送", fontSize = 16.5.sp)
            }
            CancelHint(m, talk, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomGap() + 74.dp))
        }
        SendSnack(m)
    }
    if (picking) TargetSheet(m) { picking = false }
}

@Composable
private fun BoxScope.SendSnack(m: AppModel) {
    m.snack?.let { s ->
        Snack(s, onDone = { if (m.snack?.id == s.id) m.snack = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomGap() + 86.dp))
    }
}

@Composable
private fun DevIcon(icon: ImageVector) {
    val p = Tokens.palette
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.chip), contentAlignment = Alignment.Center) {
        Ico(icon, p.ink2)
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    val p = Tokens.palette
    Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) p.tint("mint").deep else p.ink3))
}

/** The name and status line of a Mac, on the card and in the sheet. */
@Composable
private fun TargetBody(name: String, sub: String, dot: Boolean?, dimmed: Boolean = false, modifier: Modifier = Modifier) {
    val p = Tokens.palette
    Column(modifier) {
        Text(name, color = if (dimmed) p.ink2 else p.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dot != null) StatusDot(dot)
                Text(sub, color = p.ink2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The target Mac (.target); tapping it opens the sheet. */
@Composable
private fun TargetCard(m: AppModel, onClick: () -> Unit) {
    val p = Tokens.palette
    val sends = m.sends
    val shape = RoundedCornerShape(16.dp)
    val chosen = sends.chosen
    val auto = autoTarget(sends.macs)
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .shadow(1.dp, shape, ambientColor = Color.Black.copy(alpha = .05f), spotColor = Color.Black.copy(alpha = .05f))
            .clip(shape)
            .background(p.surface)
            .border(1.dp, p.line, shape)
            .pressable(shape, onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DevIcon(Icons.Laptop)
        val body = Modifier.weight(1f)
        when {
            sends.macs == null -> TargetBody(if (sends.macsError != null) "读不到电脑列表" else "正在查找电脑", sends.macsError.orEmpty(), if (sends.macsError != null) false else null, modifier = body)
            chosen != null && chosen.online -> TargetBody(chosen.name, "在线", true, modifier = body)
            chosen != null -> TargetBody(chosen.name, "不在线 · ${usedAgo(Instant.ofEpochMilli(chosen.lastUsedAt))}", false, modifier = body)
            auto != null -> TargetBody(auto.name, "在线 · 自动选择", true, modifier = body)
            else -> TargetBody("没有在线的电脑", "Mac 上打开听·写客户端后自动出现", false, modifier = body)
        }
        Ico(Icons.ChevDown, p.ink3)
    }
}

/** 发送到: 自动 and every Mac; ones not online fade but can still be chosen. */
@Composable
private fun TargetSheet(m: AppModel, onDismiss: () -> Unit) {
    val p = Tokens.palette
    val sends = m.sends
    BottomSheet(onDismiss) {
        Text("发送到", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp))
        val auto = autoTarget(sends.macs)
        Pick(Icons.Send, "自动", auto?.let { "最近在用的：${it.name}" } ?: "没有在线的电脑", null, picked = sends.target == 0L) {
            sends.choose(0)
            onDismiss()
        }
        for (d in sends.macs.orEmpty()) {
            Pick(Icons.Laptop, d.name, macStatus(d), d.online, picked = sends.target == d.id, dimmed = !d.online) {
                sends.choose(d.id)
                onDismiss()
            }
        }
    }
}

private fun macStatus(d: Device): String = when {
    !d.online -> usedAgo(Instant.ofEpochMilli(d.lastUsedAt))
    d.lastActiveAt > 0 -> "在线 · ${activeAgo(Instant.ofEpochMilli(d.lastActiveAt))}"
    else -> "在线"
}

@Composable
private fun Pick(icon: ImageVector, name: String, sub: String, dot: Boolean?, picked: Boolean, dimmed: Boolean = false, onClick: () -> Unit) {
    val p = Tokens.palette
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .pressable(RoundedCornerShape(14.dp), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DevIcon(icon)
        TargetBody(name, sub, dot, dimmed, Modifier.weight(1f))
        if (picked) Ico(Icons.Check, p.accent)
    }
}

/** What is being heard, in a card edged with the recording colour (.sent.live). */
@Composable
private fun LiveCard(text: String, cancelling: Boolean) {
    val p = Tokens.palette
    Card(lerp(p.line, p.rec, .45f), 1.5f) {
        Text(
            text,
            color = p.ink3,
            fontSize = 16.sp,
            lineHeight = 25.6.sp,
            textDecoration = if (cancelling) TextDecoration.LineThrough else null,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).alpha(if (cancelling) .55f else 1f),
        )
        Foot { Text("正在识别…", color = p.ink3, fontSize = 12.5.sp, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun SentCard(r: SendRecord, onCopy: () -> Unit, onResend: () -> Unit) {
    val p = Tokens.palette
    Card(p.line, 1f) {
        FreshText(r)
        Foot {
            val tint = if (r.state == SendState.Failed) p.rec else p.ink3
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                when (r.state) {
                    SendState.Sending -> Spinner(p.ink3, 12.dp, 1.5.dp)
                    SendState.Failed -> Ico(Icons.Alert, tint, 14.dp)
                    SendState.Sent -> Ico(Icons.Check, p.accent, 14.dp)
                }
                Text(
                    when (r.state) {
                        SendState.Sending -> if (r.to != null) "正在发送到 ${r.to}" else "正在发送到电脑"
                        SendState.Failed -> r.error
                        SendState.Sent -> "已发送到 ${r.to} · ${sentTime(Instant.ofEpochMilli(r.at))}"
                    },
                    color = tint, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (r.state != SendState.Sending) {
                IconBtn(Icons.Copy, onCopy)
                IconBtn(Icons.Retry, onResend)
            }
        }
    }
}

/** The text, at most four lines, underlined for a moment once it has arrived (.sent .fresh). */
@Composable
private fun FreshText(r: SendRecord) {
    val p = Tokens.palette
    val age = System.currentTimeMillis() - r.at
    val fade = remember(r.at) { Animatable(if (r.state == SendState.Sent && age < FRESH_MS) 1f else 0f) }
    LaunchedEffect(r.at) {
        if (fade.value > 0f) {
            delay(FRESH_MS - age)
            fade.animateTo(0f, tween(FADE_MS))
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    SelectionContainer {
        Text(
            r.text,
            color = p.ink,
            fontSize = 16.sp,
            lineHeight = 25.6.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout = it },
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).drawBehind {
                val l = layout
                val a = fade.value
                if (l != null && a > 0f) {
                    for (i in 0 until l.lineCount) {
                        val y = l.getLineBaseline(i) + 5.sp.toPx()
                        drawLine(p.accent.copy(alpha = a), Offset(l.getLineLeft(i), y), Offset(l.getLineRight(i), y), 2.sp.toPx())
                    }
                }
            },
        )
    }
}

@Composable
private fun Card(edge: Color, width: Float, content: @Composable () -> Unit) {
    val p = Tokens.palette
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .border(width.dp, edge, shape)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
    ) { content() }
}

/** At the height of its buttons in every state, so a card keeps its size as it goes from live to sent. */
@Composable
private fun Foot(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun IconBtn(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).pressable(CircleShape, onClick = onClick), contentAlignment = Alignment.Center) {
        Ico(icon, Tokens.palette.ink3, 19.dp)
    }
}
