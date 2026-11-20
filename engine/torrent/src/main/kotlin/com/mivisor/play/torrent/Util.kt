package com.mivisor.play.torrent

import java.util.Locale

/** Utilidades de formato para el motor y la app. */
object Util {
    val VIDEO_EXT = setOf("mkv", "mp4", "avi", "m4v", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp")

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    fun bytes(b: Long): String {
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.getDefault(), if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
    }

    fun speed(bps: Int) = bytes(bps.toLong()) + "/s"

    fun time(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
