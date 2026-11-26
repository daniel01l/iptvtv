import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    // (7.17) profil bazowy z modułu :baselineprofile (zimny start, Start, Kanały/EPG, pasek nawigacji, STube, Stremio, Ustawienia)
    id("androidx.baselineprofile")
}

android {
    namespace = "pl.iptv.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.iptv.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 84
        versionName = "7.24"
        // konto YouTube (OAuth 2.0 Device Flow): klient typu „TV i urządzenia z ograniczonym wejściem” z Google Cloud Console.
        // Plik keystore/youtube_oauth.properties (poza repozytorium): client_id=…, client_secret=…, api_key=… (klucz – trendy bez logowania).
        val yt = Properties().apply { rootProject.file("keystore/youtube_oauth.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }
        buildConfigField("String", "YT_CLIENT_ID", "\"${yt.getProperty("client_id", "").trim()}\"")
        buildConfigField("String", "YT_CLIENT_SECRET", "\"${yt.getProperty("client_secret", "").trim()}\"")
        buildConfigField("String", "YT_API_KEY", "\"${yt.getProperty("api_key", "").trim()}\"")
        // (7.21) klucz TMDB nie jest wbudowany w APK – użytkownik wpisuje własny w Stremio („Klucz TMDB”)
        buildConfigField("String", "TMDB_API_KEY", "\"\"")
    }
    signingConfigs {
        create("iptvtv") {
            // stały klucz – każda nowa wersja musi być nim podpisana, inaczej aktualizacja zostanie odrzucona
            storeFile = rootProject.file("keystore/iptvtv.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // podpis kluczem debug, aby APK dało się od razu zainstalować
            signingConfig = signingConfigs.getByName("iptvtv")
        }
    }
    buildFeatures { buildConfig = true; compose = true }
    // full – Twoja wersja (bez zmian); secure – wersja dla testerów: aktywacja kodem urządzenia, blokada, ograniczona prywatność
    flavorDimensions += "edition"
    // klucz publiczny z aplikacji D-iptv Admin (plik poza repozytorium); lista aktywacji w publicznym repozytorium dplayer
    val actPub = rootProject.file("keystore/activation_pub.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
    val actUrl = "\"https://raw.githubusercontent.com/daniel01l/dplayer/main/activation.json\""
    productFlavors {
        // pełna (rozwijana) – wszystkie funkcje, działa po aktywacji ⭐ w Adminie
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "SECURE", "false")
            buildConfigField("boolean", "ACTIVATION", "true")
            buildConfigField("boolean", "FULL_EDITION", "true")
            buildConfigField("int", "GRACE_DAYS", "30")
            buildConfigField("String", "ACT_PUBKEY", "\"$actPub\"")
            buildConfigField("String", "ACT_URL", actUrl)
            buildConfigField("String", "CONTACT", "\"@Lokilo1\"")
            buildConfigField("String", "UPDATE_URL", "\"https://github.com/daniel01l/iptvtv/releases/latest/download/iptvtv.json\"")
        }
        // testowa – ograniczona prywatność, aktywacja 🧪
        create("secure") {
            dimension = "edition"
            applicationId = "pl.iptv.dplayer"
            versionCode = 3
            versionName = "1.2"
            buildConfigField("boolean", "SECURE", "true")
            buildConfigField("boolean", "ACTIVATION", "true")
            buildConfigField("boolean", "FULL_EDITION", "false")
            buildConfigField("int", "GRACE_DAYS", "3")
            buildConfigField("String", "ACT_PUBKEY", "\"$actPub\"")
            buildConfigField("String", "ACT_URL", actUrl)
            buildConfigField("String", "CONTACT", "\"@Lokilo1\"")
            buildConfigField("String", "UPDATE_URL", "\"https://github.com/daniel01l/dplayer/releases/latest/download/dplayer.json\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // ekstraktor YouTube używa java.time / java.nio z nowszych Androidów – desugaring dla minSdk 21
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("shots", System.getProperty("shots") ?: "0")
            // (7.22) pełny zestaw (~550 testów Robolectric, wiele odtwarzaczy) – domyślne 512 MB procesu testów nie wystarczało
            it.maxHeapSize = "2g"
        } }
    sourceSets["main"].java.srcDir("../shared")    // wersja full (release) zawsze jako D-iptv-Player.apk
    applicationVariants.all {
        if (flavorName == "full" && buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "D-iptv-Player.apk"
            }
        }
    }
}

// schemat bazy STube w repozytorium (podstawa przyszłych migracji)
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// (7.17) Profil bazowy: generowany ręcznie (`./gradlew :app:generateFullReleaseBaselineProfile` z emulatorem / urządzeniem
// Android TV, API 33+), zapisywany w repozytorium (src/fullRelease/generated/baselineProfiles) i dołączany do każdego
// wydania obok ręcznego src/main/baseline-prof.txt. Zwykłe budowanie nie uruchamia generowania (bez urządzenia).
baselineProfile {
    saveInSrc = true
    automaticGenerationDuringBuild = false
    // profil startowy (startup-prof.txt) – kolejność klas w DEX pod zimny start
    dexLayoutOptimization = true
}

// (7.17) Raport stabilności klas Compose (./gradlew -PcomposeReports :app:compileFullReleaseKotlin → build/compose_compiler).
// Kotlin 2.0.21: tryb Strong Skipping włączony domyślnie (niestabilne parametry porównywane przez tożsamość, lambdy zapamiętywane).
composeCompiler {
    if (project.hasProperty("composeReports")) {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

dependencies {
    // Media3 1.5.0 – wersja zgodna z prebudowanym dekoderem FFmpeg (Jellyfin publikuje go tylko dla wybranych wersji Media3)
    val media3 = "1.5.0"
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // STube: odtwarzanie w tle (MediaSessionService, powiadomienie multimediów)
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    // Stremio: programowe dekodery dźwięku FFmpeg (AC3, E-AC3, DTS, DTS-HD, TrueHD…) jako zapas, gdy TV nie ma dekodera / passthrough.
    // Prebudowany moduł Jellyfin (biblioteki natywne FFmpeg, licencja GPLv3 wg POM) – wersja musi odpowiadać Media3.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")
    // kod QR logowania STube (generowany lokalnie, Apache 2.0)
    implementation("com.google.zxing:core:3.5.3")
    // pulpit X25: miniatury i logo w Compose (ten sam ImageLoader co widoki – IptvApp)
    implementation("io.coil-kt:coil-compose:2.7.0")
    // tokeny konta YouTube szyfrowane kluczem z Android Keystore (1.1.0-alpha06: minSdk 21)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // STube: wbudowany odtwarzacz – adresy strumieni YouTube bez reklam (licencja GPLv3)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    // STube: lokalna baza (historia wyszukiwania, postęp oglądania) – Room 2.6.1 (minSdk 21)
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    // motyw X25: pulpit w Jetpack Compose (Compose 1.7 – minSdk 21); pozostałe ekrany na widokach z motywem z Ui
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // (7.17) instalacja profilu bazowego przy pierwszym uruchomieniu APK spoza Sklepu Play (wydania z GitHuba)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    baselineProfile(project(":baselineprofile"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
