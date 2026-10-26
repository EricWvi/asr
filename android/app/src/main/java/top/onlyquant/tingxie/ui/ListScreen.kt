package top.onlyquant.tingxie.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.onlyquant.tingxie.AppModel
import top.onlyquant.tingxie.Route
import top.onlyquant.tingxie.data.Doc
import top.onlyquant.tingxie.data.DocPatch
import top.onlyquant.tingxie.data.groupLabel
import top.onlyquant.tingxie.data.userMessage
import top.onlyquant.tingxie.ui.theme.Tokens
import top.onlyquant.tingxie.ui.theme.TintNames
import top.onlyquant.tingxie.ui.theme.tint

/** Distance from the screen bottom to floating controls: 28dp above the system navigation. */
@Composable
fun bottomGap(): androidx.compose.ui.unit.Dp {
    val nav = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    return nav + 28.dp
}

/** Search state survives opening a result and coming back. */
class SearchState {
    var query by mutableStateOf(TextFieldValue(""))
    var words by mutableStateOf<List<String>>(emptyList())
    var results by mutableStateOf<List<Doc>?>(null)
    var filter by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ListScreen(m: AppModel, search: SearchState) {
    val p = Tokens.palette
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var sheetDoc by remember { mutableStateOf<Doc?>(null) }
    var renaming by remember { mutableStateOf<Pair<Doc, TextFieldValue>?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    val ime = WindowInsets.isImeVisible

    LaunchedEffect(Unit) { m.refresh() }

    fun runSearch() {
        val words = search.query.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(8)
        focus.clearFocus()
        if (words.isEmpty()) return
        scope.launch {
            try {
                search.results = m.api!!.search(words.joinToString(" "))
                search.words = words
            } catch (e: Exception) {
                m.toast(e.userMessage())
            }
        }
    }

    fun clearSearch() {
        search.query = TextFieldValue("")
        search.words = emptyList()
        search.results = null
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(start = 20.dp, end = 16.dp)) {
                Row(
                    Modifier.padding(top = 28.dp).height(64.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("文稿", color = p.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                    Box(
                        Modifier
                            .offset(x = 7.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (menuOpen) p.selected else Color.Transparent)
                            .pressable(CircleShape) { menuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Avatar(m.me?.name ?: "", m.avatarUrl(), 34.dp, 14.sp)
                        if (menuOpen) UserMenu(m, onDismiss = { menuOpen = false }, onLogout = { menuOpen = false; confirmLogout = true })
                    }
                }
                SearchBar(search.query, onChange = {
                    search.query = it
                    if (it.text.isBlank()) clearSearch()
                }, onSearch = ::runSearch, onClear = ::clearSearch)
                Row(Modifier.padding(top = 6.dp).offset(x = (-7).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (name in TintNames) {
                        FilterDot(name, active = search.filter, onClick = { search.filter = if (search.filter == name) null else name })
                    }
                }
            }
            DocList(m, search, onLong = { sheetDoc = it }, pressedId = sheetDoc?.id, modifier = Modifier.weight(1f))
            if (ime) Box(Modifier.imePadding())
        }

        if (!ime) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Brush.verticalGradient(0f to p.bg.copy(alpha = 0f), .55f to p.bg.copy(alpha = .85f), 1f to p.bg)),
            )
            Fab(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomGap())) { m.newDoc() }
        }

        m.snack?.let { s ->
            Snack(s, onDone = { if (m.snack?.id == s.id) m.snack = null },
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottomGap() + 82.dp))
        }
    }

    sheetDoc?.let { d ->
        DocSheet(m, d, onDismiss = { sheetDoc = null }, onRename = {
            sheetDoc = null
            renaming = d to selectAll(d.title)
        })
    }
    renaming?.let { (d, value) ->
        val save = {
            val t = value.text.trim()
            if (t.isNotEmpty() && t != d.title) m.library.edit(d.id, DocPatch(title = t))
            renaming = null
        }
        Dlg(onDismiss = { renaming = null }, title = "重命名", content = {
            DlgField(value, { renaming = d to it }, onDone = save)
        }) {
            Btn("取消", BtnKind.Ghost) { renaming = null }
            Btn("保存", BtnKind.Primary, onClick = save)
        }
    }
    if (confirmLogout) {
        Dlg(onDismiss = { confirmLogout = false }, title = "退出登录？") {
            Btn("取消", BtnKind.Ghost) { confirmLogout = false }
            Btn("退出", BtnKind.Danger) {
                confirmLogout = false
                m.signOut()
            }
        }
    }
}

