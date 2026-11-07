package top.onlyquant.tingxie

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.onlyquant.tingxie.data.Api
import top.onlyquant.tingxie.data.DefaultServer
import top.onlyquant.tingxie.data.DeviceLogin
import top.onlyquant.tingxie.data.Library
import top.onlyquant.tingxie.data.LoginResult
import top.onlyquant.tingxie.data.Me
import top.onlyquant.tingxie.data.Prefs
import top.onlyquant.tingxie.data.Sends
import top.onlyquant.tingxie.data.Session
import top.onlyquant.tingxie.data.defaultTitle
import top.onlyquant.tingxie.data.exchangeCode
import top.onlyquant.tingxie.data.userMessage
import top.onlyquant.tingxie.ui.SnackMsg
import java.io.File

sealed interface Route {
    data object List : Route
    /** [hits] are search words to mark in the text; [fresh] is a document just created here. */
    data class Doc(val id: Long, val hits: kotlin.collections.List<String> = emptyList(), val fresh: Boolean = false) : Route
    data object Send : Route
    data object Profile : Route
    data object Devices : Route
}

/** The message above the sign-in button; errors are red. */
data class LoginMsg(val text: String, val error: Boolean = false)

class AppModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    /** False until the saved session has been read. */
    var ready by mutableStateOf(false)
        private set

    var server by mutableStateOf(DefaultServer)
        private set
    var loginBusy by mutableStateOf(false)
        private set
    var loginMsg by mutableStateOf<LoginMsg?>(null)
        private set
    private var awaitingBrowser = false

    var api by mutableStateOf<Api?>(null)
        private set
    lateinit var library: Library
        private set
    lateinit var sends: Sends
        private set
    var me by mutableStateOf<Me?>(null)
        private set
    var online by mutableStateOf(true)
        private set

    /** The screens under the current one; the last is shown. */
    val stack = mutableStateListOf<Route>()

    var snack by mutableStateOf<SnackMsg?>(null)

    val voice = top.onlyquant.tingxie.voice.Voice(app, viewModelScope) { toast(it) }

    val tidier = top.onlyquant.tingxie.data.Tidier(
        viewModelScope, { library }, { api },
        toast = { text, action, onAction ->
            toast(text, action, onAction)
            if (action != null) tidySnack = snack?.id
        },
        withdraw = { if (snack?.id == tidySnack) snack = null },
    )
    private var tidySnack: Long? = null

    val updater = top.onlyquant.tingxie.data.Updater(
        app, prefs, viewModelScope,
        // The profile page has a card that says the same.
        toast = { text, action, onAction, sticky -> if (stack.lastOrNull() != Route.Profile) snack = SnackMsg(text, action, onAction, sticky) },
        withdraw = { if (snack?.sticky == true) snack = null },
    )

    fun toast(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        snack = SnackMsg(text, action, onAction)
    }

    init {
        watchNetwork(app)
        viewModelScope.launch {
            server = prefs.server()
            prefs.session()?.let { begin(it) }
            ready = true
        }
    }

    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        online = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val now = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                viewModelScope.launch {
                    if (now && !online) refresh()
                    online = now
                }
            }

            override fun onLost(network: Network) {
                viewModelScope.launch { online = false }
            }
        })
    }

    // ---------- sign-in ----------

    fun setServerUrl(raw: String) {
        var url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        server = url
        viewModelScope.launch { prefs.setServer(url) }
    }

    fun startLogin(context: Context) {
        loginMsg = null
        viewModelScope.launch {
            try {
                DeviceLogin.start(context, prefs, server)
                awaitingBrowser = true
            } catch (e: Exception) {
                loginMsg = LoginMsg("打不开浏览器", true)
            }
        }
    }

    /** The browser came back to tingxie://auth/callback. */
    fun onCallback(uri: Uri) {
        awaitingBrowser = false
        viewModelScope.launch {
            when (val r = DeviceLogin.finish(prefs, uri)) {
                LoginResult.Cancelled -> loginMsg = LoginMsg("已取消登录")
                LoginResult.Invalid -> loginMsg = LoginMsg("登录失败，请重试", true)
                is LoginResult.Code -> {
                    loginBusy = true
                    try {
                        val signed = exchangeCode(r.server, r.code, r.verifier)
                        val s = Session(r.server, signed.token, signed.device.id)
                        prefs.signIn(s)
                        server = r.server
                        begin(s)
                    } catch (e: Exception) {
                        loginMsg = LoginMsg(if (e is top.onlyquant.tingxie.data.ApiError) e.userMessage() else "登录失败，请重试", true)
                    } finally {
                        loginBusy = false
                    }
                }
            }
        }
    }

    /**
     * Back in the app: re-read the list and the open document, which another
     * device may have changed meanwhile. Back from the browser without a
     * callback: the sign-in tab was closed.
     */
    fun onResume() {
        if (api != null) {
            if (online) updater.autoCheck()
            refresh()
            (stack.lastOrNull() as? Route.Doc)?.let { r ->
                viewModelScope.launch { library.reload(r.id) { voice.phase == top.onlyquant.tingxie.voice.Phase.Idle && tidier.busyWith == null } }
            }
        }
        if (!awaitingBrowser) return
        viewModelScope.launch {
            // A callback, if any, is delivered just before this.
            delay(300)
            if (awaitingBrowser && api == null && !loginBusy) {
                awaitingBrowser = false
                loginMsg = LoginMsg("已取消登录")
            }
        }
    }

    private var meJob: Job? = null

    private suspend fun begin(s: Session) {
        val a = Api(s) { viewModelScope.launch { revoked() } }
        val ctx = getApplication<Application>()
        library = Library(a, File(ctx.filesDir, "docs"), viewModelScope)
        library.onContent = tidier::onChange
        sends = Sends(a, File(ctx.filesDir, "sends.json"), prefs, viewModelScope)
        api = a
        loginMsg = null
        stack.clear()
        stack.add(Route.List)
        refresh()
        if (online) updater.autoCheck()
    }

    /** Re-reads what the server knows, quietly; offline keeps the cache. */
    fun refresh() {
        val a = api ?: return
        meJob?.cancel()
        meJob = viewModelScope.launch {
            runCatching { me = a.me() }
            runCatching { library.refreshList() }
        }
    }

    private suspend fun revoked() {
        if (api == null) return
        endSession()
        loginMsg = LoginMsg("登录已失效，请重新登录", true)
    }

    private suspend fun endSession() {
        tidier.cancel()
        library.clear()
        sends.clear()
        prefs.signOut()
        api = null
        me = null
        stack.clear()
    }

    /** Signs this device out; needs the server, as the token must be revoked. */
    fun signOut() {
        val a = api ?: return
        viewModelScope.launch {
            try {
                a.signOut()
            } catch (e: top.onlyquant.tingxie.data.ApiError) {
                if (e.status != 401 && e.status != 404) {
                    toast(e.userMessage())
                    return@launch
                }
            } catch (e: Exception) {
                toast("离线时无法退出登录")
                return@launch
            }
            endSession()
        }
    }

    /** Replaces the avatar; throws if the server says no. */
    suspend fun setAvatar(image: ByteArray, type: String) {
        val a = api ?: return
        val url = a.setAvatar(image, type)
        me = me?.copy(avatarUrl = url)
    }

    suspend fun removeAvatar() {
        val a = api ?: return
        a.deleteAvatar()
        me = me?.copy(avatarUrl = "")
    }

    fun avatarUrl(): String? = me?.avatarUrl?.ifEmpty { null }?.let { (api?.server ?: server) + it }

    // ---------- navigation ----------

    fun push(r: Route) {
        stack.add(r)
    }

    /** Back one screen; false when there is nowhere to go. */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun open(id: Long, hits: List<String> = emptyList(), fresh: Boolean = false) {
        stack.add(Route.Doc(id, hits, fresh))
    }

    fun toList() {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun newDoc() {
        viewModelScope.launch {
            try {
                val d = library.create(defaultTitle())
                open(d.id, fresh = true)
            } catch (e: Exception) {
                toast(e.userMessage())
            }
        }
    }

    fun deleteDoc(id: Long, title: String) {
        viewModelScope.launch {
            try {
                library.delete(id)
                toast("已删除「$title」", "撤销") {
                    viewModelScope.launch {
                        try {
                            library.restore(id)
                        } catch (e: Exception) {
                            toast(e.userMessage())
                        }
                    }
                }
            } catch (e: Exception) {
                toast(e.userMessage())
            }
        }
    }

    /** Leaving the app: push out unsaved edits. */
    fun onStop() {
        // Leaving the screen lets go of the talk button.
        voice.release()
        if (api != null) library.flushAll()
    }
}
