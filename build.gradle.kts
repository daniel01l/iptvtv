plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // pulpit X25 (Jetpack Compose) – kompilator Compose zgodny z Kotlinem 2.0.21
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Room (STube: historia wyszukiwania, postęp filmów) – generowanie kodu DAO
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    // (7.17) profile bazowe: moduł testowy :baselineprofile generuje profil zimnego startu i głównych ekranów (AOT)
    id("com.android.test") version "8.7.3" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
}
