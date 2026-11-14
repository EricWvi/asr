package top.onlyquant.tingxie.ui

import android.content.ClipData
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import top.onlyquant.tingxie.AppModel
import top.onlyquant.tingxie.Route
import top.onlyquant.tingxie.data.ApiError
import top.onlyquant.tingxie.data.Doc
import top.onlyquant.tingxie.data.DocPatch
import top.onlyquant.tingxie.data.SaveStatus
import top.onlyquant.tingxie.data.fitting
import top.onlyquant.tingxie.data.mapped
import top.onlyquant.tingxie.data.plus
import top.onlyquant.tingxie.data.Change
import top.onlyquant.tingxie.voice.DictationTarget
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import top.onlyquant.tingxie.data.shortTime
import top.onlyquant.tingxie.data.userMessage
import top.onlyquant.tingxie.ui.theme.TintNames
import top.onlyquant.tingxie.ui.theme.Tokens
import top.onlyquant.tingxie.ui.theme.tint

@Composable
fun DocScreen(m: AppModel, route: Route.Doc) {
    val p = Tokens.palette
    val id = route.id
    val lib = m.library
    val doc = lib.doc(id)
    val scope = rememberCoroutineScope()
    var failed by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        try {
            lib.fetch(id)
        } catch (e: ApiError) {
            if (e.status == 404) {
                m.toast("内容不存在或已被删除")
                m.back()
            } else if (lib.doc(id) == null) failed = e.userMessage()
        } catch (e: Exception) {
            if (lib.doc(id) == null) failed = e.userMessage()
        }
    }

    // Leaving: a new document nobody wrote in is thrown away.
    DisposableEffect(id) {
        onDispose {
            if (m.tidier.busyWith == id) m.tidier.cancel()
            val d = lib.doc(id)
            if (route.fresh && d != null && d.content.isBlank() && !lib.dirty(id)) {
                scope.launch { runCatching { lib.delete(id) } }
            } else {
                scope.launch { lib.flush(id) }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(p.paper)) {
        if (doc == null) {
            Column(Modifier.statusBarsPadding()) {
                DocBar(m, null, scrolled = false, onMore = {})
                failed?.let {
                    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                        Text(it, color = p.ink3, fontSize = 14.sp)
                    }
                }
            }
            return@Box
        }
        DocBody(m, doc, route)
    }
}

/** A tap on [start, end) of the source, at [dx], [dy] from where its words begin on screen, which is [blockY]. */
private data class Tap(val start: Int, val end: Int, val blockY: Float, val dx: Float, val dy: Float)

/** A block of text in view mode, its words starting at [textStart] in the source and at [x], [y] on screen. */
private data class Block(val start: Int, val end: Int, val textStart: Int, val x: Float, val y: Float, val height: Float) {
    val bottom get() = y + height
}

/**
 * Where a tap between two blocks goes in [text]: onto one of the blank lines
 * between the block ending at [prevEnd] and the one starting at [nextStart]
 * (null at the top or bottom of the text), picked by how far down the gap
 * the tap was ([frac], 0 to 1). With no blank line there, the end of the
 * block above, or else the start of the one below.
 */
internal fun gapOffset(text: String, prevEnd: Int?, nextStart: Int?, frac: Float): Int {
    val from = if (prevEnd == null) 0 else text.indexOf('\n', prevEnd).let { if (it < 0) text.length + 1 else it + 1 }
    // The start of the line the block below begins on; past the end there is no block below.
    val until = if (nextStart == null) text.length + 1 else text.lastIndexOf('\n', (nextStart - 1).coerceAtLeast(0)).let { if (nextStart == 0) 0 else it + 1 }
    val lines = mutableListOf<Int>()
    var at = from
    while (at < until && at <= text.length) {
        val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        if (text.substring(at, end).isBlank()) lines += at
        at = end + 1
    }
    if (lines.isEmpty()) return prevEnd ?: nextStart ?: 0
    return lines[(frac * lines.size).toInt().coerceIn(0, lines.size - 1)]
}

/** Leaving edit mode: the text at [offset] stays where it is on screen once rendered. */
private data class Exit(val offset: Int, val layout: TextLayoutResult, val scrollY: Int, val height: Int)

