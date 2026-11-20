plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mivisor.play.torrent"
    compileSdk = 35
    // El mismo NDK que instala la CI para Outpace (no se descarga otro).
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Motor BitTorrent (libtorrent 2.0). Las tres piezas con el mismo número de versión.
    api(libs.libtorrent4j)
    implementation(libs.libtorrent4j.arm64)
    implementation(libs.libtorrent4j.arm)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
