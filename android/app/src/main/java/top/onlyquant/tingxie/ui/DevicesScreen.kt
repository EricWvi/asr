package top.onlyquant.tingxie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.onlyquant.tingxie.AppModel
import top.onlyquant.tingxie.data.Device
import top.onlyquant.tingxie.data.signedInOn
import top.onlyquant.tingxie.data.usedAgo
import top.onlyquant.tingxie.data.userMessage
import top.onlyquant.tingxie.ui.theme.Tokens
import top.onlyquant.tingxie.ui.theme.tint
import java.time.Instant

/**
 * 设备: the signed-in Android and Mac clients, read-only (a device can only
 * sign itself out). This one comes first, marked 本机, then online Macs,
 * then the rest by last use; the list refreshes every 10 s while shown, as the
 * web dialog does, so a Mac's online state stays current.
 */
@Composable
fun DevicesScreen(m: AppModel) {
    val p = Tokens.palette
    val mint = p.tint("mint")
    var devices by remember { mutableStateOf<List<Device>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val self = m.api?.session?.deviceId

    LaunchedEffect(Unit) {
        while (true) {
            val a = m.api ?: break
            try {
                devices = a.devices().sortedWith(compareByDescending<Device> { it.id == self }.thenByDescending { it.online }.thenByDescending { it.lastUsedAt })
                error = null
            } catch (e: Exception) {
                if (devices == null) error = e.userMessage()
            }
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize().background(p.bg).statusBarsPadding()) {
        AppBar(m, "设备")
        val list = devices
        when {
            list == null -> error?.let { Note(it) }
            list.isEmpty() -> Note("还没有登录过的设备")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                items(list, key = { it.id }) { d -> DeviceRow(d, d.id == self, mint.deep) }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Tokens.palette.ink3, fontSize = 14.sp)
    }
}

@Composable
private fun DeviceRow(d: Device, self: Boolean, onlineDot: androidx.compose.ui.graphics.Color) {
    val p = Tokens.palette
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.chip),
            contentAlignment = Alignment.Center,
        ) { Ico(if (d.platform == "mac") Icons.Laptop else Icons.Phone, p.ink2) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.name, color = p.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (self) {
                    Box(
                        Modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(p.accent.copy(alpha = .12f)).padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("本机", color = p.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp) }
                }
            }
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val sub = p.ink2
                if (d.online) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(onlineDot))
                    Text("在线", color = sub, fontSize = 13.sp)
                } else {
                    Text(usedAgo(Instant.ofEpochMilli(d.lastUsedAt)), color = sub, fontSize = 13.sp)
                }
                Text("·", color = sub, fontSize = 13.sp)
                Text(if (d.platform == "mac") "Mac" else "安卓", color = sub, fontSize = 13.sp)
                Text("·", color = sub, fontSize = 13.sp)
                Text(signedInOn(Instant.ofEpochMilli(d.createdAt)), color = sub, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