/** While the view changes, only the code that places the text may scroll. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object NoAutoScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DocBody(m: AppModel, doc: Doc, route: Route.Doc) {
    val p = Tokens.palette
    val t = p.tint(doc.color)
    val lib = m.library
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val scrolled = scroll.value > with(density) { 60.dp.toPx() }
    var menu by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf(false) }
    val blocks = remember(doc.id) { mutableStateMapOf<Int, Block>() }
    var bodyTop by remember { mutableStateOf(0f) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val talk = remember(doc.id) { DocDictation(m, doc.id, scope) }
    DisposableEffect(talk) { onDispose { if (m.voice.busyWith(talk)) m.voice.abort() } }
    // Text awaiting tidying is kept only while its page is open.
    DisposableEffect(doc.id) { onDispose { m.library.dropMarks(doc.id) } }
    DisposableEffect(talk) {
        m.tidier.onPlaced = { id, placed -> if (id == doc.id) talk.showFresh(placed) }
        onDispose { m.tidier.onPlaced = { _, _ -> } }
    }
    // While tidying, a glint sweeps over the text being tidied.
    val tidying = m.tidier.busyWith == doc.id
    val shimmer = remember(p, t, density) { Shimmer(p.ink3, t.deep, with(density) { 600.dp.toPx() }) }
    val glowStyle = remember(shimmer) { SpanStyle(brush = shimmer) }
    val glow = if (tidying) lib.marks(doc.id) else emptyList()
    val sweep = if (tidying) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "tidy").animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing)),
            label = "sweep",
        )
    } else null

    // ---------- edit mode ----------
    var editing by remember(doc.id) { mutableStateOf(false) }
    var title by remember(doc.id) { mutableStateOf<TextFieldValue?>(null) }
    var value by remember(doc.id) { mutableStateOf(TextFieldValue(doc.content, TextRange(doc.content.length))) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var tap by remember { mutableStateOf<Tap?>(null) }
    var exit by remember(doc.id) { mutableStateOf<Exit?>(null) }
    // The text is hidden for the moment between swapping views and placing it.
    var placing by remember(doc.id) { mutableStateOf(false) }
    var settling by remember(doc.id) { mutableStateOf(false) }
    var bodyHeight by remember(doc.id) { mutableStateOf(0) }
    val ime = WindowInsets.ime
    val imeTarget = androidx.compose.foundation.layout.WindowInsets.imeAnimationTarget
    val navBar = WindowInsets.navigationBars
    val bringIntoView = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val bodyFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val hl = remember(p, t) { Highlight(p, t) }
    if (!m.online && editing) {
        editing = false
        title = null
        tap = null
        layout = null
        placing = false
        settling = false
    }
    // Text written from elsewhere (dictation, a task box) reaches the editor.
    if (doc.content != value.text) {
        val c = doc.content
        value = TextFieldValue(c, TextRange(value.selection.start.coerceIn(0, c.length), value.selection.end.coerceIn(0, c.length)))
    }

    fun commitTitle() {
        val v = title ?: return
        val tt = v.text.trim()
        if (tt.isNotEmpty() && tt != doc.title) lib.edit(doc.id, DocPatch(title = tt))
        title = null
    }

    fun finishEditing() {
        commitTitle()
        val l = layout
        if (editing && l != null) {
            // Keep the caret's text in place, or the top line if the caret is out of sight.
            val len = l.layoutInput.text.length
            val caret = value.selection.max.coerceIn(0, len)
            val line = l.getLineForOffset(caret)
            val viewTop = scroll.value - bodyTop
            val seen = l.getLineTop(line) >= viewTop && l.getLineBottom(line) <= viewTop + scroll.viewportSize
            val offset = if (seen) caret else l.getOffsetForPosition(Offset(0f, viewTop.coerceAtLeast(0f)))
            blocks.clear()
            exit = Exit(offset, l, scroll.value, bodyHeight)
            placing = true
        }
        layout = null
        tap = null
        settling = false
        editing = false
        focusManager.clearFocus()
        scope.launch { lib.flush(doc.id) }
    }

    fun startEditing(at: Int?) {
        if (!m.online) return
        commitTitle()
        settling = true
        editing = true
        if (at != null) value = value.copy(selection = TextRange(at))
    }

    /**
     * Scrolls once, as the keyboard comes up, to [base], or as far from it as
     * it takes to keep the caret's line clear of the keyboard, then hands
     * scrolling back to the text field.
     */
    suspend fun placeCaret(base: Int) {
        val l = androidx.compose.runtime.snapshotFlow { layout }.first { it != null }!!
        // How tall the text area will be is known as soon as the keyboard starts to rise.
        withTimeoutOrNull(600) { androidx.compose.runtime.snapshotFlow { imeTarget.getBottom(density) }.first { it > 0 } }
        val nav = navBar.getBottom(density)
        val viewport = scroll.viewportSize - (maxOf(imeTarget.getBottom(density), nav) - maxOf(ime.getBottom(density), nav))
        val line = l.getLineForOffset(value.selection.max.coerceIn(0, l.layoutInput.text.length))
        val margin = with(density) { 24.dp.toPx() }
        val top = bodyTop + l.getLineTop(line) - margin
        val bottom = bodyTop + l.getLineBottom(line) + margin
        val to = when {
            bottom > base + viewport -> (bottom - viewport).toInt()
            top < base -> top.toInt().coerceAtLeast(0)
            else -> base
        }
        if (scroll.value != to) scroll.animateScrollTo(to)
        // The scroll range only reaches its full length with the keyboard up.
        withTimeoutOrNull(600) { androidx.compose.runtime.snapshotFlow { ime.getBottom(density) == imeTarget.getBottom(density) }.first { it } }
        if (scroll.value != to) scroll.animateScrollTo(to)
        settling = false
    }

    fun onEdit(next: TextFieldValue) {
        var v = next
        continueList(value.text, v.text, v.selection.start)?.let { (s, c) -> v = TextFieldValue(s, TextRange(c)) }
        if (v.text != value.text) {
            val ch = Change.between(value.text, v.text)!!
            val ranges = lib.marks(doc.id).mapped(ch, manual = true)
            talk.fresh = talk.fresh.mapNotNull { (a, b) -> (ch.map(a, 1) to ch.map(b, -1)).takeIf { it.first < it.second } }
            lib.edit(doc.id, DocPatch(content = v.text), marks = ranges)
        }
        value = v
    }

    BackHandler(editing) { finishEditing() }

    // Dictation goes to the cursor while editing, to the end otherwise.
    talk.insertionPoint = if (editing && title == null) {
        { value.selection.min }
    } else {
        { lib.doc(doc.id)?.content?.length ?: 0 }
    }
    talk.onWritten = { _, b -> if (editing) value = TextFieldValue(lib.doc(doc.id)?.content ?: "", TextRange(b)) }

    // Entering edit mode from a tap: the cursor goes where the finger was,
    // and the tapped block stays where it was on screen.
    LaunchedEffect(tap) {
        val tp = tap ?: return@LaunchedEffect
        val l = androidx.compose.runtime.snapshotFlow { layout.takeIf { editing } }.first { it != null }!!
        val len = l.layoutInput.text.length
        val start = tp.start.coerceIn(0, len)
        val top = l.getLineTop(l.getLineForOffset(start))
        // Measured from where the words begin on both sides, past list marks, "## " or "> ".
        val left = l.getHorizontalPosition(start, true)
        val off = l.getOffsetForPosition(Offset(left + tp.dx, top + tp.dy)).coerceIn(start, tp.end.coerceIn(start, len))
        value = value.copy(selection = TextRange(off))
        val target = (scroll.value + top - tp.blockY).toInt()
        scroll.scrollTo(target)
        placing = false
        bodyFocus.requestFocus()
        placeCaret(target)
        // Last: clearing the key cancels this effect.
        tap = null
    }
    LaunchedEffect(editing, title == null) {
        if (editing && title == null && tap == null) {
            bodyFocus.requestFocus()
            placeCaret(scroll.value)
        }
    }

    // Back in view mode: wait for the text to be rendered (the height stays
    // meanwhile, or the scroll position is lost), then line it up with where it was.
    LaunchedEffect(exit) {
        val e = exit ?: return@LaunchedEffect
        if (doc.content.isNotBlank()) {
            withTimeoutOrNull(1500) { androidx.compose.runtime.snapshotFlow { blocks.size }.first { it > 0 } }
            androidx.compose.runtime.withFrameNanos { }
            blocks.values.filter { it.start <= e.offset }.maxByOrNull { it.start }?.let { b ->
                val start = b.start.coerceIn(0, e.layout.layoutInput.text.length)
                scroll.scrollTo((e.scrollY + b.y - e.layout.getLineTop(e.layout.getLineForOffset(start))).toInt())
            }
        }
        placing = false
        // Last: clearing the key cancels this effect.
        exit = null
    }

    // View mode dictates at the end: keep it in sight.
    val interim = talk.interim
    LaunchedEffect(interim != null, interim) {
        if (interim != null && !editing) scroll.animateScrollTo(scroll.maxValue)
    }
    val dim = if (m.voice.cancelling) SpanStyle(color = p.ink3.copy(alpha = .55f), textDecoration = TextDecoration.LineThrough) else SpanStyle(color = p.ink3)
    val at = talk.at.coerceIn(0, doc.content.length)
    val shown = if (interim == null || editing) doc.content else doc.content.substring(0, at) + interim + doc.content.substring(at)
    val overlays = buildList {
        if (interim != null && interim.isNotEmpty()) add(Overlay(at, at + interim.length, dim))
        for ((a, b) in talk.fresh) add(Overlay(a, b, tag = Backdrops.FRESH, item = t.deep.toArgb().toString()))
        for ((a, b) in glow) add(Overlay(a, b, style = glowStyle))
    }

    // From a search result: scroll the first block with a hit into the middle.
    if (route.hits.isNotEmpty()) {
        LaunchedEffect(doc.id, blocks.size) {
            if (editing || exit != null) return@LaunchedEffect
            val lower = doc.content.lowercase()
            val first = route.hits.minOfOrNull { w -> lower.indexOf(w.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } } ?: return@LaunchedEffect
            val y = blocks.values.filter { it.start <= first }.maxByOrNull { it.start }?.y ?: return@LaunchedEffect
            scroll.scrollTo((bodyTop + y - with(density) { 240.dp.toPx() }).toInt().coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        DocBar(m, doc, scrolled, editing = editing, onMore = { menu = true }, onDone = { finishEditing() })
        if (menu) {
            Box(Modifier.fillMaxWidth()) {
                PopMenu(onDismiss = { menu = false }, offsetY = (-8).dp) {
                    MenuItem(Icons.Copy, "复制 Markdown") {
                        menu = false
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(doc.title, exportMarkdown(doc))))
                            m.toast("已复制 Markdown")
                        }
                    }
                    MenuItem(Icons.Share, "分享 .md 文件") {
                        menu = false
                        scope.launch { shareMarkdown(context, doc) }
                    }
                    Divider(Modifier.padding(horizontal = 6.dp, vertical = 6.dp))
                    MenuItem(Icons.Trash, "删除", danger = true) {
                        menu = false
                        m.toList()
                        m.deleteDoc(doc.id, doc.title)
                    }
                }
            }
        }
        if (!m.online) OfflineBar()
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides if (settling) NoAutoScroll else bringIntoView,
        ) {
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)) {
                TitleField(doc, title, onChange = { title = it }, onStart = {
                    if (m.online) {
                        startEditing(null)
                        settling = false
                        title = TextFieldValue(doc.title, TextRange(doc.title.length))
                    }
                }, onEnter = {
                    commitTitle()
                    settling = true
                    value = value.copy(selection = TextRange(0))
                    bodyFocus.requestFocus()
                })
                Box(Modifier.padding(top = 12.dp)) {
                    Meta(m, doc, onDot = { palette = true })
                    if (palette) ColorPopup(doc.color, onPick = {
                        palette = false
                        if (it != doc.color) lib.edit(doc.id, DocPatch(color = it))
                    }, onDismiss = { palette = false })
                }
                Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(1.dp).background(p.paperLine))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = with(density) { (exit?.height ?: 0).toDp() })
                    .onGloballyPositioned {
                        bodyTop = it.positionInParent().y + with(density) { 22.dp.toPx() }
                        bodyHeight = it.size.height
                    }
                    .graphicsLayer { alpha = if (placing) 0f else 1f }
                    .padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 200.dp),
            ) {
                // Each text lines the band up with the screen itself (glowAnchor).
                androidx.compose.runtime.CompositionLocalProvider(LocalGlow provides remember(shimmer, sweep) { sweep?.let { s -> Glow(shimmer) { s.value } } }) {
                if (editing) {
                    Editor(
                        value, ::onEdit, hl,
                        interim = interim, at = at, interimStyle = dim,
                        fresh = talk.fresh, freshAlpha = { talk.freshAlpha.value }, freshColor = t.deep,
                        glow = glow, glowStyle = glowStyle,
                        onLayout = { layout = it },
                        modifier = Modifier.focusRequester(bodyFocus),
                    )
                } else {
                    val tapper = Modifier.pointerInput(m.online) {
                        detectTapGestures { o ->
                            if (!m.online) return@detectTapGestures
                            val sorted = blocks.values.sortedBy { it.y }
                            val b = sorted.lastOrNull { it.y <= o.y && o.y < it.bottom }
                            tap = if (b != null) {
                                Tap(b.textStart, b.end, b.y, o.x - b.x, o.y - b.y)
                            } else {
                                // Between blocks: onto the blank line there, as on the web.
                                val prev = sorted.lastOrNull { it.bottom <= o.y }
                                val next = sorted.firstOrNull { it.y > o.y }
                                val top = prev?.bottom ?: 0f
                                val frac = if (next == null || next.y <= top) 1f else (o.y - top) / (next.y - top)
                                val at = gapOffset(shown, prev?.end, next?.start, frac)
                                Tap(at, at, o.y, 0f, 0f)
                            }
                            placing = true
                            startEditing(null)
                        }
                    }
                    if (shown.isBlank()) {
                        Text("按住说话，或点这里输入", color = p.ink3, fontSize = 17.sp, lineHeight = 29.75.sp, modifier = Modifier.fillMaxWidth().then(tapper))
                    } else {
                        MarkdownView(
                            shown, t, m.api!!.server, route.hits,
                            onToggle = { off -> toggleTask(m, doc, off) },
                            overlays = overlays,
                            freshAlpha = { talk.freshAlpha.value },
                            onBlock = { b -> blocks[b.start] = Block(b.start, b.end, b.textStart, b.x, b.y, b.height) },
                            modifier = tapper,
                        )
                    }
                }
                }
            }
        }
        }
        if (editing && m.online) Tools(m, doc, talk, onHide = { finishEditing() })
    }
    if (m.online && !editing) {
        Dock(m, doc, talk, onKeyboard = {
            startEditing(doc.content.length)
        }, Modifier.align(Alignment.BottomCenter))
        CancelHint(m, talk, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomGap() + 76.dp))
    }
    if (editing) {
        CancelHint(m, talk, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(bottom = 66.dp))
    }
    m.snack?.let { s ->
        Snack(s, onDone = { if (m.snack?.id == s.id) m.snack = null },
            Modifier.align(Alignment.BottomCenter).then(
                if (editing) Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(bottom = 66.dp)
                else Modifier.padding(bottom = bottomGap() + 82.dp),
            ))
    }
}

