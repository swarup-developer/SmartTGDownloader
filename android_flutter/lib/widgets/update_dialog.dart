import 'dart:io';
import 'package:flutter/material.dart';
import 'package:smart_tg_downloader/services/update_service.dart';

class UpdateDialog extends StatefulWidget {
  final UpdateInfo updateInfo;

  const UpdateDialog({super.key, required this.updateInfo});

  static void show(BuildContext context, UpdateInfo info) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => UpdateDialog(updateInfo: info),
    );
  }

  @override
  State<UpdateDialog> createState() => _UpdateDialogState();
}

class _UpdateDialogState extends State<UpdateDialog> {
  bool _downloadingUpdate = false;
  double _downloadProgress = 0.0;
  String _downloadError = '';

  void _startDownload() {
    UpdateService.downloadAndInstallApk(widget.updateInfo.downloadUrl).listen(
      (progress) {
        setState(() {
          _downloadProgress = progress;
        });
      },
      onError: (err) {
        setState(() {
          _downloadingUpdate = false;
          _downloadError = 'Download failed: $err';
        });
      },
      onDone: () {
        // Installation starts automatically from Android Service
      },
      cancelOnError: true,
    );
  }

  Widget _buildVersionBadge(String label, String version, Color bgColor) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white10),
      ),
      child: Text(
        '$label: $version',
        style: const TextStyle(color: Colors.white70, fontSize: 11, fontWeight: FontWeight.bold),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return WillPopScope(
      onWillPop: () async => false, // Prevent back button dismissal
      child: AlertDialog(
        backgroundColor: const Color(0xFF1B1E2B),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: const Row(
          children: [
            Icon(Icons.system_update_rounded, color: Color(0xFF8B80FF)),
            SizedBox(width: 12),
            Text(
              'New Update Available',
              style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold),
            ),
          ],
        ),
        content: SizedBox(
          width: double.maxFinite,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'A mandatory update is required to continue using the application.',
                style: TextStyle(color: Colors.grey[400], fontSize: 13),
              ),
              const SizedBox(height: 16),
              Row(
                children: [
                  _buildVersionBadge('Current', widget.updateInfo.currentVersion, Colors.white24),
                  const SizedBox(width: 8),
                  const Icon(Icons.arrow_forward_rounded, color: Colors.white30, size: 16),
                  const SizedBox(width: 8),
                  _buildVersionBadge('Latest', widget.updateInfo.latestVersion, const Color(0xFF6C63FF).withOpacity(0.2)),
                ],
              ),
              const SizedBox(height: 20),
              const Text(
                "What's New:",
                style: TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Container(
                constraints: const BoxConstraints(maxHeight: 120),
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF12141C),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.white10),
                ),
                child: SingleChildScrollView(
                  physics: const BouncingScrollPhysics(),
                  child: Text(
                    widget.updateInfo.whatsNew,
                    style: TextStyle(color: Colors.grey[350], fontSize: 12, height: 1.4),
                  ),
                ),
              ),
              if (_downloadingUpdate) ...[
                const SizedBox(height: 24),
                LinearProgressIndicator(
                  value: _downloadProgress,
                  backgroundColor: Colors.white10,
                  valueColor: const AlwaysStoppedAnimation<Color>(Color(0xFF6C63FF)),
                ),
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Downloading update...',
                      style: TextStyle(color: Colors.grey[400], fontSize: 11),
                    ),
                    Text(
                      '${(_downloadProgress * 100).toStringAsFixed(0)}%',
                      style: const TextStyle(color: Color(0xFF8B80FF), fontSize: 11, fontWeight: FontWeight.bold),
                    ),
                  ],
                ),
              ],
              if (_downloadError.isNotEmpty) ...[
                const SizedBox(height: 16),
                Text(
                  _downloadError,
                  style: const TextStyle(color: Colors.redAccent, fontSize: 12, fontWeight: FontWeight.bold),
                ),
              ],
            ],
          ),
        ),
        actions: _downloadingUpdate
            ? []
            : [
                TextButton(
                  onPressed: () => exit(0),
                  child: Text('Exit', style: TextStyle(color: Colors.grey[400])),
                ),
                ElevatedButton(
                  style: ElevatedButton.styleFrom(
                    backgroundColor: const Color(0xFF6C63FF),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  ),
                  onPressed: () {
                    setState(() {
                      _downloadingUpdate = true;
                      _downloadProgress = 0.0;
                      _downloadError = '';
                    });
                    _startDownload();
                  },
                  child: const Text('Update', style: TextStyle(color: Colors.white)),
                ),
              ],
      ),
    );
  }
}
