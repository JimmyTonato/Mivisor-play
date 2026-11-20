plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// GitHub Actions: cada ejecución genera un versionCode mayor que el anterior.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()

// Firma igual que MiVisor TV: con los secretos de GitHub si están; si no, con app/debug.keystore.
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

composeCompiler {
    stabilityConfigurationFile.set(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

android {
    namespace = "com.mivisor.play"
    compileSdk = 35

    defaultConfig {
        // Paquete propio: se instala junto a MiVisor TV sin reemplazarla.
        applicationId = "com.mivisor.play"
        // Android 6.0: el mínimo de Outpace (binario compilado contra la API 23).
        minSdk = 23
        targetSdk = 35
        versionCode = maxOf(1, runNumber)
        versionName = "1.0.$runNumber"

        resourceConfigurations += listOf("es", "en")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

        buildConfigField("String", "OUTPACE_COMMIT", "\"${System.getenv("OUTPACE_COMMIT") ?: "desconocido"}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("dev") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Outpace (liboutpace.so) es un EJECUTABLE que se lanza desde nativeLibraryDir: debe extraerse al instalar.
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libc++_shared.so")
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin", "kotlin-tooling-metadata.json"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:catalog"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:reliability"))
    implementation(project(":core:engine"))
    implementation(project(":engine:outpace"))
    implementation(project(":engine:torrent"))
    implementation(project(":feature:playback"))
    implementation(project(":ui:design"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.profileinstaller)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
}