/** The title: tap to rename in place; Enter jumps into the text; clearing it keeps the old one. */
@Composable
private fun TitleField(doc: Doc, value: TextFieldValue?, onChange: (TextFieldValue) -> Unit, onStart: () -> Unit, onEnter: () -> Unit) {
    val p = Tokens.palette
    val style = androidx.compose.ui.text.TextStyle(color = p.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, letterSpacing = (-0.26).sp)
    if (value == null) {
        Text(doc.title, style = style, modifier = Modifier.pointerInput(Unit) { detectTapGestures { onStart() } })
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value, { onChange(it.copy(text = it.text.replace("\n", ""))) },
        textStyle = style,
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { onEnter() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** The source with syntax colours; code blocks and fresh text are drawn behind it. */
@Composable
private fun Editor(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    hl: Highlight,
    interim: String?,
    at: Int,
    interimStyle: SpanStyle,
    fresh: List<Pair<Int, Int>>,
    freshAlpha: () -> Float,
    freshColor: Color,
    glow: List<List<Int>>,
    glowStyle: SpanStyle,
    onLayout: (TextLayoutResult) -> Unit,
    modifier: Modifier,
) {
    val p = Tokens.palette
    var l by remember { mutableStateOf<TextLayoutResult?>(null) }
    val transform = remember(hl, interim, at, interimStyle, glow, glowStyle) { SourceTransformation(hl, interim, at, interimStyle, glow, glowStyle) }
    val blocks = remember(value.text) { codeBlocks(value.text) }
    val n = interim?.length ?: 0
    fun tx(o: Int) = if (o <= at || n == 0) o else o + n
    BasicTextField(
        value, onChange,
        textStyle = androidx.compose.ui.text.TextStyle(color = p.ink, fontSize = 17.sp, lineHeight = 29.75.sp),
        cursorBrush = SolidColor(p.accent),
        visualTransformation = transform,
        onTextLayout = { l = it; onLayout(it) },
        modifier = modifier
            .fillMaxWidth()
            .glowAnchor()
            .drawBehind {
                val lay = l ?: return@drawBehind
                val len = lay.layoutInput.text.length
                val pad = 12.dp.toPx()
                for (b in blocks) {
                    val s = tx(b.first).coerceIn(0, len)
                    val e = tx(b.last + 1).coerceIn(0, len)
                    val top = lay.getLineTop(lay.getLineForOffset(s))
                    val bottom = lay.getLineBottom(lay.getLineForOffset(e))
                    drawRoundRect(p.codeBlock, Offset(-pad, top), androidx.compose.ui.geometry.Size(size.width + 2 * pad, bottom - top), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                }
                val alpha = freshAlpha()
                if (fresh.isEmpty() || alpha <= 0f) return@drawBehind
                for (f in fresh) {
                    val fs = tx(f.first).coerceIn(0, len)
                    val fe = tx(f.second).coerceIn(0, len)
                    if (fs >= fe) continue
                    val first = lay.getLineForOffset(fs)
                    val last = lay.getLineForOffset(fe - 1)
                    for (line in first..last) {
                        val left = if (line == first) lay.getHorizontalPosition(fs, true) else lay.getLineLeft(line)
                        val right = if (line == last) lay.getHorizontalPosition(fe, true) else lay.getLineRight(line)
                        val y = lay.getLineBaseline(line) + 5.dp.toPx()
                        drawLine(freshColor.copy(alpha = alpha), Offset(left, y), Offset(right, y), 2.dp.toPx())
                    }
                }
            },
    )
}

private val TOOL_SIDE = 92.dp

/** Above the keyboard while editing: 整理, 按住说话 (at the cursor), hide the keyboard. */
@Composable
private fun Tools(m: AppModel, doc: Doc, talk: DocDictation, onHide: () -> Unit) {
    val p = Tokens.palette
    val pending = m.library.marks(doc.id).isNotEmpty()
    val tidying = m.tidier.busyWith == doc.id
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.surface)
            .drawBehind { drawLine(p.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Both sides are as wide as 「整理中」, which keeps the talk button in the middle.
        Box(Modifier.width(TOOL_SIDE), contentAlignment = Alignment.CenterStart) {
            Row(
                Modifier.height(44.dp).pressable(CircleShape) { tapTidy(m, doc, talk) }.dim(!pending && !tidying).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (tidying) Spinner(p.ink2, 18.dp) else Ico(Icons.Sparkle, p.ink2, 18.dp)
                Text(if (tidying) "整理中" else "整理", color = p.ink2, fontSize = 14.sp)
            }
        }
        HoldButton(m, talk, Modifier.weight(1f).height(42.dp), fontSize = 15.sp, glow = 4.dp, shadow = false, off = tidying)
        // Mirrors 整理: the word, then the icon on the outer edge.
        Box(Modifier.width(TOOL_SIDE), contentAlignment = Alignment.CenterEnd) {
            Row(
                Modifier.height(44.dp).pressable(CircleShape, onClick = onHide).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("键盘", color = p.ink2, fontSize = 14.sp)
                Ico(Icons.KbHide, p.ink2, 18.dp)
            }
        }
    }
}

/**
 * 整理: starts tidying (the earlier underline goes), or cancels it. Not
 * while talking; with nothing to tidy it says so.
 */
private fun tapTidy(m: AppModel, doc: Doc, talk: DocDictation) {
    val tidying = m.tidier.busyWith == doc.id
    if (!tidying && m.voice.phase != top.onlyquant.tingxie.voice.Phase.Idle) return
    if (!tidying) talk.showFresh(emptyList())
    m.tidier.toggle(doc.id)
}

/** Flips "[ ]" and "[x]" at the given offset of the source. */
private fun toggleTask(m: AppModel, doc: Doc, at: Int) {
    // The renderer keeps its first callback, so [doc] may be old: edit what is there now.
    val d = m.library.doc(doc.id) ?: return
    val c = d.content
    if (at + 1 >= c.length || c[at] != '[') return
    val now = if (c[at + 1] == ' ') 'x' else ' '
    // An edit by hand, as on the web: a box inside dictated text drops its mark.
    val ranges = m.library.marks(doc.id).mapped(Change(at + 1, at + 2, 1), manual = true)
    m.library.edit(doc.id, DocPatch(content = c.substring(0, at + 1) + now + c.substring(at + 2)), marks = ranges)
}

@Composable
private fun DocBar(m: AppModel, doc: Doc?, scrolled: Boolean, onMore: () -> Unit, editing: Boolean = false, onDone: () -> Unit = {}) {
    val p = Tokens.palette
    val shadow by animateFloatAsState(if (scrolled) 1f else 0f, label = "bar")
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .drawBehind {
                if (shadow > 0f) {
                    drawRect(Color.Black.copy(alpha = .06f * shadow), topLeft = Offset(0f, size.height), size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx() * 6))
                    drawLine(p.line.copy(alpha = shadow), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
            }
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier
                .height(48.dp)
                .pressable(RoundedCornerShape(24.dp)) { m.back() }
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Ico(Icons.Back, p.ink2, 26.dp)
            Text("文稿", color = p.ink2, fontSize = 16.sp)
        }
        // Once scrolled: the title and its save state, so a long document need not go back to the top.
        Row(
            Modifier.weight(1f).graphicsLayer { alpha = shadow; translationY = (1 - shadow) * 4.dp.toPx() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                doc?.title ?: "", color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (doc != null) SaveChip(m, doc)
        }
        if (doc != null && editing) {
            Box(
                Modifier.height(40.dp).pressable(RoundedCornerShape(20.dp), pressed = p.accent.copy(alpha = .1f), onClick = onDone).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text("完成", color = p.accent, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold) }
        } else if (doc != null) {
            Box(Modifier.size(48.dp).pressable(CircleShape, onClick = onMore), contentAlignment = Alignment.Center) {
                Ico(Icons.MoreV, p.ink2)
            }
        }
    }
}

@Composable
fun OfflineBar() {
    val p = Tokens.palette
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(p.well)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Ico(Icons.CloudOff, p.ink2, 18.dp)
        Text("离线，只能查看", color = p.ink2, fontSize = 13.5.sp)
    }
}

@Composable
private fun Chip(content: @Composable () -> Unit) {
    val p = Tokens.palette
    Row(
        Modifier.height(26.dp).clip(RoundedCornerShape(7.dp)).background(p.well).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) { content() }
}

@Composable
private fun ChipText(text: String, color: Color = Tokens.palette.ink.copy(alpha = .62f)) = Text(text, color = color, fontSize = 12.5.sp)

/** The colour dot, edit time and save state under the title. */
@Composable
private fun Meta(m: AppModel, doc: Doc, onDot: () -> Unit) {
    val p = Tokens.palette
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(p.well).pressable(RoundedCornerShape(7.dp), onClick = onDot),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(p.tint(doc.color).base).border(1.dp, Color.Black.copy(alpha = .12f), CircleShape))
        }
        Chip { ChipText(shortTime(doc.updated)) }
        SaveChip(m, doc)
    }
}

