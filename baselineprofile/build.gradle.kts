// (7.17) Generator profilu bazowego (Baseline Profile) aplikacji :app – scenariusz w BaselineProfileGenerator.
// Uruchomienie (emulator / urządzenie Android TV, API 33+ – bez roota):
//   ./gradlew :app:generateFullReleaseBaselineProfile -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
// Wynik: app/src/fullRelease/generated/baselineProfiles/{baseline-prof.txt, startup-prof.txt} (w repozytorium).
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "pl.iptv.tv.baselineprofile"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    // te same wersje (flavory) co w :app – profil generowany dla wersji pełnej
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("secure") { dimension = "edition" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// generowanie na podłączonym urządzeniu / uruchomionym emulatorze (bez urządzeń zarządzanych przez Gradle)
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
