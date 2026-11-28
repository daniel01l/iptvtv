# (7.28) R8 dla wydania: optymalizacja bibliotek (Compose, Media3, Coil, OkHttp) bez zmiany nazw.
-dontobfuscate
# brakujące klasy opcjonalnych zależności (np. java.beans w Rhino) – ostrzeżenie zamiast błędu
-ignorewarnings
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# kod aplikacji (aktywności, usługi, CastOptionsProvider z meta-data, JSON, Room, testy profilu bazowego)
-keep class pl.iptv.** { *; }

# NewPipeExtractor (STube) – refleksja i JavaScript (Rhino)
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.grack.nanojson.** { *; }

# FFmpeg (Jellyfin) – metody JNI
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
