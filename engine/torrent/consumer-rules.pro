# libtorrent4j: la capa SWIG se llama desde C++ por nombre (JNI) y las alertas se crean por tipo.
-keep class org.libtorrent4j.** { *; }
-keep class com.mivisor.play.torrent.HolePuncher { *; }
-dontwarn org.libtorrent4j.**
