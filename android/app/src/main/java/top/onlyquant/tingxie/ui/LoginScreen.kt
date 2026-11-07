package top.onlyquant.tingxie.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.onlyquant.tingxie.AppModel
import top.onlyquant.tingxie.R
import top.onlyquant.tingxie.ui.theme.Tokens

/**
 * The brand line: 听, a small dot, 写 in a serif, as on the confirm page
 * (.brand-name and .brand-dot: a 3dp dot with 4dp on each side).
 */
@Composable
fun BrandName(size: androidx.compose.ui.unit.TextUnit = 30.sp) {
    val ink = Tokens.palette.ink
    val style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size)
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "听·写" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("听", style = style, color = ink, modifier = Modifier.clearAndSetSemantics {})
        Box(Modifier.size(3.dp).clip(CircleShape).background(ink))
        Text("写", style = style, color = ink, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
fun LoginScreen(m: AppModel) {
    val p = Tokens.palette
    val context = LocalContext.current
    var editing by remember { mutableStateOf<TextFieldValue?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 28.dp, end = 28.dp, top = 36.dp, bottom = 24.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.logo), null, Modifier.width(84.dp))
            BrandName()
        }
        Column(
            Modifier.fillMaxWidth().padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            m.loginMsg?.let { Text(it.text, color = if (it.error) p.rec else p.ink2, fontSize = 14.sp) }
            BigButton(busy = m.loginBusy, onClick = { m.startLogin(context) })
            Row(
                Modifier
                    .height(36.dp)
                    .pressable(RoundedCornerShape(18.dp)) { editing = selectAll(m.server) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Ico(Icons.Server, p.ink3, 15.dp)
                Text(m.server.substringAfter("://"), color = p.ink3, fontSize = 13.5.sp)
            }
        }
    }

    editing?.let { value ->
        val save = {
            m.setServerUrl(value.text)
            editing = null
        }
        Dlg(onDismiss = { editing = null }, title = "服务器", content = {
            DlgField(value, { editing = it }, onDone = save, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }) {
            Btn("取消", BtnKind.Ghost) { editing = null }
            Btn("保存", BtnKind.Primary, onClick = save)
        }
    }
}

@Composable
private fun BigButton(busy: Boolean, onClick: () -> Unit) {
    val p = Tokens.palette
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .alpha(if (busy) .7f else 1f)
            .shadow(10.dp, shape, ambientColor = p.accent, spotColor = p.accent.copy(alpha = .7f))
            .background(p.accent, shape)
            .pressable(shape, pressed = Color.Black.copy(alpha = .08f), enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) Spinner(Color.White)
            Text(if (busy) "正在登录" else "登录", color = Color.White, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
