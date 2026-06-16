import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/rendering.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/models/download_item.dart';
import 'package:smart_tg_downloader/providers/account_provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';
import 'package:smart_tg_downloader/screens/login_screen.dart';
import 'package:smart_tg_downloader/widgets/scan_results_sheet.dart';

class HomeTab extends StatefulWidget {
  const HomeTab({super.key});

  @override
  State<HomeTab> createState() => _HomeTabState();
}

class _HomeTabState extends State<HomeTab> {
  final TextEditingController _linkController = TextEditingController();

  @override
  void dispose() {
    _linkController.dispose();
    super.dispose();
  }

  void _pasteFromClipboard() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (data != null && data.text != null) {
      setState(() {
        _linkController.text = data.text!;
      });
      SemanticsService.announce('Pasted link from clipboard', TextDirection.ltr);
    }
  }

  void _clearInput() {
    setState(() {
      _linkController.clear();
    });
    SemanticsService.announce('Cleared link input', TextDirection.ltr);
  }

  @override
  Widget build(BuildContext context) {
    final ap = Provider.of<AccountProvider>(context);
    final dp = Provider.of<DownloadProvider>(context);

    final bool isUserLoggedIn = ap.isLoggedIn;
    final activeDownload = dp.currentDownloading;

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
                // Welcome Header
                const SizedBox(height: 16),
                Row(
                  children: [
                    Container(
                      width: 4,
                      height: 28,
                      decoration: BoxDecoration(
                        color: const Color(0xFF6C63FF),
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                    const SizedBox(width: 12),
                    const Text(
                      'Smart TG Downloader',
                      style: TextStyle(
                        color: Colors.white,
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 0.3,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Padding(
                  padding: const EdgeInsets.only(left: 16.0),
                  child: Text(
                    'Scan and download Telegram message media.',
                    style: TextStyle(color: Colors.grey[500], fontSize: 13, fontWeight: FontWeight.w400),
                  ),
                ),
                const SizedBox(height: 28),

                // NOT LOGGED IN SHIELD
                if (!isUserLoggedIn) _buildLoginPrompt(context) else ...[
                  // DOWNLOAD INPUT SECTION
                  _buildLinkInputCard(context, dp),
                  const SizedBox(height: 20),
                  
                  // MOCK CONTROLLER CARD (FOR TESTING WIFI GUARD AND SPEED LIMITS IN PROTOTYPE)
                  _buildMockEnvironmentControls(context, dp),
                  const SizedBox(height: 20),

                  // ACTIVE DOWNLOAD PROGRESS MONITOR
                  if (activeDownload != null)
                    _buildActiveDownloadCard(context, dp, activeDownload)
                  else if (dp.activeQueue.isNotEmpty)
                    _buildQueueStatusCard(context, dp),
                  const SizedBox(height: 20),
                  _buildStepsCard(context),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  // LOGIN PROMPT WIDGET
  Widget _buildLoginPrompt(BuildContext context) {
    return Semantics(
      container: true,
      label: 'Login required card',
      child: Container(
        decoration: BoxDecoration(
          color: const Color(0xFF1B1E2B),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: Colors.white.withOpacity(0.05), width: 1),
        ),
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            children: [
              ExcludeSemantics(
                child: Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: const Color(0xFF6C63FF).withOpacity(0.1),
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(
                    Icons.lock_outline_rounded,
                    color: Color(0xFF8B80FF),
                    size: 32,
                  ),
                ),
              ),
              const SizedBox(height: 20),
              const Text(
                'Telegram Authorization Required',
                style: TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.bold, letterSpacing: 0.2),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 10),
              Text(
                'To download media files from Telegram, please log in with your phone number. Your credentials will remain secure.',
                style: TextStyle(color: Colors.grey[400], fontSize: 13, height: 1.4),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              Semantics(
                label: 'Login with Telegram button',
                hint: 'Double tap to open Telegram login screen',
                button: true,
                child: SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFF6C63FF),
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                      elevation: 2,
                      shadowColor: const Color(0xFF6C63FF).withOpacity(0.3),
                    ),
                    onPressed: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(builder: (context) => const LoginScreen()),
                      );
                    },
                    child: const Text(
                      'Login with Telegram',
                      style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  // LINK ENTRY AND SCAN CARD
  Widget _buildLinkInputCard(BuildContext context, DownloadProvider dp) {
    final bool hasLink = _linkController.text.trim().isNotEmpty;
    
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF1B1E2B),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withOpacity(0.05), width: 1),
      ),
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Telegram Message Link',
              style: TextStyle(color: Colors.white70, fontSize: 12, fontWeight: FontWeight.bold, letterSpacing: 0.5),
            ),
            const SizedBox(height: 12),
            
            Semantics(
              label: 'Telegram link input box',
              hint: 'Enter or paste a Telegram message link here',
              child: TextField(
                controller: _linkController,
                style: const TextStyle(color: Colors.white, fontSize: 14),
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  hintText: 'e.g., t.me/c/1234567/890',
                  hintStyle: TextStyle(color: Colors.grey[600], fontSize: 13),
                  contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
                  filled: true,
                  fillColor: const Color(0xFF12141C),
                  suffixIcon: _linkController.text.isEmpty
                      ? Semantics(
                          label: 'Paste link from clipboard',
                          child: IconButton(
                            icon: const Icon(Icons.content_paste_rounded, color: Color(0xFF8B80FF), size: 20),
                            onPressed: _pasteFromClipboard,
                            tooltip: 'Paste',
                          ),
                        )
                      : Semantics(
                          label: 'Clear text input',
                          child: IconButton(
                            icon: const Icon(Icons.clear_rounded, color: Colors.white54, size: 20),
                            onPressed: _clearInput,
                            tooltip: 'Clear',
                          ),
                        ),
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide(color: Colors.white.withOpacity(0.08)),
                  ),
                  enabledBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: BorderSide(color: Colors.white.withOpacity(0.08)),
                  ),
                  focusedBorder: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: const BorderSide(color: Color(0xFF6C63FF), width: 1.5),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),
            
            // Action Scan button
            SizedBox(
              width: double.infinity,
              child: dp.isScanning
                  ? const Center(
                      child: Padding(
                        padding: EdgeInsets.all(8.0),
                        child: CircularProgressIndicator(color: Color(0xFF6C63FF)),
                      ),
                    )
                  : Semantics(
                      label: 'Scan post and download media button',
                      hint: 'Double tap to parse the link and show downloadable media',
                      button: true,
                      enabled: hasLink,
                      child: ElevatedButton(
                        style: ElevatedButton.styleFrom(
                          backgroundColor: hasLink ? const Color(0xFF6C63FF) : const Color(0xFF12141C),
                          foregroundColor: hasLink ? Colors.white : Colors.white24,
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                          elevation: hasLink ? 2 : 0,
                          shadowColor: hasLink ? const Color(0xFF6C63FF).withOpacity(0.3) : Colors.transparent,
                        ),
                        onPressed: hasLink
                            ? () async {
                                final summary = await dp.scanLink(_linkController.text, const ['All']);
                                if (summary != null && mounted) {
                                  showModalBottomSheet(
                                    context: context,
                                    isScrollControlled: true,
                                    backgroundColor: Colors.transparent,
                                    builder: (context) => ScanResultsSheet(scanSummary: summary),
                                  );
                                } else if (dp.scanError != null && mounted) {
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    SnackBar(
                                      content: Text('Error: ${dp.scanError}'),
                                      backgroundColor: Colors.redAccent,
                                    ),
                                  );
                                }
                              }
                            : null,
                        child: const Text(
                          'Scan & Download',
                          style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                        ),
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  // ACTIVE PROGRESS MONITOR CARD (MAPPED TO MOUSE/SCREEN READER SWIPE ONCE)
  Widget _buildActiveDownloadCard(BuildContext context, DownloadProvider dp, var item) {
    return MergeSemantics(
      child: Semantics(
        container: true,
        label: 'Active download progress monitor',
        child: Container(
          decoration: BoxDecoration(
            color: const Color(0xFF1B1E2B),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.white.withOpacity(0.05), width: 1),
          ),
          child: Padding(
            padding: const EdgeInsets.all(16.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(6),
                      decoration: BoxDecoration(
                        color: const Color(0xFF8B80FF).withOpacity(0.12),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Icon(
                        item.category == 'Audio' 
                            ? Icons.music_note_rounded 
                            : item.category == 'Video' 
                                ? Icons.play_arrow_rounded 
                                : Icons.insert_drive_file_rounded,
                        color: const Color(0xFF8B80FF),
                        size: 18,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        item.title,
                        style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 14),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                
                // Progress Bar
                ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: item.progress,
                    backgroundColor: const Color(0xFF12141C),
                    color: const Color(0xFF6C63FF),
                    minHeight: 6,
                  ),
                ),
                const SizedBox(height: 12),
                
                // Stats row
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      '${item.formattedDownloaded} / ${item.formattedSize}',
                      style: TextStyle(color: Colors.grey[400], fontSize: 11),
                    ),
                    Text(
                      item.formattedSpeed,
                      style: const TextStyle(color: Color(0xFF4CAF50), fontSize: 11, fontWeight: FontWeight.bold),
                    ),
                    Text(
                      item.etaString,
                      style: TextStyle(color: Colors.grey[400], fontSize: 11),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                
                // Controls Row
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Semantics(
                      label: 'Pause download button',
                      hint: 'Double tap to temporarily pause the downloading file',
                      button: true,
                      child: TextButton.icon(
                        icon: const Icon(Icons.pause_rounded, size: 16, color: Colors.white70),
                        label: const Text('Pause', style: TextStyle(color: Colors.white70, fontSize: 12, fontWeight: FontWeight.w600)),
                        onPressed: () => dp.pauseDownload(item.id),
                        style: TextButton.styleFrom(
                          backgroundColor: Colors.white.withOpacity(0.05),
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Semantics(
                      label: 'Cancel download button',
                      hint: 'Double tap to cancel the download and discard files',
                      button: true,
                      child: TextButton.icon(
                        icon: const Icon(Icons.close_rounded, size: 16, color: Colors.redAccent),
                        label: const Text('Cancel', style: TextStyle(color: Colors.redAccent, fontSize: 12, fontWeight: FontWeight.w600)),
                        onPressed: () => dp.cancelDownload(item.id),
                        style: TextButton.styleFrom(
                          backgroundColor: Colors.redAccent.withOpacity(0.1),
                          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // QUEUE STATUS CARD (FOR MULTIPLE FILES QUEUED)
  Widget _buildQueueStatusCard(BuildContext context, DownloadProvider dp) {
    final itemsCount = dp.activeQueue.length;
    final pausedItems = dp.activeQueue.where((i) => i.status == DownloadStatus.paused).length;
    
    return MergeSemantics(
      child: Semantics(
        container: true,
        label: 'Queue progress card',
        child: Container(
          decoration: BoxDecoration(
            color: const Color(0xFF1B1E2B),
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.white.withOpacity(0.05), width: 1),
          ),
          child: ListTile(
            contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
            leading: const CircularProgressIndicator(
              color: Color(0xFF8B80FF),
              strokeWidth: 3,
            ),
            title: Text(
              '$itemsCount files in download queue',
              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 14),
            ),
            subtitle: Text(
              pausedItems > 0 ? '$pausedItems downloads paused' : 'Downloads are running...',
              style: TextStyle(color: Colors.grey[400], fontSize: 12),
            ),
            trailing: pausedItems > 0
                ? Semantics(
                    label: 'Resume all downloads button',
                    hint: 'Double tap to resume downloading all paused items',
                    button: true,
                    child: TextButton(
                      onPressed: () {
                        final firstPaused = dp.activeQueue.firstWhere((i) => i.status == DownloadStatus.paused);
                        dp.resumeDownload(firstPaused.id);
                      },
                      style: TextButton.styleFrom(
                        foregroundColor: const Color(0xFF8B80FF),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
                      ),
                      child: const Text('Resume All', style: TextStyle(fontWeight: FontWeight.bold)),
                    ),
                  )
                : null,
          ),
        ),
      ),
    );
  }

  // MOCK ENVIRONMENT CONTROLLERS FOR EMULATOR DEMO
  Widget _buildMockEnvironmentControls(BuildContext context, DownloadProvider dp) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF1B1E2B).withOpacity(0.4),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withOpacity(0.04), width: 1),
      ),
      child: Semantics(
        label: 'Simulate Wi-Fi Connection switch',
        hint: 'Double tap to toggle mock Wi-Fi status for testing the Wi-Fi guard feature',
        child: SwitchListTile(
          secondary: Container(
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(
              color: const Color(0xFF8B80FF).withOpacity(0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Icon(Icons.wifi_tethering_rounded, color: Color(0xFF8B80FF), size: 18),
          ),
          title: const Text(
            'Simulate Wi-Fi Connection',
            style: TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w500),
          ),
          subtitle: const Text(
            'Toggle to test Wi-Fi Guard speed-limiters',
            style: TextStyle(color: Colors.white30, fontSize: 10),
          ),
          value: dp.mockWifiConnected,
          activeColor: const Color(0xFF8B80FF),
          activeTrackColor: const Color(0xFF8B80FF).withOpacity(0.3),
          inactiveThumbColor: Colors.grey[400],
          inactiveTrackColor: Colors.white12,
          onChanged: (val) {
            dp.setMockWifiConnected(val);
          },
        ),
      ),
    );
  }

  Widget _buildStepsCard(BuildContext context) {
    final steps = [
      {
        'step': '1',
        'title': 'Copy Telegram Link',
        'desc': 'Find a public Telegram message or channel post and copy its shareable link.',
      },
      {
        'step': '2',
        'title': 'Paste & Scan',
        'desc': 'Paste the link in the input field above and click Scan & Download.',
      },
      {
        'step': '3',
        'title': 'Download Media',
        'desc': 'Select the files you want to download from the popup sheet and track progress.',
      },
    ];

    return Card(
      color: const Color(0xFF1B1E2B),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(20.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'How to Download',
              style: TextStyle(
                color: Colors.white,
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            ListView.separated(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              itemCount: steps.length,
              separatorBuilder: (context, index) => const Divider(color: Colors.white12, height: 16),
              itemBuilder: (context, index) {
                final item = steps[index];
                return Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    CircleAvatar(
                      radius: 10,
                      backgroundColor: const Color(0xFF6C63FF),
                      child: Text(
                        item['step']!,
                        style: const TextStyle(color: Colors.white, fontSize: 11, fontWeight: FontWeight.bold),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            item['title']!,
                            style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            item['desc']!,
                            style: TextStyle(color: Colors.grey[400], fontSize: 11),
                          ),
                        ],
                      ),
                    ),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}
