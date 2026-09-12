# TDLib JNI bindings must not be stripped or renamed.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# Kotlin coroutines debug metadata is safe to keep for release diagnostics.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# Keep BuildConfig fields referenced reflectively nowhere, but keep names stable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
