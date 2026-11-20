package com.mivisor.play.torrent

import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Mini servidor HTTP local (127.0.0.1) que sirve el vídeo mientras se descarga.
 * Si el reproductor pide un trozo que aún no está, espera a que llegue la pieza.
 * Soporta peticiones Range, así que se puede avanzar/retroceder en el vídeo.
 */
class StreamServer {

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = true

    val port: Int get() = server.localPort

    fun url(): String =
        "http://127.0.0.1:$port/" + URLEncoder.encode(TorrentEngine.fileName, "UTF-8").replace("+", "%20")

    fun start(): StreamServer {
        Thread({
            while (running) {
                val s = try { server.accept() } catch (e: IOException) { break }
                pool.execute { serve(s) }
            }
        }, "StreamServer").apply { isDaemon = true }.start()
        return this
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: IOException) {}
        pool.shutdownNow()
    }

    private fun serve(sock: Socket) {
        try {
            sock.use { handle(it) }
        } catch (e: IOException) {
            // El reproductor cerró la conexión (normal al hacer seek)
        } catch (e: Throwable) {
            TLog.w("Servidor", "error", e)
        }
    }

    private fun handle(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ').uppercase()

        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
        }

        val size = TorrentEngine.fileSize
        var start = 0L
        var end = size - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            if (a.isEmpty() && b.isNotEmpty()) {
                start = maxOf(0L, size - b.toLong())
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = min(size - 1, b.toLongOrNull() ?: end)
            }
        }

        val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
        if (start >= size) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
            return
        }

        val length = end - start + 1
        val partial = rangeHeader != null
        val headers = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: ${mimeFor(TorrentEngine.fileName)}\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $length\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$size\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        if (method == "HEAD") return

        var raf: RandomAccessFile? = null
        val buf = ByteArray(64 * 1024)
        var pos = start
        try {
            while (pos <= end && running && !sock.isClosed) {
                val piece = TorrentEngine.pieceAt(pos)
                TorrentEngine.focus(piece)
                // Espera hasta tener la pieza
                while (!TorrentEngine.haveData(piece)) {
                    if (!running || sock.isClosed || TorrentEngine.handle == null) return
                    Thread.sleep(60)
                }
                if (raf == null) {
                    val f = TorrentEngine.filePath() ?: return
                    var tries = 0
                    while (!f.exists() && tries++ < 100) Thread.sleep(50)
                    raf = RandomAccessFile(f, "r")
                }
                val pieceEnd = min(TorrentEngine.pieceEndInFile(piece), end + 1)
                while (pos < pieceEnd) {
                    raf.seek(pos)
                    val n = raf.read(buf, 0, min(buf.size.toLong(), pieceEnd - pos).toInt())
                    if (n <= 0) { Thread.sleep(50); continue }
                    out.write(buf, 0, n)
                    pos += n
                }
            }
            out.flush()
        } finally {
            try { raf?.close() } catch (_: IOException) {}
        }
    }

    companion object {
        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts" -> "video/mp2t"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }
}
