import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/account_provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';
import 'package:smart_tg_downloader/screens/home_tab.dart';
import 'package:smart_tg_downloader/screens/recent_tab.dart';
import 'package:smart_tg_downloader/screens/settings_screen.dart';
import 'package:smart_tg_downloader/screens/welcome_screen.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';
import 'package:smart_tg_downloader/services/update_service.dart';
import 'package:smart_tg_downloader/widgets/update_dialog.dart';

class MainShell extends StatefulWidget {
  const MainShell({super.key});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  int _currentIndex = 0;

  final List<Widget> _tabs = [
    const HomeTab(),
    const RecentTab(),
    const SettingsScreen(),
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final info = await UpdateService.checkForUpdates();
      if (mounted && info.hasUpdate) {
        UpdateDialog.show(context, info);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final accountProvider = Provider.of<AccountProvider>(context);
    final downloadProvider = Provider.of<DownloadProvider>(context);
    
    // Redirect to WelcomeScreen if logged out
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (accountProvider.activeAccount == null && mounted) {
        Navigator.pushAndRemoveUntil(
          context,
          MaterialPageRoute(builder: (context) => const WelcomeScreen()),
          (route) => false,
        );
      }
    });
    
    return Scaffold(
      backgroundColor: const Color(0xFF12141C),
      appBar: AppBar(
        backgroundColor: const Color(0xFF1B1E2B),
        elevation: 0,
        title: const Text(
          'Smart TG Downloader',
          style: TextStyle(
            color: Colors.white,
            fontSize: 18,
            fontWeight: FontWeight.w800,
            letterSpacing: 0.3,
          ),
        ),
        actions: const [],
      ),
      body: Column(
        children: [
          // Storage Permission Warning
          if (downloadProvider.checkedPermissions && !downloadProvider.storagePermissionGranted)
            Container(
              color: Colors.orange[800],
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
              child: Row(
                children: [
                  const Icon(Icons.warning, color: Colors.white, size: 18),
                  const SizedBox(width: 8),
                  const Expanded(
                    child: Text(
                      'Storage permission is required to save downloads locally.',
                      style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold),
                    ),
                  ),
                  TextButton(
                    onPressed: () {
                      downloadProvider.requestStoragePermission();
                    },
                    style: TextButton.styleFrom(
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      backgroundColor: Colors.white.withOpacity(0.2),
                    ),
                    child: const Text('Grant', style: TextStyle(fontSize: 10, fontWeight: FontWeight.bold)),
                  ),
                ],
              ),
            ),

          // Notification Permission Warning
          if (downloadProvider.checkedPermissions && !downloadProvider.notificationPermissionGranted)
            Container(
              color: Colors.amber[800],
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
              child: Row(
                children: [
                  const Icon(Icons.notifications_active, color: Colors.white, size: 18),
                  const SizedBox(width: 8),
                  const Expanded(
                    child: Text(
                      'Notification permission is needed to show download status.',
                      style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.bold),
                    ),
                  ),
                  TextButton(
                    onPressed: () {
                      downloadProvider.requestNotificationPermission();
                    },
                    style: TextButton.styleFrom(
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      backgroundColor: Colors.white.withOpacity(0.2),
                    ),
                    child: const Text('Allow', style: TextStyle(fontSize: 10, fontWeight: FontWeight.bold)),
                  ),
                ],
              ),
            ),


          
          Expanded(child: _tabs[_currentIndex]),
        ],
      ),
      bottomNavigationBar: NavigationBarTheme(
        data: NavigationBarThemeData(
          indicatorColor: const Color(0xFF6C63FF).withOpacity(0.2),
          labelTextStyle: MaterialStateProperty.resolveWith((states) {
            if (states.contains(MaterialState.selected)) {
              return const TextStyle(color: Color(0xFF6C63FF), fontWeight: FontWeight.bold, fontSize: 12);
            }
            return const TextStyle(color: Colors.white60, fontSize: 12);
          }),
          iconTheme: MaterialStateProperty.resolveWith((states) {
            if (states.contains(MaterialState.selected)) {
              return const IconThemeData(color: Color(0xFF8B80FF));
            }
            return const IconThemeData(color: Colors.white60);
          }),
        ),
        child: NavigationBar(
          backgroundColor: const Color(0xFF1B1E2B),
          selectedIndex: _currentIndex,
          onDestinationSelected: (index) {
            setState(() {
              _currentIndex = index;
            });
          },
          destinations: const [
            NavigationDestination(
              icon: Icon(Icons.download_outlined),
              selectedIcon: Icon(Icons.download),
              label: 'Home',
            ),
            NavigationDestination(
              icon: Icon(Icons.history),
              selectedIcon: Icon(Icons.history),
              label: 'Recent',
            ),
            NavigationDestination(
              icon: Icon(Icons.settings_outlined),
              selectedIcon: Icon(Icons.settings),
              label: 'Settings',
            ),
          ],
        ),
      ),
    );
  }
}

class StorageServiceWifiCheck {
  static bool isWifiGuardEnabled(BuildContext context) {
    try {
      return StorageService.instance.getWifiOnlyMode();
    } catch (_) {
      return true;
    }
  }
}
