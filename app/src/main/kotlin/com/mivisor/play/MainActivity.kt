package com.mivisor.play

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mivisor.play.data.AppLog
import com.mivisor.play.data.Kind
import com.mivisor.play.ui.Open
import com.mivisor.play.ui.Root
import com.mivisor.tv.catalog.datos.DirectStream
import com.mivisor.tv.outpace.EngineManager

class MainActivity : ComponentActivity() {
    private val graph: Graph get() = (application as PlayApp).graph

    /** Emisión que llega de fuera (enlace acestream:// o texto compartido) y aún no se ha abierto. */
    private val incoming = mutableStateOf<Open?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()
        if (savedInstanceState == null) incoming.value = openFrom(intent)
        setContent {
            Root(graph = graph, incoming = incoming.value, onIncomingHandled = { incoming.value = null }, onExit = { finish() })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)?.let { incoming.value = it }
    }

    override fun onStart() {
        super.onStart()
        EngineManager.cancelBackgroundStop()
        graph.externalPlayback = false
        graph.markForeground(true)
    }

    override fun onStop() {
        graph.markForeground(false)
        // Sin trabajo en segundo plano: se para la reproducción ya y el motor al cabo de un rato.
        graph.player.stop()
        // Si el vídeo se está viendo en otro reproductor, la descarga sigue (si no, se cortaría).
        if (!graph.externalPlayback) graph.torrents.stop()
        EngineManager.onBackground(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) {
            graph.player.release()
            graph.torrents.release()
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /**
     * Red de seguridad para el mando (como en MiVisor TV): si la búsqueda de foco de Compose falla, se ignora esa
     * pulsación en lugar de cerrar la app. Y una flecha mantenida no inunda la tele de movimientos de foco.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = try {
        if (throttleRepeat(event)) true else super.dispatchKeyEvent(event)
    } catch (e: IllegalStateException) {
        recoverKey(e)
    } catch (e: IllegalArgumentException) {
        recoverKey(e)
    } catch (e: IndexOutOfBoundsException) {
        recoverKey(e)
    }

    private var lastRepeatAt = 0L

    private fun throttleRepeat(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount == 0) return false
        if (event.keyCode !in DPAD) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRepeatAt < 85L) return true
        lastRepeatAt = now
        return false
    }

    private fun recoverKey(e: RuntimeException): Boolean {
        AppLog.add("Foco", "Tecla ignorada: ${e.javaClass.simpleName}")
        return true
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Lo que llega de otra app:
     *  - acestream://ID o un texto con el ID dentro → emisión de Ace Stream;
     *  - magnet:?… (enlace o texto compartido) → magnet;
     *  - un archivo .torrent (gestor de archivos, navegador, Telegram) → se guarda en la app y se abre.
     */
    private fun openFrom(intent: Intent?): Open? {
        intent ?: return null
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && (data.scheme == "content" || data.scheme == "file")) {
            val path = graph.importTorrent(data) ?: return null
            return Open(Kind.TORRENT, path)
        }
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return null
        Regex("""magnet:\?\S+""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            AppLog.add("Abrir", "Llega un magnet desde otra app")
            return Open(Kind.MAGNET, m.value, Graph.magnetName(m.value).orEmpty())
        }
        val id = DirectStream.parse(text) ?: return null
        AppLog.add("Abrir", "Llega ${DirectStream.short(id)} desde otra app")
        return Open(Kind.ACE, id)
    }

    private companion object {
        val DPAD = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
    }
}
