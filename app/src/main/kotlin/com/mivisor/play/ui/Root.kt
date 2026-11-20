package com.mivisor.play.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.mivisor.play.Graph
import com.mivisor.play.data.Kind
import com.mivisor.tv.model.DeviceClass
import com.mivisor.tv.ui.design.LocalUiForm
import com.mivisor.tv.ui.design.MvTheme
import com.mivisor.tv.ui.design.UiForm

/** Lo que se puede abrir. [key]: ID de Ace Stream, enlace magnet o ruta del .torrent guardado en la app. */
data class Open(val kind: Kind, val key: String, val name: String = "")

sealed interface Route {
    data object Home : Route
    data class Player(val open: Open) : Route
    data object Settings : Route
    data object Log : Route
    data object Licenses : Route
}

class Nav(start: Route) {
    val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()
    fun go(r: Route) {
        // Un reproductor nuevo sustituye al que hubiera (nunca dos a la vez).
        if (r is Route.Player && current is Route.Player) stack[stack.lastIndex] = r else stack.add(r)
    }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

@Composable
fun Root(graph: Graph, incoming: Open?, onIncomingHandled: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val device = remember(config.smallestScreenWidthDp) { detectDevice(context, graph.store.deviceOverride) }
    // Móvil y tablet en horizontal (como se ve un vídeo); la tele, como venga.
    LaunchedEffect(device) {
        (context as? Activity)?.let { a ->
            val want = if (device == DeviceClass.TV) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            if (a.requestedOrientation != want) a.requestedOrientation = want
        }
    }
    val density = LocalDensity.current
    val metrics = context.resources.displayMetrics
    val drawDensity = remember(device, config.screenWidthDp, config.screenHeightDp, density.density) {
        DeviceClass.density(device, metrics.widthPixels, metrics.heightPixels, density.density)
    }
    val form = when (device) {
        DeviceClass.TV -> UiForm.TV
        DeviceClass.TABLET -> UiForm.TABLET
        DeviceClass.PHONE -> UiForm.PHONE
    }
    val nav = remember { Nav(Route.Home) }

    fun open(o: Open) {
        graph.store.touch(o.key, o.kind, o.name)
        nav.go(Route.Player(o))
    }

    LaunchedEffect(incoming) {
        if (incoming != null) {
            open(incoming)
            onIncomingHandled()
        }
    }

    CompositionLocalProvider(
        LocalDensity provides Density(drawDensity, density.fontScale),
        LocalUiForm provides form
    ) {
        MvTheme(lite = false) {
            Box(Modifier.fillMaxSize().background(Pc.Bg)) {
                BackHandler(enabled = true) { if (!nav.back()) onExit() }
                when (val r = nav.current) {
                    Route.Home -> HomeScreen(
                        graph = graph,
                        onOpen = ::open,
                        onSettings = { nav.go(Route.Settings) }
                    )
                    is Route.Player -> when (r.open.kind) {
                        Kind.ACE -> AcePlayerScreen(graph, r.open.key, onClose = { nav.back() }, onLog = { nav.go(Route.Log) })
                        Kind.MAGNET, Kind.TORRENT -> TorrentPlayerScreen(graph, r.open, onClose = { nav.back() }, onLog = { nav.go(Route.Log) })
                    }
                    Route.Settings -> SettingsScreen(graph, onBack = { nav.back() }, onLog = { nav.go(Route.Log) }, onLicenses = { nav.go(Route.Licenses) })
                    Route.Log -> LogScreen(graph, onBack = { nav.back() })
                    Route.Licenses -> LicensesScreen(onBack = { nav.back() })
                }
            }
        }
    }
}

private fun detectDevice(context: Context, override: String): DeviceClass {
    val pm = context.packageManager
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val tvMode = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val leanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("android.software.leanback_only")
    val touch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    return DeviceClass.detect(tvMode, leanback, touch, context.resources.configuration.smallestScreenWidthDp, override)
}
