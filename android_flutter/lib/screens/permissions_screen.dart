import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';

class PermissionsScreen extends StatefulWidget {
  final VoidCallback onPermissionsConfigured;

  const PermissionsScreen({super.key, required this.onPermissionsConfigured});

  @override
  State<PermissionsScreen> createState() => _PermissionsScreenState();
}

class _PermissionsScreenState extends State<PermissionsScreen> {
  bool _hasRequested = false;

  @override
  void initState() {
    super.initState();
    // Check initial permissions status on load
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final dp = Provider.of<DownloadProvider>(context, listen: false);
      await dp.checkPermissions();
      if (dp.storagePermissionGranted && dp.notificationPermissionGranted) {
        await StorageService.instance.setPermissionSetupCompleted(true);
        widget.onPermissionsConfigured();
      }
    });
  }

  Future<void> _handleGrantPermissions(DownloadProvider dp) async {
    await dp.requestInitialPermissions();
    setState(() {
      _hasRequested = true;
    });
    if (dp.storagePermissionGranted && dp.notificationPermissionGranted) {
      await StorageService.instance.setPermissionSetupCompleted(true);
      widget.onPermissionsConfigured();
    }
  }

  @override
  Widget build(BuildContext context) {
    final dp = Provider.of<DownloadProvider>(context);

    final bool storageGranted = dp.storagePermissionGranted;
    final bool notificationGranted = dp.notificationPermissionGranted;
    final bool allGranted = storageGranted && notificationGranted;

    return Scaffold(
      backgroundColor: const Color(0xFF12141C),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const SizedBox(height: 24),
              // Header
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: const Color(0xFF6C63FF).withOpacity(0.1),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(
                      Icons.security_rounded,
                      color: Color(0xFF8B80FF),
                      size: 28,
                    ),
                  ),
                  const SizedBox(width: 16),
                  const Text(
                    'Permissions Required',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 22,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 0.3,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              Text(
                'To download Telegram media files, this application requires a few system permissions. Please review the details below:',
                style: TextStyle(color: Colors.grey[400], fontSize: 13, height: 1.4),
              ),
              const SizedBox(height: 32),

              // Permissions List
              Expanded(
                child: ListView(
                  physics: const BouncingScrollPhysics(),
                  children: [
                    // Storage Permission explanation
                    _buildPermissionExplanationCard(
                      title: 'Storage Access',
                      description: 'Used to save downloaded Telegram audio, video, documents, and images directly to your device storage (Downloads/SmartTG). This makes your files accessible to other media players and file managers.',
                      icon: Icons.folder_shared_rounded,
                      iconColor: const Color(0xFF6C63FF),
                      isGranted: storageGranted,
                    ),
                    const SizedBox(height: 20),

                    // Notification Permission explanation
                    _buildPermissionExplanationCard(
                      title: 'System Notifications',
                      description: 'Used to show real-time download progress, active speeds, queue statuses, and completion alerts in your notification bar. This ensures background downloads run reliably and remain active.',
                      icon: Icons.notifications_active_rounded,
                      iconColor: const Color(0xFF8B80FF),
                      isGranted: notificationGranted,
                    ),
                  ],
                ),
              ),

              // Action buttons footer
              const SizedBox(height: 16),
              if (!allGranted) ...[
                Semantics(
                  label: _hasRequested ? 'Retry granting required permissions' : 'Grant required permissions',
                  hint: 'Requests storage and notification access',
                  button: true,
                  child: SizedBox(
                    width: double.infinity,
                    child: ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF6C63FF),
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(14),
                        ),
                        elevation: 4,
                        shadowColor: const Color(0xFF6C63FF).withOpacity(0.3),
                      ),
                      onPressed: () => _handleGrantPermissions(dp),
                      child: Text(
                        _hasRequested ? 'Retry Granting Permissions' : 'Grant Required Permissions',
                        style: const TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPermissionExplanationCard({
    required String title,
    required String description,
    required IconData icon,
    required Color iconColor,
    required bool isGranted,
  }) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF1B1E2B),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isGranted ? const Color(0xFF4CAF50).withOpacity(0.3) : Colors.white.withOpacity(0.05),
          width: 1.2,
        ),
      ),
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: iconColor.withOpacity(0.12),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(icon, color: iconColor, size: 20),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 15,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              // Status Badge
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: isGranted 
                      ? const Color(0xFF4CAF50).withOpacity(0.12)
                      : Colors.white.withOpacity(0.05),
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      isGranted ? Icons.check_circle_rounded : Icons.cancel_rounded,
                      color: isGranted ? const Color(0xFF4CAF50) : Colors.grey[500],
                      size: 14,
                    ),
                    const SizedBox(width: 6),
                    Text(
                      isGranted ? 'Granted' : 'Required',
                      style: TextStyle(
                        color: isGranted ? const Color(0xFF4CAF50) : Colors.grey[400],
                        fontSize: 11,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            description,
            style: TextStyle(
              color: Colors.grey[400],
              fontSize: 12.5,
              height: 1.45,
            ),
          ),
        ],
      ),
    );
  }
}
