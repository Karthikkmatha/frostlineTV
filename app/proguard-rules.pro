# FrostlineTV release rules. The shipped release build is not minified (isMinifyEnabled = false); these rules are kept
# current and exercised by the `releaseMinified` build type (assembleReleaseMinified) so a future switch is safe.

-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault, Exceptions

# ---- kotlinx.serialization: generated serializers and companions of every @Serializable class ----
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class tv.frostline.** { kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers @kotlinx.serialization.Serializable class tv.frostline.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ---- Retrofit: the service interface and its annotations are read reflectively ----
-keep,allowobfuscation,allowshrinking interface tv.frostline.core.twitch.HelixApi
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-dontwarn javax.annotation.**

# ---- OkHttp / Okio platform probes ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Media3: reflective renderer / extractor / HLS factories ----
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory { *; }
-keep class androidx.media3.exoplayer.** implements androidx.media3.exoplayer.Renderer { <init>(...); }
-dontwarn androidx.media3.**

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- Coil ----
-dontwarn coil3.**
