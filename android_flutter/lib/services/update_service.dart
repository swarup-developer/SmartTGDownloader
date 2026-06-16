import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'package:smart_tg_downloader/version.dart';

class UpdateInfo {
  final bool hasUpdate;
  final String latestVersion;
  final String currentVersion;
  final String whatsNew;
  final String downloadUrl;

  UpdateInfo({
    required this.hasUpdate,
    required this.latestVersion,
    required this.currentVersion,
    required this.whatsNew,
    required this.downloadUrl,
  });
}

class UpdateService {
  static const String _repoUrl = 'https://api.github.com/repos/swarup-developer/SmartTGDownloader/releases/latest';
  static const MethodChannel _channel = MethodChannel('com.example.smart_tg_downloader/device_info');

  /// Checks if a new version is available on GitHub
  static Future<UpdateInfo> checkForUpdates() async {
    try {
      final client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 10);
      final request = await client.getUrl(Uri.parse(_repoUrl));
      request.headers.set('user-agent', 'smart-tg-downloader-updater');
      final response = await request.close();

      if (response.statusCode == 200) {
        final body = await response.transform(utf8.decoder).join();
        final Map<String, dynamic> data = json.decode(body);

        final String tagName = data['tag_name'] ?? '';
        final String bodyText = data['body'] ?? 'No release notes available.';
        final List<dynamic> assets = data['assets'] ?? [];

        // Expecting tag format like "v1.0.2-android"
        final regExp = RegExp(r'^v(\d+\.\d+\.\d+)-android$');
        final match = regExp.firstMatch(tagName);

        if (match != null) {
          final latestVer = match.group(1)!;
          if (_isNewerVersion(latestVer, appVersionName)) {
            // Find the asset that matches Android APK. Since we build split APKs, we can offer the generic or matching apk.
            // Let's search for an asset ending with "-release.apk" or contains "apk".
            String apkUrl = '';
            for (var asset in assets) {
              final String name = asset['name'] ?? '';
              if (name.endsWith('.apk')) {
                // If there's an ABI specific APK, we can return it.
                // For simplicity, we return the first APK we find, or the user can get it from the browser.
                // We'll prioritize arm64-v8a since it's the most common target.
                apkUrl = asset['browser_download_url'] ?? '';
                if (name.contains('arm64-v8a')) {
                  break; // Prefer arm64-v8a
                }
              }
            }

            if (apkUrl.isNotEmpty) {
              return UpdateInfo(
                hasUpdate: true,
                latestVersion: latestVer,
                currentVersion: appVersionName,
                whatsNew: bodyText,
                downloadUrl: apkUrl,
              );
            }
          }
        }
      }
    } catch (e) {
      // Fail silently, returning no update
    }

    return UpdateInfo(
      hasUpdate: false,
      latestVersion: appVersionName,
      currentVersion: appVersionName,
      whatsNew: '',
      downloadUrl: '',
    );
  }

  /// Downloads and triggers installation of the APK file
  static Stream<double> downloadAndInstallApk(String url) async* {
    final client = HttpClient();
    final request = await client.getUrl(Uri.parse(url));
    final response = await request.close();

    if (response.statusCode != 200) {
      throw Exception('Failed to download update: ${response.statusCode}');
    }

    final tempDir = await getTemporaryDirectory();
    final file = File('${tempDir.path}/update.apk');
    if (await file.exists()) {
      await file.delete();
    }

    final int totalBytes = response.contentLength;
    int downloadedBytes = 0;

    final sink = file.openWrite();
    await for (var chunk in response) {
      sink.add(chunk);
      downloadedBytes += chunk.length;
      if (totalBytes > 0) {
        yield downloadedBytes / totalBytes;
      }
    }
    await sink.close();

    // Trigger Android package installer via MethodChannel
    await _channel.invokeMethod('installApk', {'path': file.path});
  }

  static bool _isNewerVersion(String latest, String current) {
    try {
      final l = latest.split('.').map(int.parse).toList();
      final c = current.split('.').map(int.parse).toList();
      for (int i = 0; i < 3; i++) {
        if (l[i] > c[i]) return true;
        if (l[i] < c[i]) return false;
      }
    } catch (_) {}
    return false;
  }
}
