package top.onlyquant.tingxie.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as GRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.onlyquant.tingxie.AppModel
import top.onlyquant.tingxie.BuildConfig
import top.onlyquant.tingxie.data.UpdateState
import top.onlyquant.tingxie.data.CropView
import top.onlyquant.tingxie.data.userMessage
import top.onlyquant.tingxie.ui.theme.Tokens
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** A screen's top bar (.appbar): back, then the title. */
@Composable
fun AppBar(m: AppModel, title: String) {
    val p = Tokens.palette
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).pressable(CircleShape) { m.back() },
            contentAlignment = Alignment.Center,
        ) { Ico(Icons.Back, p.ink2, 26.dp) }
        Text(
            title, color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
    }
}

/**
 * 个人资料: the avatar with a camera badge, name and email, and buttons to
 * change or remove the avatar. Changing opens the system photo picker,
 * then a full-screen round crop.
 */
@Composable
fun ProfileScreen(m: AppModel) {
    val p = Tokens.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    val me = m.me
    val hasAvatar = !m.avatarUrl().isNullOrEmpty()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = runCatching { withContext(Dispatchers.IO) { decodePicked(context, uri) } }.getOrNull()
            if (bmp == null) m.toast("无法打开这张图片") else picked = bmp
        }
    }
    fun pick() {
        if (!m.online) return m.toast("离线，只能查看")
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    picked?.let { bmp ->
        CropScreen(bmp, onCancel = { picked = null }, onSave = { image ->
            m.setAvatar(image, "image/webp")
            picked = null
        })
        return
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            AppBar(m, "个人资料")
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The badge sticks out of the circle, so the press is not clipped to it.
                Box(Modifier.size(104.dp)) {
                    Avatar(me?.name ?: "", m.avatarUrl(), 104.dp, 40.sp, Modifier.pressable(CircleShape) { pick() })
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(2.dp, 2.dp)
                            .size(34.dp)
                            .shadow(2.dp, CircleShape)
                            .background(p.surface, CircleShape)
                            .border(1.dp, p.line, CircleShape)
                            .pressable(CircleShape) { pick() },
                        contentAlignment = Alignment.Center,
                    ) { Ico(Icons.Camera, p.ink2, 17.dp) }
                }
                Text(me?.name ?: "", color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                if (!me?.email.isNullOrEmpty()) Text(me.email, color = p.ink2, fontSize = 14.5.sp, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("更换头像", enabled = !busy) { pick() }
                    if (hasAvatar) {
                        Btn("移除头像", BtnKind.Ghost, enabled = !busy) {
                            if (!m.online) return@Btn m.toast("离线，只能查看")
                            busy = true
                            scope.launch {
                                try {
                                    m.removeAvatar()
                                } catch (e: Exception) {
                                    m.toast(e.userMessage())
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            About(m)
        }
        // The card below shows the download itself.
        m.snack?.takeUnless { it.sticky }?.let { s ->
            Snack(s, onDone = { if (m.snack?.id == s.id) m.snack = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomGap() + 96.dp))
        }
    }
}

/** The app's version and updating it (.about). */
@Composable
private fun About(m: AppModel) {
    val p = Tokens.palette
    val s = m.updater.state
    val shape = RoundedCornerShape(16.dp)
    val (sub, failed) = when (s) {
        UpdateState.Idle -> null to false
        UpdateState.Checking -> "正在检查" to false
        UpdateState.UpToDate -> "已是最新版本" to false
        UpdateState.CheckFailed -> "检查失败，请重试" to true
        is UpdateState.Available -> "有新版本 ${s.app.version}" to false
        is UpdateState.Downloading -> "正在下载 ${s.percent}%" to false
        is UpdateState.DownloadFailed -> "下载失败，请重试" to true
        is UpdateState.BadFile -> "安装包校验失败，请重试" to true
        is UpdateState.Ready -> "已下载 ${s.app.version}" to false
    }
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = bottomGap())
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .border(1.dp, p.line, shape)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("听·写 ${BuildConfig.VERSION_NAME}", color = p.ink, fontSize = 15.5.sp)
                if (sub != null) Text(sub, color = if (failed) p.rec else p.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 1.dp))
            }
            when (s) {
                UpdateState.Idle, UpdateState.UpToDate -> Btn("检查更新") { act(m) }
                UpdateState.Checking -> Btn("检查中", enabled = false, busy = true) {}
                UpdateState.CheckFailed, is UpdateState.DownloadFailed, is UpdateState.BadFile -> Btn("重试") { act(m) }
                is UpdateState.Available -> Btn("更新", BtnKind.Accent) { act(m) }
                is UpdateState.Downloading -> Btn("取消", BtnKind.Ghost) { m.updater.act() }
                is UpdateState.Ready -> Btn("安装", BtnKind.Accent) { m.updater.act() }
            }
        }
        if (s is UpdateState.Downloading) {
            Box(Modifier.padding(top = 10.dp, end = 4.dp, bottom = 2.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.chip)) {
                Box(Modifier.fillMaxWidth(s.percent / 100f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.accent))
            }
        }
    }
}

