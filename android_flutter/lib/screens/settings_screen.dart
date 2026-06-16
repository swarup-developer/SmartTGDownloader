import 'package:flutter/material.dart';
import 'package:smart_tg_downloader/screens/account_settings_screen.dart';
import 'package:smart_tg_downloader/screens/network_settings_screen.dart';
import 'package:smart_tg_downloader/screens/storage_settings_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF12141C),
      body: SafeArea(
        child: SingleChildScrollView(
          physics: const BouncingScrollPhysics(),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20.0, vertical: 16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Header
                const SizedBox(height: 16),
                Row(
                  children: [
                    Container(
                      width: 4,
                      height: 28,
                      decoration: BoxDecoration(
                        color: const Color(0xFF8B80FF),
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                    const SizedBox(width: 12),
                    const Text(
                      'Settings',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 0.3,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 28),

                // Settings List
                _buildSettingsTile(
                  context,
                  icon: Icons.person_outline_rounded,
                  title: 'Account Settings',
                  subtitle: 'Manage your active Telegram accounts and sessions',
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(builder: (context) => const AccountSettingsScreen()),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _buildSettingsTile(
                  context,
                  icon: Icons.wifi_rounded,
                  title: 'Network & Speed',
                  subtitle: 'Configure Wi-Fi Guard and download speed limits',
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(builder: (context) => const NetworkSettingsScreen()),
                    );
                  },
                ),
                const SizedBox(height: 12),
                _buildSettingsTile(
                  context,
                  icon: Icons.folder_open_rounded,
                  title: 'Storage & Folders',
                  subtitle: 'Change download directory and file paths',
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(builder: (context) => const StorageSettingsScreen()),
                    );
                  },
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildSettingsTile(
    BuildContext context, {
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return Semantics(
      label: '$title settings button',
      hint: 'Double tap to open $title',
      button: true,
      child: Card(
        color: const Color(0xFF1B1E2B),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        borderOnForeground: false,
        elevation: 0,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(16),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 20.0),
            child: Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: const Color(0xFF8B80FF).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(icon, color: const Color(0xFF8B80FF), size: 24),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        subtitle,
                        style: TextStyle(
                          color: Colors.grey[450],
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                const Icon(Icons.chevron_right_rounded, color: Colors.white30, size: 24),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
