package com.mivisor.play.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mivisor.tv.playback.PlaySource
import com.mivisor.tv.playback.SourceBook
import com.mivisor.tv.reliability.SourceState

/**
 * La reproducción de MiVisor TV pide un «libro de fuentes». Aquí cada emisión es una sola fuente (el ID que abre el
 * usuario), así que no hay orden ni fiabilidad que aprender: se prueba esa y se reintenta la misma.
 */
class SimpleSourceBook(context: Context, private val store: Store) : SourceBook {
    private val app = context.applicationContext

    override fun order(channelId: String, sources: List<PlaySource>, chosen: String?): List<String> =
        (listOfNotNull(chosen) + sources.map { it.contentId }).distinct()

    override fun state(contentId: String): SourceState = SourceState.NORMAL

    override fun onSuccess(channelId: String, source: PlaySource, startMs: Long) {
        AppLog.add("Reproducción", "Imagen en ${startMs / 1000.0} s")
    }

    override fun onFailure(channelId: String, source: PlaySource, kind: String): String {
        val cause = if (online() == false) "offline" else "source"
        AppLog.add("Reproducción", "Fallo «$kind» (${if (cause == "offline") "sin Internet" else "de la emisión"})")
        return cause
    }

    override fun onFreeze(source: PlaySource, step: Int) {
        AppLog.add("Reproducción", "Imagen recuperada tras un corte (paso $step)")
    }

    override fun onSessionEnd(channelId: String, channelName: String, source: PlaySource?, playedMs: Long, stalls: Int) {
        AppLog.add("Reproducción", "Fin: ${playedMs / 1000} s vistos, $stalls cortes")
    }

    override fun log(category: String, text: String) = AppLog.add(category, text)

    override fun generalCause(source: PlaySource): String? = if (online() == false) "offline" else null

    override fun preferredAudio(channelId: String): String? = store.audioFor(channelId)
    override fun rememberAudio(channelId: String, language: String?) = store.setAudio(channelId, language)
    override fun subtitlesByDefault(): Boolean = store.subtitlesByDefault

    /** Una sola fuente: nunca se «cambia» sola; se sigue recargando la misma. */
    override fun autoSwitchAfterMs(): Long = 0L
    override fun autoLiveEdge(): Boolean = store.autoLive
    override fun prewarmNext(): Boolean = false

    /** ¿Hay Internet? null = no se sabe (entonces no se culpa a la red). */
    private fun online(): Boolean? = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrNull()
}