@Composable
private fun SearchBar(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, onSearch: () -> Unit, onClear: () -> Unit) {
    val p = Tokens.palette
    BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = TextStyle(color = p.ink, fontSize = 16.sp),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.chip)
                    .padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Ico(Icons.Search, p.ink2, 18.dp)
                Box(Modifier.weight(1f)) {
                    if (value.text.isEmpty()) Text("搜索", color = p.ink3, fontSize = 16.sp)
                    inner()
                }
                if (value.text.isNotEmpty()) {
                    Box(Modifier.size(36.dp).pressable(CircleShape, onClick = onClear), contentAlignment = Alignment.Center) {
                        Ico(Icons.X, p.ink3, 18.dp)
                    }
                }
            }
        },
    )
}

@Composable
private fun FilterDot(name: String, active: String?, onClick: () -> Unit) {
    val p = Tokens.palette
    val t = p.tint(name)
    val on = active == name
    Box(Modifier.size(46.dp).clip(CircleShape).combinedClickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(26.dp)
                .alpha(if (active != null && !on) .35f else 1f)
                .drawBehind {
                    if (on) {
                        // 3dp gap of background, then a 2dp ring in the deep shade.
                        drawCircle(t.deep, radius = size.minDimension / 2 + 5.dp.toPx())
                        drawCircle(p.bg, radius = size.minDimension / 2 + 3.dp.toPx())
                    }
                }
                .clip(CircleShape)
                .background(t.fill)
                .border(1.dp, Color.Black.copy(alpha = .08f), CircleShape),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocList(m: AppModel, search: SearchState, onLong: (Doc) -> Unit, pressedId: Long?, modifier: Modifier) {
    val p = Tokens.palette
    val filter = search.filter
    val results = search.results
    val all = m.library.list
    val shown = (results ?: all ?: emptyList()).filter { filter == null || it.color == filter }
    val bottom = PaddingValues(start = 8.dp, end = 8.dp, bottom = 170.dp)

    when {
        results != null && shown.isEmpty() -> Empty("没有找到「${search.words.joinToString(" ")}」", modifier)
        results == null && all == null -> Box(modifier)
        results == null && all!!.isEmpty() -> Empty("还没有文稿", modifier)
        shown.isEmpty() -> Empty("没有这种颜色的文稿", modifier)
        else -> LazyColumn(modifier.fillMaxWidth(), contentPadding = bottom) {
            if (results != null) {
                stickyHeader { GroupHeader("找到 ${shown.size} 篇") }
                items(shown, key = { it.id }) { d ->
                    DocRow(d, search.words, pressed = pressedId == d.id, onClick = { m.open(d.id, search.words) }, onLong = { onLong(d) })
                }
            } else {
                val groups = shown.groupBy { groupLabel(it.updated) }
                for ((label, docs) in groups) {
                    stickyHeader(key = "g-$label") { GroupHeader(label) }
                    items(docs, key = { it.id }) { d ->
                        DocRow(d, emptyList(), pressed = pressedId == d.id, onClick = { m.open(d.id) }, onLong = { onLong(d) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String, modifier: Modifier) = Box(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp), contentAlignment = Alignment.TopCenter) {
    Text(text, color = Tokens.palette.ink3, fontSize = 14.sp)
}

@Composable
private fun GroupHeader(text: String) {
    val p = Tokens.palette
    Text(
        text, color = p.ink3, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().background(p.bg).padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** Marks each search word in [text] with [bg], ignoring case. */
fun marked(text: String, words: List<String>, bg: Color, fg: Color? = null): AnnotatedString = buildAnnotatedString {
    append(text)
    val lower = text.lowercase()
    for (w in words) {
        val lw = w.lowercase()
        var i = lower.indexOf(lw)
        while (i >= 0 && lw.isNotEmpty()) {
            addStyle(SpanStyle(background = bg, color = fg ?: Color.Unspecified), i, i + lw.length)
            i = lower.indexOf(lw, i + lw.length)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocRow(d: Doc, words: List<String>, pressed: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val p = Tokens.palette
    val t = p.tint(d.color)
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (down || pressed) p.selected else Color.Transparent)
            .combinedClickable(source, indication = null, onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(20.dp, 26.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(t.fill)
                .border(1.dp, Color.Black.copy(alpha = .06f), RoundedCornerShape(5.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(marked(d.title, words, t.mark, p.ink), color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.snippet.isNotEmpty()) {
                Text(
                    marked(d.snippet, words, t.mark, p.ink2), color = p.ink3, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Fab(modifier: Modifier, onClick: () -> Unit) {
    val p = Tokens.palette
    val bg = if (p.dark) lerp(p.surface, Color.White, .06f) else p.surface
    Box(
        modifier
            .size(68.dp)
            .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .28f), spotColor = Color.Black.copy(alpha = if (p.dark) .7f else .28f))
            .background(bg, CircleShape)
            .border(1.dp, p.line, CircleShape)
            .pressable(CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(Icons.Plus, null, tint = p.accent, modifier = Modifier.size(30.dp))
    }
}

/** The avatar menu: send to computer, profile, devices, sign out. */
@Composable
private fun UserMenu(m: AppModel, onDismiss: () -> Unit, onLogout: () -> Unit) {
    PopMenu(onDismiss, offsetY = 44.dp) {
        MenuItem(Icons.Send, "发送到电脑") { onDismiss(); m.push(Route.Send) }
        MenuItem(Icons.User, "个人资料") { onDismiss(); m.push(Route.Profile) }
        MenuItem(Icons.Devices, "设备") { onDismiss(); m.push(Route.Devices) }
        MenuItem(Icons.Logout, "退出登录", onClick = onLogout)
    }
}

/** A floating menu card under its anchor's right edge (.menu). */
@Composable
fun PopMenu(onDismiss: () -> Unit, offsetY: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    val p = Tokens.palette
    val density = LocalDensity.current
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd,
        offset = with(density) { androidx.compose.ui.unit.IntOffset(0, offsetY.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .padding(start = 16.dp, bottom = 16.dp, end = 4.dp)
                .width(208.dp)
                .shadow(24.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = .22f), spotColor = Color.Black.copy(alpha = .22f))
                .background(p.surface, RoundedCornerShape(16.dp))
                .border(1.dp, p.line, RoundedCornerShape(16.dp))
                .padding(6.dp),
        ) { content() }
    }
}

/** Long press on a document: colour, rename, copy, share, delete. */
@Composable
private fun DocSheet(m: AppModel, d: Doc, onDismiss: () -> Unit, onRename: () -> Unit) {
    val p = Tokens.palette
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var color by rememberSaveable { mutableStateOf(d.color) }
    BottomSheet(onDismiss) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(16.dp, 20.dp).clip(RoundedCornerShape(5.dp)).background(p.tint(color).fill))
            Text(d.title, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (name in TintNames) {
                ColorSwatch(p.tint(name).base, selected = name == color, size = 40.dp) {
                    color = name
                    if (name != d.color) m.library.edit(d.id, DocPatch(color = name))
                    onDismiss()
                }
            }
        }
        MenuItem(Icons.Edit, "重命名", height = 52.dp, gap = 16.dp, onClick = onRename)
        MenuItem(Icons.Copy, "复制 Markdown", height = 52.dp, gap = 16.dp) {
            onDismiss()
            scope.launch {
                try {
                    val full = m.library.fetch(d.id)
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(full.title, exportMarkdown(full))))
                    m.toast("已复制 Markdown")
                } catch (e: Exception) {
                    m.toast(e.userMessage())
                }
            }
        }
        MenuItem(Icons.Share, "分享 .md 文件", height = 52.dp, gap = 16.dp) {
            onDismiss()
            scope.launch {
                try {
                    shareMarkdown(context, m.library.fetch(d.id))
                } catch (e: Exception) {
                    m.toast(e.userMessage())
                }
            }
        }
        Divider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        MenuItem(Icons.Trash, "删除", danger = true, height = 52.dp, gap = 16.dp) {
            onDismiss()
            m.deleteDoc(d.id, d.title)
        }
    }
}

/** A colour choice: the pastel, ringed in accent when chosen. */
@Composable
fun ColorSwatch(color: Color, selected: Boolean, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val p = Tokens.palette
    Box(
        Modifier
            .size(size)
            .drawBehind {
                if (selected) {
                    drawCircle(p.accent, radius = this.size.minDimension / 2 + 4.dp.toPx())
                    drawCircle(p.surface, radius = this.size.minDimension / 2 + 2.dp.toPx())
                }
            }
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.Black.copy(alpha = .08f), CircleShape)
            .combinedClickable(onClick = onClick),
    )
}
