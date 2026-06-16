import 'dart:io';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/account_provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';
import 'package:smart_tg_downloader/screens/login_screen.dart';
import 'package:smart_tg_downloader/screens/main_shell.dart';
import 'package:smart_tg_downloader/screens/permissions_screen.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';
import 'package:smart_tg_downloader/services/update_service.dart';

class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({super.key});

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {

  bool _downloadingUpdate = false;
  double _downloadProgress = 0.0;
  String _downloadError = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await _runUpdateCheck();
    });
  }

  Future<void> _runUpdateCheck() async {
    final info = await UpdateService.checkForUpdates();
    if (mounted) {
      if (info.hasUpdate) {
        _showMandatoryUpdateDialog(info);
      } else {
        await _checkPermissionsAndRedirect();
      }
    }
  }

  Future<void> _checkPermissionsAndRedirect() async {
    final dp = Provider.of<DownloadProvider>(context, listen: false);
    await dp.checkPermissions();
    final hasSetupCompleted = StorageService.instance.getPermissionSetupCompleted();
    if (!hasSetupCompleted && (!dp.storagePermissionGranted || !dp.notificationPermissionGranted)) {
      Navigator.pushReplacement(
        context,
        MaterialPageRoute(
          builder: (context) => PermissionsScreen(
            onPermissionsConfigured: () {
              Navigator.pushReplacement(
                context,
                MaterialPageRoute(builder: (context) => const WelcomeScreen()),
              );
            },
          ),
        ),
      );
    } else {
      final ap = Provider.of<AccountProvider>(context, listen: false);
      if (ap.isLoggedIn) {
        Navigator.pushReplacement(
          context,
          MaterialPageRoute(
            builder: (context) => const MainShell(),
          ),
        );
      }
    }
  }

  void _showMandatoryUpdateDialog(UpdateInfo info) {
    showDialog(
      context: context,
      barrierDismissible: false, // Mandatory
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (context, setDialogState) {
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
                          _buildVersionBadge('Current', info.currentVersion, Colors.white24),
                          const SizedBox(width: 8),
                          const Icon(Icons.arrow_forward_rounded, color: Colors.white30, size: 16),
                          const SizedBox(width: 8),
                          _buildVersionBadge('Latest', info.latestVersion, const Color(0xFF6C63FF).withOpacity(0.2)),
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
                            info.whatsNew,
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
                            setDialogState(() {
                              _downloadingUpdate = true;
                              _downloadProgress = 0.0;
                              _downloadError = '';
                            });
                            setState(() {
                              _downloadingUpdate = true;
                              _downloadProgress = 0.0;
                              _downloadError = '';
                            });
                            _startDownload(info, setDialogState);
                          },
                          child: const Text('Update', style: TextStyle(color: Colors.white)),
                        ),
                      ],
              ),
            );
          },
        );
      },
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

  void _startDownload(UpdateInfo info, StateSetter setDialogState) {
    UpdateService.downloadAndInstallApk(info.downloadUrl).listen(
      (progress) {
        setDialogState(() {
          _downloadProgress = progress;
        });
        setState(() {
          _downloadProgress = progress;
        });
      },
      onError: (err) {
        setDialogState(() {
          _downloadingUpdate = false;
          _downloadError = 'Download failed: $err';
        });
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

  @override
  Widget build(BuildContext context) {
    final ap = Provider.of<AccountProvider>(context);

    final bool isUserLoggedIn = ap.isLoggedIn;

    return Scaffold(
      backgroundColor: const Color(0xFF12141C),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 32.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Spacer(),
                // App Logo
                const ExcludeSemantics(
                  child: CircleAvatar(
                    radius: 50,
                    backgroundColor: Color(0xFF6C63FF),
                    child: Icon(
                      Icons.cloud_download,
                      color: Colors.white,
                      size: 50,
                    ),
                  ),
                ),
                const SizedBox(height: 32),
                
                // Welcome Message
                const Text(
                  'Welcome to Smart TG Downloader',
                  style: TextStyle(
                    color: Colors.white,
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                  ),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 16),
                // Clean List Layout of features
                Expanded(
                  child: ListView(
                    physics: const NeverScrollableScrollPhysics(),
                    shrinkWrap: true,
                    children: [
                      _buildFeatureTile(
                        icon: Icons.flash_on_rounded,
                        title: 'Direct MTProto Connect',
                        desc: 'Safe and direct connection to Telegram servers.',
                      ),
                      _buildFeatureTile(
                        icon: Icons.folder_copy_rounded,
                        title: 'Smart Organization',
                        desc: 'Downloads sorted into Audio, Video, Images, and Docs.',
                      ),
                      _buildFeatureTile(
                        icon: Icons.network_check_rounded,
                        title: 'Wi-Fi Guard Mode',
                        desc: 'Save mobile data by restricting downloads to Wi-Fi.',
                      ),
                      _buildFeatureTile(
                        icon: Icons.accessibility_new_rounded,
                        title: 'Fully Accessible',
                        desc: 'Built for Screen Readers and keyboard navigation.',
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 20),

                // Action Buttons
                if (!isUserLoggedIn)
                  Semantics(
                    label: 'Login with Telegram button',
                    hint: 'Double tap to open Telegram login screen',
                    button: true,
                    child: SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: const Color(0xFF6C63FF),
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                          elevation: 4,
                        ),
                        onPressed: () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) => LoginScreen(
                                onLoginSuccess: () {
                                  // Once logged in, replace screen with main app shell
                                  Navigator.pushReplacement(
                                    context,
                                    MaterialPageRoute(
                                      builder: (context) => const MainShell(),
                                    ),
                                  );
                                },
                              ),
                            ),
                          );
                        },
                        child: const Text(
                          'Click to Login with Telegram',
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: 15,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    ),
                  )
                else ...[
                  const Center(
                    child: CircularProgressIndicator(),
                  ),
                ],
                const SizedBox(height: 16),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildFeatureTile({required IconData icon, required String title, required String desc}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8.0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: const Color(0xFF8B80FF), size: 20),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  desc,
                  style: TextStyle(
                    color: Colors.grey[400],
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
