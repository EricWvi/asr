package top.onlyquant.tingxie.data

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import top.onlyquant.tingxie.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** latest.json, attached to each GitHub release by the release workflow. */
@Serializable
data class LatestApp(
    val version: String,
    @SerialName("version_code") val versionCode: Int,
    val url: String,
    val sha256: String,
    val size: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState
    data class Available(val app: LatestApp) : UpdateState
    data class Downloading(val app: LatestApp, val percent: Int) : UpdateState
    data class DownloadFailed(val app: LatestApp) : UpdateState
    data class BadFile(val app: LatestApp) : UpdateState
    data class Ready(val app: LatestApp) : UpdateState
}

const val UPDATE_CHECK_EVERY_MS = 24 * 60 * 60 * 1000L

/** Whether an automatic check is due, at most once a day. */
fun updateCheckDue(last: Long, now: Long) = now - last !in 0 until UPDATE_CHECK_EVERY_MS

fun percentOf(done: Long, total: Long) = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Checks GitHub for a newer app, downloads it, verifies it and hands it to
 * the system installer. Runs outside the screens, so a download goes on while
 * the user moves around the app.
 */
class Updater(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val toast: (text: String, action: String?, onAction: (() -> Unit)?, sticky: Boolean) -> Unit,
    private val withdraw: () -> Unit,
) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set

    private var job: Job? = null
    private val dir = File(context.cacheDir, "update")

    private fun apk(app: LatestApp) = File(dir, "tingxie-${app.version}.apk")

    init {
        // The package this very version was installed from has done its job.
        scope.launch(Dispatchers.IO) { File(dir, "tingxie-${BuildConfig.VERSION_NAME}.apk").delete() }
    }

    /** Quietly, at most once a day; only a new version says anything, and only once. */
    fun autoCheck() {
        if (state != UpdateState.Idle) return
        scope.launch {
            val now = System.currentTimeMillis()
            if (!updateCheckDue(prefs.updateCheckedAt(), now)) return@launch
            prefs.setUpdateCheckedAt(now)
            val app = runCatching { fetchLatest() }.getOrNull() ?: return@launch
            if (state != UpdateState.Idle || app.versionCode <= BuildConfig.VERSION_CODE) return@launch
            state = offered(app)
            if (prefs.updatePrompted() != app.version) {
                prefs.setUpdatePrompted(app.version)
                toast("有新版本 ${app.version}", "更新", { act() }, false)
            }
        }
    }

    /** The button on the profile page and the snack bar: whatever the state calls for. */
    fun act() {
        when (val s = state) {
            UpdateState.Idle, UpdateState.UpToDate, UpdateState.CheckFailed -> check()
            is UpdateState.Available -> download(s.app)
            is UpdateState.DownloadFailed -> download(s.app)
            is UpdateState.BadFile -> download(s.app)
            is UpdateState.Downloading -> cancel(s.app)
            is UpdateState.Ready -> install(s.app)
            UpdateState.Checking -> Unit
        }
    }

    private fun check() {
        state = UpdateState.Checking
        job = scope.launch {
            state = try {
                val app = fetchLatest()
                prefs.setUpdateCheckedAt(System.currentTimeMillis())
                if (app.versionCode > BuildConfig.VERSION_CODE) offered(app) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.CheckFailed
            }
        }
    }

    private suspend fun fetchLatest(): LatestApp {
        val req = Request.Builder().url(BuildConfig.UPDATE_URL).build()
        val res = baseHttp.newCall(req).await()
        val body = res.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            withContext(Dispatchers.IO) { it.body.string() }
        }
        return json.decodeFromString(body)
    }

    /** A package downloaded earlier and still good need not be fetched again. */
    private suspend fun offered(app: LatestApp): UpdateState {
        val file = apk(app)
        val kept = withContext(Dispatchers.IO) { file.length() == app.size && sha256Of(file) == app.sha256 }
        return if (kept) UpdateState.Ready(app) else UpdateState.Available(app)
    }

    private fun download(app: LatestApp) {
        state = UpdateState.Downloading(app, 0)
        progress(0)
        job = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { fetchApk(app) }
                if (withContext(Dispatchers.IO) { sha256Of(file) } != app.sha256) {
                    file.delete()
                    withdraw()
                    state = UpdateState.BadFile(app)
                    toast("安装包校验失败", "重试", { act() }, false)
                    return@launch
                }
                withdraw()
                state = UpdateState.Ready(app)
                install(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withdraw()
                state = UpdateState.DownloadFailed(app)
                toast("下载失败", "重试", { act() }, false)
            }
        }
    }

    private suspend fun fetchApk(app: LatestApp): File {
        dir.deleteRecursively()
        dir.mkdirs()
        // Named per attempt: a cancelled download may still be winding down.
        val part = File(dir, "${System.nanoTime()}.part")
        val res = baseHttp.newCall(Request.Builder().url(app.url).build()).await()
        res.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val total = it.body.contentLength().takeIf { n -> n > 0 } ?: app.size
            part.outputStream().use { out ->
                val input = it.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var shown = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    val percent = percentOf(done, total)
                    if (percent != shown) {
                        shown = percent
                        withContext(Dispatchers.Main) {
                            state = UpdateState.Downloading(app, percent)
                            progress(percent)
                        }
                    }
                }
            }
        }
        val file = apk(app)
        if (!part.renameTo(file)) throw IOException("rename failed")
        return file
    }

    private fun progress(percent: Int) {
        toast("正在下载 $percent%", "取消", { act() }, true)
    }

    private fun cancel(app: LatestApp) {
        job?.cancel()
        withdraw()
        state = UpdateState.Available(app)
    }

    /** The system asks to allow installing from this app the first time. */
    private fun install(app: LatestApp) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk(app))
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: android.content.ActivityNotFoundException) {
            toast("打不开系统安装器", null, null, false)
        }
    }
}
