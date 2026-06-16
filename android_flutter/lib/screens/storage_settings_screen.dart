import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';

class StorageSettingsScreen extends StatefulWidget {
  const StorageSettingsScreen({super.key});

  @override
  State<StorageSettingsScreen> createState() => _StorageSettingsScreenState();
}

class _StorageSettingsScreenState extends State<StorageSettingsScreen> {
  String _downloadDir = 'Downloads/SmartTG';
  bool _initialized = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_initialized) {
      StorageService.instance.getDownloadDirectory().then((dir) {
        if (mounted) {
          setState(() {
            _downloadDir = dir;
          });
        }
      });
      _initialized = true;
    }
  }

  void _simulateSelectDirectory(DownloadProvider dp) async {
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF1B1E2B),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(20.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'Select Download Folder',
                  style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 8),
                const Text(
                  'Choose a target directory to store downloaded files.',
                  style: TextStyle(color: Colors.white54, fontSize: 12),
                ),
                const SizedBox(height: 20),
                _buildFolderOption(dp, 'Downloads/SmartTG (Default)'),
                const Divider(color: Colors.white12),
                _buildFolderOption(dp, 'Downloads/TelegramMedia'),
                const Divider(color: Colors.white12),
                _buildFolderOption(dp, 'Documents/SmartTGDownloader'),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildFolderOption(DownloadProvider dp, String suffix) {
    return Semantics(
      label: 'Select folder option $suffix button',
      hint: 'Double tap to select this download location',
      button: true,
      child: ListTile(
        leading: const Icon(Icons.folder_rounded, color: Color(0xFF8B80FF), size: 22),
        title: Text(suffix, style: const TextStyle(color: Colors.white, fontSize: 14)),
        onTap: () async {
          setState(() {
            _downloadDir = suffix;
          });
          await StorageService.instance.setDownloadDirectory(suffix);
          dp.refreshSettings();
          if (mounted) {
            Navigator.pop(context);
          }
          SemanticsService.announce('Download folder changed to $suffix', TextDirection.ltr);
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final dp = Provider.of<DownloadProvider>(context);

    return Scaffold(
      backgroundColor: const Color(0xFF12141C),
      appBar: AppBar(
        backgroundColor: const Color(0xFF1B1E2B),
        elevation: 0,
        leading: Semantics(
          label: 'Back button',
          hint: 'Double tap to go back to settings',
          button: true,
          child: IconButton(
            icon: const Icon(Icons.arrow_back_rounded, color: Colors.white),
            tooltip: 'Back',
            onPressed: () => Navigator.pop(context),
          ),
        ),
        title: const Text(
          'Storage & Folders',
          style: TextStyle(color: Colors.white, fontSize: 16, fontWeight: FontWeight.bold),
        ),
      ),
      body: SingleChildScrollView(
        physics: const BouncingScrollPhysics(),
        child: Padding(
          padding: const EdgeInsets.all(20.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Storage Path Configuration',
                style: TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 12),
              Semantics(
                label: 'Download folder selector',
                hint: 'Current directory is $_downloadDir. Double tap to change folder location',
                child: Card(
                  color: const Color(0xFF1B1E2B),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                  elevation: 0,
                  child: ListTile(
                    onTap: () => _simulateSelectDirectory(dp),
                    leading: Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: const Color(0xFF8B80FF).withOpacity(0.1),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: const Icon(Icons.folder_open_rounded, color: Color(0xFF8B80FF), size: 20),
                    ),
                    title: const Text(
                      'Download Folder',
                      style: TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
                    ),
                    subtitle: Text(
                      _downloadDir,
                      style: const TextStyle(color: Colors.white38, fontSize: 11),
                    ),
                    trailing: const Icon(Icons.edit_location_alt_rounded, color: Color(0xFF8B80FF), size: 18),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