private fun act(m: AppModel) {
    if (m.online) m.updater.act() else m.toast("离线，只能查看")
}

/**
 * 裁剪头像: the picture on black under a round frame (diameter 320dp, the
 * outside darkened). Drag to move, pinch to zoom 1–4×; the picture always
 * covers the frame. 保存 crops what is inside to 256×256 and hands it over
 * as WebP; a failure shows next to the button.
 */
@Composable
private fun CropScreen(bmp: Bitmap, onCancel: () -> Unit, onSave: suspend (ByteArray) -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val image = remember(bmp) { bmp.asImageBitmap() }
    var view by remember(bmp) { mutableStateOf<CropView?>(null) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    BackHandler(enabled = !saving) { onCancel() }
    LightSystemBars()

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
            Text("裁剪头像", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
                .onSizeChanged { s ->
                    if (view == null || view!!.stageW != s.width.toFloat() || view!!.stageH != s.height.toFloat()) {
                        val r = minOf(with(density) { 160.dp.toPx() }, s.width / 2f - with(density) { 16.dp.toPx() }, s.height / 2f - with(density) { 16.dp.toPx() })
                        view = CropView(bmp.width, bmp.height, s.width.toFloat(), s.height.toFloat(), r)
                    }
                }
                .pointerInput(bmp) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (saving) return@detectTransformGestures
                        view = view?.let { v -> v.zoomTo(v.zoom * zoom, centroid.x, centroid.y).panBy(pan.x, pan.y) }
                    }
                },
        ) {
            val v = view
            if (v != null) {
                val ring = with(density) { 1.5.dp.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val k = v.scale
                    drawImage(
                        image,
                        dstOffset = IntOffset(v.x.roundToInt(), v.y.roundToInt()),
                        dstSize = IntSize((v.w * k).roundToInt(), (v.h * k).roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                    val c = Offset(size.width / 2, size.height / 2)
                    val hole = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(GRect(Offset.Zero, size))
                        addOval(GRect(c, v.r))
                    }
                    drawPath(hole, Color.Black.copy(alpha = .62f))
                    drawCircle(Color.White.copy(alpha = .85f), v.r + ring / 2, c, style = Stroke(ring))
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = bottomGap()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.height(44.dp).pressable(RoundedCornerShape(22.dp), enabled = !saving, onClick = onCancel).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("取消", color = Color.White, fontSize = 16.sp) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (failed) Text("保存失败，请重试", color = Color(0xFFFF6258), fontSize = 14.sp)
            }
            Row(
                Modifier
                    .height(44.dp)
                    .widthIn(min = 96.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = if (saving) .6f else 1f))
                    .pressable(RoundedCornerShape(22.dp), enabled = !saving && view != null) {
                        val v = view ?: return@pressable
                        saving = true
                        failed = false
                        scope.launch {
                            try {
                                onSave(withContext(Dispatchers.Default) { cropToWebp(bmp, v) })
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failed = true
                            } finally {
                                saving = false
                            }
                        }
                    }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (saving) Spinner(Color(0xFF1D1D1F), 16.dp)
                Text(if (saving) "保存中" else "保存", color = Color(0xFF1D1D1F), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** White status and navigation bar icons while on black. */
@Composable
private fun LightSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val c = WindowCompat.getInsetsController(window, view)
        val status = c.isAppearanceLightStatusBars
        val nav = c.isAppearanceLightNavigationBars
        c.isAppearanceLightStatusBars = false
        c.isAppearanceLightNavigationBars = false
        onDispose {
            c.isAppearanceLightStatusBars = status
            c.isAppearanceLightNavigationBars = nav
        }
    }
}

private const val MAX_SIDE = 2048
private const val AVATAR_SIDE = 256

/** Decodes the picked image (HEIC too), upright, its long side at most 2048px. */
private fun decodePicked(context: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val (w, h) = info.size.width to info.size.height
        val long = maxOf(w, h)
        if (long > MAX_SIDE) d.setTargetSize(w * MAX_SIDE / long, h * MAX_SIDE / long)
    }

/** What the frame shows, as a 256×256 WebP at quality 90. */
private fun cropToWebp(bmp: Bitmap, v: CropView): ByteArray {
    val (l, t, s) = v.source()
    val out = Bitmap.createBitmap(AVATAR_SIDE, AVATAR_SIDE, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(out).drawBitmap(
        bmp,
        Rect(l.roundToInt(), t.roundToInt(), (l + s).roundToInt(), (t + s).roundToInt()),
        RectF(0f, 0f, AVATAR_SIDE.toFloat(), AVATAR_SIDE.toFloat()),
        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
    )
    val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    return ByteArrayOutputStream().also { out.compress(format, 90, it) }.toByteArray()
}
