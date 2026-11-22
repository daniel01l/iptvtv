pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        // JitPack tylko dla ekstraktora YouTube (NewPipeExtractor) – pozostałe zależności wyłącznie z Google/Maven Central
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "IptvTv"
include(":app", ":admin", ":baselineprofile")
