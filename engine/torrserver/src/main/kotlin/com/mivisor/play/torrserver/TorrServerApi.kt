package com.mivisor.play.torrserver

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Un archivo dentro del torrent ([id] es el «index» que pide /stream). */
data class TsFile(val id: Int, val path: String, val length: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/** Estado de un torrent según TorrServer (campos de su JSON; los que falten quedan a 0). */
data class TsStatus(
    val hash: String,
    val name: String,
    val title: String,
    val stat: Int,
    val statText: String,
    val downBps: Long,
    val activePeers: Int,
    val totalPeers: Int,
    val seeders: Int,
    val preloadedBytes: Long,
    val preloadSize: Long,
    val torrentSize: Long,
    val files: List<TsFile>
) {
    companion object {
        fun parse(o: JSONObject): TsStatus {
            val files = mutableListOf<TsFile>()
            o.optJSONArray("file_stats")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    files += TsFile(f.optInt("id", i + 1), f.optString("path"), f.optLong("length"))
                }
            }
            return TsStatus(
                hash = o.optString("hash"),
                name = o.optString("name"),
                title = o.optString("title"),
                stat = o.optInt("stat", -1),
                statText = o.optString("stat_string"),
                downBps = o.optDouble("download_speed", 0.0).toLong(),
                activePeers = o.optInt("active_peers"),
                totalPeers = o.optInt("total_peers"),
                seeders = o.optInt("connected_seeders"),
                preloadedBytes = o.optLong("preloaded_bytes"),
                preloadSize = o.optLong("preload_size"),
                torrentSize = o.optLong("torrent_size"),
                files = files
            )
        }
    }
}

/** Cliente de la API HTTP de TorrServer (solo 127.0.0.1). Todas las llamadas bloquean: fuera del hilo principal. */
object TorrServerApi {

    /** Versión del motor, o null si no contesta. */
    fun echo(base: String, timeoutMs: Int = 1_500): String? = runCatching {
        val c = TorrServerEngine.open("$base/echo", "GET", timeoutMs)
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText().trim() } else null
        } finally { c.disconnect() }
    }.getOrNull()

    /** Añade un magnet (o un hash, o un enlace http a un .torrent). */
    fun add(base: String, link: String, title: String): TsStatus =
        TsStatus.parse(postJson("$base/torrents", JSONObject().put("action", "add").put("link", link).put("title", title).put("save_to_db", false)))

    /** Sube un archivo .torrent del aparato. */
    fun upload(base: String, file: File, title: String): TsStatus {
        val boundary = "----MiVisorPlay" + System.currentTimeMillis()
        val c = TorrServerEngine.open("$base/torrent/upload", "POST", 30_000)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                fun text(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
                }
                text("title", title)
                text("save", "false")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name.replace("\"", "")}\"\r\nContent-Type: application/x-bittorrent\r\n\r\n".toByteArray(Charsets.UTF_8))
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val body = readBody(c)
            return TsStatus.parse(firstObject(body) ?: throw IllegalStateException("El motor no aceptó el .torrent (${c.responseCode}): ${body.take(200)}"))
        } finally { c.disconnect() }
    }

    fun get(base: String, hash: String): TsStatus? = runCatching {
        TsStatus.parse(postJson("$base/torrents", JSONObject().put("action", "get").put("hash", hash), 4_000))
    }.getOrNull()

    /** Suelta el torrent (deja de descargarlo y libera su caché). */
    fun drop(base: String, hash: String) {
        runCatching { postJson("$base/torrents", JSONObject().put("action", "drop").put("hash", hash), 3_000) }
        runCatching { postJson("$base/torrents", JSONObject().put("action", "rem").put("hash", hash), 3_000) }
    }

    /** URL de reproducción de un archivo (el nombre al final ayuda al reproductor a saber el formato). */
    fun streamUrl(base: String, hash: String, file: TsFile): String =
        "$base/stream/" + enc(file.name) + "?link=$hash&index=${file.id}&play"

    /** Ajustes de caché y red: se leen los actuales y se cambian solo los nuestros. */
    fun applySettings(base: String, cfg: TsConfig, diskCachePath: String) {
        val sets = runCatching { postJson("$base/settings", JSONObject().put("action", "get"), 4_000) }.getOrDefault(JSONObject())
        sets.put("CacheSize", cfg.cacheMb.toLong() * 1024 * 1024)
        sets.put("PreloadCache", cfg.preloadPercent.coerceIn(0, 100))
        sets.put("ReaderReadAHead", 95)
        sets.put("UseDisk", cfg.useDisk)
        sets.put("TorrentsSavePath", if (cfg.useDisk) diskCachePath else "")
        sets.put("RemoveCacheOnDrop", true)
        sets.put("ConnectionsLimit", cfg.connectionsLimit)
        sets.put("UploadRateLimit", cfg.uploadKbps)
        postJson("$base/settings", JSONObject().put("action", "set").put("sets", sets), 6_000)
        TsLog.add("Ajustes: caché ${cfg.cacheMb} MB en ${if (cfg.useDisk) "disco" else "memoria"}, precarga ${cfg.preloadPercent} %, ${cfg.connectionsLimit} conexiones")
    }

    // ------------------------------------------------------------------------------------------ HTTP

    private fun postJson(url: String, body: JSONObject, timeoutMs: Int = 20_000): JSONObject {
        val c = TorrServerEngine.open(url, "POST", timeoutMs)
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val text = readBody(c)
            if (c.responseCode !in 200..299) throw IllegalStateException("El motor respondió ${c.responseCode}: ${text.take(200)}")
            return firstObject(text) ?: JSONObject()
        } finally { c.disconnect() }
    }

    private fun readBody(c: java.net.HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun firstObject(text: String): JSONObject? {
        val t = text.trim()
        return when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t).optJSONObject(0)
            else -> null
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
