package top.onlyquant.tingxie

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import top.onlyquant.tingxie.data.baseHttp
import top.onlyquant.tingxie.ui.DocScreen
import top.onlyquant.tingxie.ui.ListScreen
import top.onlyquant.tingxie.ui.LoginScreen
import top.onlyquant.tingxie.ui.SearchState
import top.onlyquant.tingxie.ui.theme.TingxieTheme
import top.onlyquant.tingxie.ui.theme.Tokens

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { App(model) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "tingxie" && uri.host == "auth") model.onCallback(uri)
    }

    override fun onResume() {
        super.onResume()
        model.onResume()
    }

    override fun onStop() {
        super.onStop()
        model.onStop()
    }
}

@Composable
fun App(m: AppModel) {
    // Images (/api/images, avatars) load with the device token.
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { m.api?.http ?: baseHttp })) }
            .build()
    }
    TingxieTheme {
        Box(Modifier.fillMaxSize().background(Tokens.palette.bg)) {
            if (!m.ready) return@Box
            if (m.api == null) {
                LoginScreen(m)
                return@Box
            }
            val search = remember(m.api) { SearchState() }
            val saved = rememberSaveableStateHolder()
            BackHandler(m.stack.size > 1) { m.back() }
            when (val top = m.stack.lastOrNull()) {
                Route.List, null -> saved.SaveableStateProvider("list") { ListScreen(m, search) }
                is Route.Doc -> saved.SaveableStateProvider("doc-${top.id}") { DocScreen(m, top) }
                else -> saved.SaveableStateProvider("list") { ListScreen(m, search) }
            }
        }
    }
}
