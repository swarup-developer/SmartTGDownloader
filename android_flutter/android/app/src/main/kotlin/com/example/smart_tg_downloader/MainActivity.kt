package com.example.smart_tg_downloader

import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.smart_tg_downloader/device_info"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            if (call.method == "getAndroidSdkInt") {
                result.success(Build.VERSION.SDK_INT)
            } else if (call.method == "installApk") {
                val path = call.argument<String>("path")
                if (path != null) {
                    try {
                        val file = File(path)
                        val intent = Intent(Intent.ACTION_VIEW)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val apkUri = FileProvider.getUriForFile(
                                context,
                                context.packageName + ".fileprovider",
                                file
                            )
                            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } else {
                            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                        }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("INSTALL_ERROR", e.message, null)
                    }
                } else {
                    result.error("BAD_ARGS", "Path was null", null)
                }
            } else {
                result.notImplemented()
            }
        }
    }
}
