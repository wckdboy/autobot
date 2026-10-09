# ---------------------------------------------------------------------------
# Autobot release rules (R8 full mode is the AGP default; minify + shrinkResources on).
# ---------------------------------------------------------------------------

# Strip all android.util.Log calls (and their argument building) from release builds.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
    public static java.lang.String getStackTraceString(java.lang.Throwable);
}

# Reproducibility / privacy: keep no source file names, only line numbers for local retrace.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ---- kotlinx.serialization -------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    static ** INSTANCE;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.wckdboy.autobot.**$$serializer { *; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
# Navigation 3 keys are saved/restored by serializer lookup.
-keep @kotlinx.serialization.Serializable class dev.wckdboy.autobot.navigation.** { *; }

# ---- SQLCipher (JNI) --------------------------------------------------------
-keep class net.zetetic.database.** { *; }
-keep interface net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# ---- Room -------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---- Hilt / Dagger ----------------------------------------------------------
# Hilt ships consumer rules; these keep the generated entry points/components explicit.
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class **_HiltModules* { *; }
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }

# ---- OkHttp (ships its own rules; silence optional platform deps) ------------
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
