package com.mivisor.play.torrent

/**
 * Libera en disco un trozo de un archivo sin cambiar su tamaño
 * (fallocate con FALLOC_FL_PUNCH_HOLE). Es lo que permite borrar
 * los fragmentos ya vistos de un vídeo de 7 GB sin tocar el resto.
 */
object HolePuncher {
    init { System.loadLibrary("holepunch") }

    /** Devuelve 0 si va bien, o -errno si falla. */
    @JvmStatic external fun punch(path: String, offset: Long, length: Long): Int
}
