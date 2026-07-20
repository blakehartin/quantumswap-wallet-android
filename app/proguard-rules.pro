# Keep source-file + line numbers for crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotations (needed for Gson @SerializedName, retrofit-like codegen, etc.)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# -----------------------------------------------------------
# WebView JavaScript bridge
# -----------------------------------------------------------
# Keep all @JavascriptInterface methods so the bridge.html page can call them
# by their original names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.quantumswap.app.bridge.** { *; }

# -----------------------------------------------------------
# Gson model classes (reflection-based (de)serialization)
# -----------------------------------------------------------
# Keep Gson types and TypeToken signatures.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# Preserve all model classes used with Gson (serialized/deserialized via reflection).
-keep class com.quantumswap.app.model.** { *; }
-keepclassmembers class com.quantumswap.app.model.** { <fields>; <init>(...); }

-keep class com.quantumswap.app.api.read.model.** { *; }
-keepclassmembers class com.quantumswap.app.api.read.model.** { <fields>; <init>(...); }

-keep class com.quantumswap.app.entity.** { *; }
-keepclassmembers class com.quantumswap.app.entity.** { <fields>; <init>(...); }

# Fields annotated with @SerializedName must survive obfuscation.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep enums used by Gson.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -----------------------------------------------------------
# OkHttp / Okio / Retrofit-style API client reflection
# -----------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Swagger/OpenAPI generated client uses reflection for auth/JSON adapters.
-keep class com.quantumswap.app.api.read.** { *; }
-keep class io.swagger.annotations.** { *; }
-dontwarn io.swagger.**

# gson-fire uses reflection on dates/deprecated fields.
-keep class io.gsonfire.** { *; }
-dontwarn io.gsonfire.**

# -----------------------------------------------------------
# Timber
# -----------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-keep class timber.log.Timber { *; }

# -----------------------------------------------------------
# AndroidX / Material / ViewBinding
# -----------------------------------------------------------
-dontwarn androidx.**
-keep class androidx.webkit.** { *; }
-keep class androidx.camera.** { *; }

# ViewBinding: generated binding classes.
-keep class com.quantumswap.app.databinding.** { *; }

# -----------------------------------------------------------
# Reflection-loaded Application class
# -----------------------------------------------------------
-keep public class com.quantumswap.app.App

# -----------------------------------------------------------
# BackupAgent (declared in manifest via android:backupAgent).
# Manifest-declared components must survive R8.
# -----------------------------------------------------------
-keep public class com.quantumswap.app.backup.WalletBackupAgent
-keepclassmembers class com.quantumswap.app.backup.WalletBackupAgent { <init>(...); }

# -----------------------------------------------------------
# Parcelables + Serializables (Android standard keep rules).
# -----------------------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# -----------------------------------------------------------
# Dead-code / Gradle lint quiet down.
# -----------------------------------------------------------
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.annotation.processing.**
