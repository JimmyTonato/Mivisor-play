package com.mivisor.play.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Qué es lo que se abre: una emisión de Ace Stream, un magnet o un archivo .torrent guardado en la app. */
enum class Kind(val id: String) { ACE("ace"), MAGNET("magnet"), TORRENT("torrent");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: ACE }
}

/**
 * Algo guardado: lo que se ha visto (recientes) o se ha marcado (favoritos).
 * [contentId] es la clave: el ID de 40 caracteres (Ace), el enlace magnet o la ruta del .torrent.
 */
data class Saved(
    val contentId: String,
    val name: String,
    val lastMs: Long,
    val favorite: Boolean,
    /** El nombre lo escribió el usuario (no se sobrescribe con el del descriptor). */
    val custom: Boolean,
    val kind: Kind = Kind.ACE
)

/**
 * Todo lo que guarda la app, en SharedPreferences (sin reflexión: sobrevive a R8 sin reglas).
 * Formato de cada línea: «clave \t nombre \t ms \t fav(0/1) \t custom(0/1) \t tipo».
 */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("mivisor_play", Context.MODE_PRIVATE)

    private val _saved = MutableStateFlow(readSaved())
    val saved: StateFlow<List<Saved>> = _saved.asStateFlow()

    // ------------------------------------------------------------ Emisiones

    private fun readSaved(): List<Saved> = sp.getString(KEY_SAVED, "").orEmpty()
        .split('\n').mapNotNull { line ->
            val p = line.split('\t')
            val id = p.getOrNull(0)?.trim().orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            Saved(
                contentId = id,
                name = p.getOrNull(1).orEmpty(),
                lastMs = p.getOrNull(2)?.toLongOrNull() ?: 0L,
                favorite = p.getOrNull(3) == "1",
                custom = p.getOrNull(4) == "1",
                kind = Kind.of(p.getOrNull(5))
            )
        }

    private fun write(list: List<Saved>) {
        // Los favoritos no caducan; de los demás se guardan los 40 últimos.
        val keep = list.sortedByDescending { it.lastMs }.let { all ->
            val favs = all.filter { it.favorite }
            val rest = all.filterNot { it.favorite }.take(MAX_RECENT)
            all.filter { it in favs || it in rest }
        }
        sp.edit().putString(KEY_SAVED, keep.joinToString("\n") { s ->
            listOf(s.contentId, clean(s.name), s.lastMs.toString(), if (s.favorite) "1" else "0", if (s.custom) "1" else "0", s.kind.id).joinToString("\t")
        }).apply()
        _saved.value = keep
    }

    fun find(contentId: String): Saved? = _saved.value.firstOrNull { it.contentId == contentId }

    /** Se empieza a ver: sube a la primera posición de recientes. */
    fun touch(contentId: String, kind: Kind = Kind.ACE, name: String = "") {
        val old = find(contentId)
        val now = System.currentTimeMillis()
        val item = old?.copy(lastMs = now) ?: Saved(contentId, clean(name), now, favorite = false, custom = false, kind = kind)
        write(listOf(item) + _saved.value.filter { it.contentId != contentId })
    }

    /** Nombre que trae el descriptor de la emisión: solo si el usuario no puso uno. */
    fun autoName(contentId: String, name: String) {
        val old = find(contentId) ?: return
        val n = clean(name)
        if (old.custom || n.isEmpty() || old.name == n) return
        write(_saved.value.map { if (it.contentId == contentId) it.copy(name = n) else it })
    }

    fun rename(contentId: String, name: String) {
        val n = clean(name)
        val old = find(contentId) ?: Saved(contentId, "", System.currentTimeMillis(), false, false)
        val item = old.copy(name = n, custom = n.isNotEmpty())
        write(listOf(item) + _saved.value.filter { it.contentId != contentId })
    }

    fun setFavorite(contentId: String, favorite: Boolean) {
        val old = find(contentId) ?: Saved(contentId, "", System.currentTimeMillis(), false, false)
        write(listOf(old.copy(favorite = favorite)) + _saved.value.filter { it.contentId != contentId })
    }

    fun remove(contentId: String) {
        find(contentId)?.takeIf { it.kind == Kind.TORRENT }?.let { java.io.File(it.contentId).delete() }
        write(_saved.value.filter { it.contentId != contentId })
    }

    fun clearRecents() {
        _saved.value.filter { !it.favorite && it.kind == Kind.TORRENT }.forEach { java.io.File(it.contentId).delete() }
        write(_saved.value.filter { it.favorite })
    }

    // ------------------------------------------------------------ Torrents

    var spaceSaver: Boolean
        get() = sp.getBoolean("space_saver", true)
        set(v) { sp.edit().putBoolean("space_saver", v).apply() }

    var cacheMb: Int
        get() = sp.getInt("cache_mb", 500)
        set(v) { sp.edit().putInt("cache_mb", v).apply() }

    var bufferMb: Int
        get() = sp.getInt("buffer_mb", 10)
        set(v) { sp.edit().putInt("buffer_mb", v).apply() }

    var uploadKbps: Int
        get() = sp.getInt("upload_kbps", 0)
        set(v) { sp.edit().putInt("upload_kbps", v).apply() }

    // ------------------------------------------------------------ Ajustes

    var fullP2p: Boolean
        get() = sp.getBoolean("full_p2p", false)
        set(v) { sp.edit().putBoolean("full_p2p", v).apply() }

    var subtitlesByDefault: Boolean
        get() = sp.getBoolean("subs_default", false)
        set(v) { sp.edit().putBoolean("subs_default", v).apply() }

    /** "normal", "grande", "muy-grande". */
    var subtitleSize: String
        get() = sp.getString("subs_size", "normal") ?: "normal"
        set(v) { sp.edit().putString("subs_size", v).apply() }

    var autoLive: Boolean
        get() = sp.getBoolean("auto_live", true)
        set(v) { sp.edit().putBoolean("auto_live", v).apply() }

    /** Registro detallado del motor (más líneas en «Registro»). */
    var verboseLog: Boolean
        get() = sp.getBoolean("verbose_log", true)
        set(v) { sp.edit().putBoolean("verbose_log", v).apply() }

    /** "auto", "tv", "tablet", "phone". */
    var deviceOverride: String
        get() = sp.getString("device", "auto") ?: "auto"
        set(v) { sp.edit().putString("device", v).apply() }

    fun audioFor(channelId: String): String? = sp.getString("audio_$channelId", null)
    fun setAudio(channelId: String, language: String?) {
        sp.edit().apply { if (language == null) remove("audio_$channelId") else putString("audio_$channelId", language) }.apply()
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim().take(60)

    private companion object {
        const val KEY_SAVED = "saved"
        const val MAX_RECENT = 40
    }
}
