pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "MiVisor-Play"

// Núcleo sin Android (se prueba con JUnit en la JVM). Son los módulos de MiVisor TV, sin cambios.
include(":core:model", ":core:net", ":core:catalog", ":core:diagnostics", ":core:reliability", ":core:engine")
// Android: motor Outpace (Ace Stream), motor de torrents, reproducción Media3, base de foco/tipografía y la app nueva.
include(":engine:outpace", ":engine:torrent", ":feature:playback", ":ui:design", ":app")
