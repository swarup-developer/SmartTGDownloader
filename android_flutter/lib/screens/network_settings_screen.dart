import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';

class NetworkSettingsScreen extends StatefulWidget {
  const NetworkSettingsScreen({super.key});

  @override
  State<NetworkSettingsScreen> createState() => _NetworkSettingsScreenState();
}

class _NetworkSettingsScreenState extends State<NetworkSettingsScreen> {
  bool _wifiOnly = true;
  int _speedLimitKbps = 0;
  bool _initialized = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_initialized) {
      _wifiOnly = StorageService.instance.getWifiOnlyMode();
      _speedLimitKbps = StorageService.instance.getSpeedLimit();
      _initialized = true;
    }
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
          'Network & Speed',
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
              // Wi-Fi Guard Section
              const Text(
                'Data Connection Policy',
                style: TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 12),
              Card(
                color: const Color(0xFF1B1E2B),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                elevation: 0,
                child: Semantics(
                  label: 'Wi-Fi guard switch tile',
                  hint: 'Double tap to toggle downloading only when connected to Wi-Fi networks',
                  child: SwitchListTile(
                    secondary: Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: const Color(0xFF6C63FF).withOpacity(0.1),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: const Icon(Icons.wifi_rounded, color: Color(0xFF8B80FF), size: 20),
                    ),
                    title: const Text(
                      'Wi-Fi Guard Mode',
                      style: TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.bold),
                    ),
                    subtitle: const Text(
                      'Pause and queue downloads when disconnected from Wi-Fi to save cellular data',
                      style: TextStyle(color: Colors.white38, fontSize: 11),
                    ),
                    value: _wifiOnly,
                    activeColor: const Color(0xFF8B80FF),
                    activeTrackColor: const Color(0xFF8B80FF).withOpacity(0.3),
                    inactiveThumbColor: Colors.grey[400],
                    inactiveTrackColor: Colors.white12,
                    onChanged: (value) async {
                      setState(() {
                        _wifiOnly = value;
                      });
                      await StorageService.instance.setWifiOnlyMode(value);
                      dp.refreshSettings();
                    },
                  ),
                ),
              ),
              const SizedBox(height: 32),

              // Speed Limit Section
              const Text(
                'Download Speed Limits',
                style: TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 12),
              Card(
                color: const Color(0xFF1B1E2B),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                elevation: 0,
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8.0),
                  child: Column(
                    children: [
                      _buildSpeedLimitTile(dp, 'Unlimited Speed', 0),
                      const Divider(color: Colors.white12, indent: 56),
                      _buildSpeedLimitTile(dp, 'Slow Speed (500 KB/s)', 500),
                      const Divider(color: Colors.white12, indent: 56),
                      _buildSpeedLimitTile(dp, 'Medium Speed (1 MB/s)', 1024),
                      const Divider(color: Colors.white12, indent: 56),
                      _buildSpeedLimitTile(dp, 'Fast Speed (2 MB/s)', 2048),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSpeedLimitTile(DownloadProvider dp, String label, int kbps) {
    final isSelected = _speedLimitKbps == kbps;
    return Semantics(
      label: 'Set download speed to $label button',
      hint: isSelected ? 'Currently selected' : 'Double tap to select this speed limit',
      button: true,
      selected: isSelected,
      child: ListTile(
        onTap: () async {
          setState(() {
            _speedLimitKbps = kbps;
          });
          await StorageService.instance.setSpeedLimit(kbps);
          dp.refreshSettings();
          final text = kbps == 0 ? 'Unlimited speed limit selected' : 'Speed limit changed to $kbps kilobytes per second';
          SemanticsService.announce(text, TextDirection.ltr);
        },
        leading: Container(
          padding: const EdgeInsets.all(6),
          decoration: BoxDecoration(
            color: isSelected ? const Color(0xFF8B80FF).withOpacity(0.15) : Colors.transparent,
            shape: BoxShape.circle,
          ),
          child: Icon(
            kbps == 0 ? Icons.speed_rounded : Icons.shutter_speed_rounded,
            color: isSelected ? const Color(0xFF8B80FF) : Colors.white54,
            size: 18,
          ),
        ),
        title: Text(
          label,
          style: TextStyle(
            color: isSelected ? const Color(0xFF8B80FF) : Colors.white,
            fontSize: 13,
            fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
          ),
        ),
        trailing: isSelected
            ? const Icon(Icons.check_circle_rounded, color: Color(0xFF8B80FF), size: 18)
            : null,
      ),
    );
  }
}
