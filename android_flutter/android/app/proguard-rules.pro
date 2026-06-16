# Proguard rules for smart_tg_downloader

# Flutter engine keep rules
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.plugins.** { *; }

# Keep our MainActivity classes
-keep class com.example.smart_tg_downloader.** { *; }

# Ignore missing Play Core classes (deferred components referenced by Flutter Engine)
-dontwarn com.google.android.play.core.**