/** 已保存, 保存中, 保存失败 or 离线: under the title, and in the bar once it has scrolled away. */
@Composable
private fun SaveChip(m: AppModel, doc: Doc) {
    val p = Tokens.palette
    val status = m.library.status[doc.id]
    Chip {
        when {
            !m.online -> {
                Ico(Icons.CloudOff, p.ink.copy(alpha = .62f), 13.dp)
                ChipText("离线")
            }
            status == SaveStatus.Error -> ChipText("保存失败", p.rec)
            status == SaveStatus.Saving -> ChipText("保存中")
            else -> ChipText("已保存")
        }
    }
}

/** The six colours in a floating card under the dot (.palette). */
@Composable
private fun ColorPopup(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val p = Tokens.palette
    androidx.compose.ui.window.Popup(
        offset = with(LocalDensity.current) { androidx.compose.ui.unit.IntOffset(0, 36.dp.roundToPx()) },
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Row(
            Modifier
                .padding(bottom = 16.dp, end = 16.dp)
                .shadow(24.dp, RoundedCornerShape(18.dp), ambientColor = Color.Black.copy(alpha = .22f), spotColor = Color.Black.copy(alpha = .22f))
                .background(p.surface, RoundedCornerShape(18.dp))
                .border(1.dp, p.line, RoundedCornerShape(18.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (name in TintNames) ColorSwatch(p.tint(name).base, selected = name == current, size = 34.dp) { onPick(name) }
        }
    }
}

/** The floating bar in view mode: 整理 ｜ 按住说话 ｜ 键盘. */
@Composable
private fun Dock(m: AppModel, doc: Doc, talk: DocDictation, onKeyboard: () -> Unit, modifier: Modifier) {
    DockBar(modifier) {
        val pending = m.library.marks(doc.id).isNotEmpty()
        val tidying = m.tidier.busyWith == doc.id
        DockSide(Icons.Sparkle, if (tidying) "整理中" else "整理", disabled = !pending && !tidying, busy = tidying) { tapTidy(m, doc, talk) }
        HoldButton(m, talk, Modifier.weight(1f).height(56.dp), off = tidying)
        DockSide(Icons.Keyboard, "键盘", onClick = onKeyboard)
    }
}

/**
 * Dictation into a document. View mode writes at the end; the editor moves
 * [at] to its cursor before talking starts. What is heard shows faintly at
 * [at]; the final text goes in as one edit, is marked for tidying and gets
 * the fading underline (.fresh).
 */
class DocDictation(private val m: AppModel, private val id: Long, private val scope: CoroutineScope) : DictationTarget {
    var interim by mutableStateOf<String?>(null)
        private set
    var at by mutableStateOf(0)
    /** Where the insertion point is when talking starts; the end by default. */
    var insertionPoint: () -> Int = { m.library.doc(id)?.content?.length ?: 0 }
    /** Called with [from, to) of the text just written. */
    var onWritten: (Int, Int) -> Unit = { _, _ -> }
    var fresh by mutableStateOf<List<Pair<Int, Int>>>(emptyList())
    val freshAlpha = Animatable(1f)
    private var fading: Job? = null

    override fun begin() {
        at = insertionPoint()
        interim = ""
    }

    override fun interim(text: String) {
        if (interim != null) interim = text
    }

    override fun commit(text: String, error: String?) {
        interim = null
        if (error != null) m.toast(error)
        val d = m.library.doc(id) ?: return
        if (text.isEmpty()) return
        val c = d.content
        val a = at.coerceIn(0, c.length)
        val b = a + text.length
        val ranges = m.library.marks(id).mapped(Change(a, a, text.length), manual = false).plus(a, b)
        m.library.edit(id, top.onlyquant.tingxie.data.DocPatch(content = c.substring(0, a) + text + c.substring(a)), marks = ranges)
        showFresh(listOf(a to b))
        onWritten(a, b)
    }

    override fun cancel() {
        interim = null
    }

    /** Underlines the spans for 2.5 s, then fades them out over 1.2 s; none clears it. */
    fun showFresh(spans: List<Pair<Int, Int>>) {
        fading?.cancel()
        fresh = spans
        if (spans.isEmpty()) return
        fading = scope.launch {
            freshAlpha.snapTo(1f)
            delay(2500)
            freshAlpha.animateTo(0f, androidx.compose.animation.core.tween(1200))
            fresh = emptyList()
        }
    }
}
