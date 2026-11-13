plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.iptv.tv"
    compileSdk = 34
    defaultConfig {
        applicationId = "pl.iptv.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 48
        versionName = "5.5"
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
    buildFeatures { buildConfig = true }
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
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("shots", System.getProperty("shots") ?: "0") } }
    sourceSets["main"].java.srcDir("../shared")
    // wersja full (release) zawsze jako D-iptv-Player.apk
    applicationVariants.all {
        if (flavorName == "full" && buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "D-iptv-Player.apk"
            }
        }
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
