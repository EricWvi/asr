package top.onlyquant.tingxie.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.onlyquant.tingxie.Route
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/** A screen at its place in the stack; the depth keeps two pages of the same route apart. */
private data class Page(val depth: Int, val route: Route)

private val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val MILLIS = 300

/**
 * Shows the top of [stack]. A new screen slides in from the right over the
 * one below; going back slides the top screen out to the right. The screen
 * underneath stays still. The sliding screen has the display's rounded
 * corners. A back swipe drags the top screen to the right under the finger,
 * from either edge (predictive back, Android 14+); letting go far enough
 * finishes the slide, otherwise it springs back. Back handlers inside a screen (leaving edit
 * mode, the avatar crop) come first, as before.
 */
@Composable
fun Pages(stack: List<Route>, onBack: () -> Unit, content: @Composable (Route) -> Unit) {
    val top = Page(stack.lastIndex.coerceAtLeast(0), stack.lastOrNull() ?: Route.List)
    // The still screen, and the one sliding over it (in or out).
    var base by remember { mutableStateOf(top) }
    var moving by remember { mutableStateOf<Page?>(null) }
    // The top that the motion below has caught up with.
    var settled by remember { mutableStateOf(top) }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val corner = screenCorner()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()

        LaunchedEffect(top) {
            val from = settled
            if (from == top) return@LaunchedEffect
            settled = top
            when {
                top.depth > from.depth -> {
                    base = from
                    moving = top
                    offset.snapTo(width)
                    offset.animateTo(0f, tween(MILLIS, easing = Ease))
                }
                top.depth < from.depth -> {
                    // After a back swipe the screen is already part way out.
                    if (moving != from) offset.snapTo(0f)
                    moving = from
                    base = top
                    offset.animateTo(width, tween(MILLIS, easing = Ease))
                }
            }
            base = top
            moving = null
            offset.snapTo(0f)
        }

        PredictiveBackHandler(enabled = stack.size > 1) { progress ->
            val under = Page(stack.lastIndex - 1, stack[stack.lastIndex - 1])
            val leaving = top
            if (moving == null) {
                base = under
                moving = leaving
            }
            try {
                // Under the finger: how far it has moved in from either edge.
                var start = Float.NaN
                progress.collect {
                    if (start.isNaN()) start = it.touchX
                    offset.snapTo(abs(it.touchX - start).coerceAtMost(width))
                }
                onBack()
            } catch (e: CancellationException) {
                scope.launch {
                    offset.animateTo(0f, tween(MILLIS, easing = Ease))
                    if (settled == leaving) {
                        base = leaving
                        moving = null
                    }
                }
                throw e
            }
        }

        for (page in listOfNotNull(base, moving)) {
            key(page) {
                val slide = if (page == moving) {
                    Modifier.graphicsLayer {
                        translationX = offset.value
                        shape = RoundedCornerShape(corner)
                        clip = true
                        shadowElevation = 16.dp.toPx()
                    }
                } else Modifier
                Box(Modifier.fillMaxSize().then(slide)) { content(page.route) }
            }
        }
    }
}

/** The display's corner radius; 32dp where Android does not tell (before 12). */
@Composable
private fun screenCorner(): androidx.compose.ui.unit.Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        val px = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius
        } else null
        if (px != null && px > 0) with(density) { px.toDp() } else 32.dp
    }
}
