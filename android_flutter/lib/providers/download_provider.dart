import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:flutter/rendering.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:smart_tg_downloader/models/download_item.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';

class DownloadProvider with ChangeNotifier {
  final StorageService _storage = StorageService.instance;

  List<DownloadItem> _history = [];
  final List<DownloadItem> _activeQueue = [];
  bool _isScanning = false;
  String? _scanError;

  // Mock states for demonstrating guard options in emulator
  bool _mockWifiConnected = true;

  // Runtime Permissions State
  bool _storagePermissionGranted = false;
  bool _notificationPermissionGranted = false;
  bool _checkedPermissions = false;

  DownloadProvider() {
    _loadFromStorage();
    checkPermissions();
  }

  // Getters
  List<DownloadItem> get history => _history;
  List<DownloadItem> get activeQueue => _activeQueue;
  bool get isScanning => _isScanning;
  String? get scanError => _scanError;
  bool get mockWifiConnected => _mockWifiConnected;

  bool get storagePermissionGranted => _storagePermissionGranted;
  bool get notificationPermissionGranted => _notificationPermissionGranted;
  bool get checkedPermissions => _checkedPermissions;

  // Active Downloading Item (convenience for Home screen)
  DownloadItem? get currentDownloading {
    final active = _activeQueue.where((item) => item.status == DownloadStatus.downloading);
    return active.isNotEmpty ? active.first : null;
  }

  Future<void> _loadFromStorage() async {
    _history = await _storage.getAllDownloads();
    notifyListeners();
  }

  // --- Runtime Permissions ---
  Future<void> requestInitialPermissions() async {
    int sdkInt = 30;
    try {
      const channel = MethodChannel('com.example.smart_tg_downloader/device_info');
      sdkInt = await channel.invokeMethod<int>('getAndroidSdkInt') ?? 30;
    } catch (_) {}

    if (sdkInt >= 33) {
      await [
        Permission.photos,
        Permission.notification,
      ].request();
    } else {
      await [
        Permission.storage,
        Permission.notification,
      ].request();
    }
    await checkPermissions();
  }

  Future<void> checkPermissions() async {
    _storagePermissionGranted = await Permission.storage.isGranted || await Permission.photos.isGranted;
    _notificationPermissionGranted = await Permission.notification.isGranted;
    _checkedPermissions = true;
    notifyListeners();
  }

  Future<void> requestStoragePermission() async {
    var status = await Permission.storage.request();
    if (!status.isGranted) {
      status = await Permission.photos.request();
    }
    _storagePermissionGranted = status.isGranted;
    notifyListeners();
  }

  Future<void> requestNotificationPermission() async {
    final status = await Permission.notification.request();
    _notificationPermissionGranted = status.isGranted;
    notifyListeners();
  }

  // Set mock wifi state (to demonstrate Wifi guard feature)
  void setMockWifiConnected(bool value) {
    _mockWifiConnected = value;
    notifyListeners();
    SemanticsService.announce(
      value ? 'Wi-Fi connection restored' : 'Wi-Fi connection lost',
      TextDirection.ltr,
    );
    
    // If we disconnect from wifi and wifi-only is active, we pause downloading items
    if (!value && _storage.getWifiOnlyMode()) {
      _checkWifiGuard();
    }
  }

  void _checkWifiGuard() {
    bool pausedAny = false;
    for (int i = 0; i < _activeQueue.length; i++) {
      if (_activeQueue[i].status == DownloadStatus.downloading) {
        _activeQueue[i] = _activeQueue[i].copyWith(
          status: DownloadStatus.paused,
          error: 'Paused: Waiting for Wi-Fi',
        );
        _storage.saveDownload(_activeQueue[i]);
        pausedAny = true;
      }
    }
    if (pausedAny) {
      SemanticsService.announce(
        'Downloads auto-paused because Wi-Fi disconnected and Wi-Fi Guard is enabled.',
        TextDirection.ltr,
      );
      notifyListeners();
    }
  }

  // --- Scan Telegram Link (Mock) ---
  Future<Map<String, dynamic>?> scanLink(String link, List<String> selectedCategories) async {
    _isScanning = true;
    _scanError = null;
    notifyListeners();

    SemanticsService.announce('Scanning Telegram link...', TextDirection.ltr);

    await Future.delayed(const Duration(seconds: 2));

    final cleanLink = link.trim().toLowerCase();
    if (!cleanLink.contains('t.me/') && !cleanLink.contains('telegram.me/') && !cleanLink.startsWith('tg://')) {
      _scanError = 'Invalid Telegram link format';
      _isScanning = false;
      notifyListeners();
      return null;
    }

    // Mock scanned results
    // Extract channel name from link if possible
    String channelName = 'TG Channel';
    final parts = link.split('/');
    if (parts.length > 3) {
      channelName = parts[3].toUpperCase();
    }

    // Generate random items based on filters
    final List<Map<String, dynamic>> items = [];
    final random = math.Random();
    
    final cats = selectedCategories.contains('All') 
        ? ['Audio', 'Video', 'Images', 'Docs'] 
        : selectedCategories;

    int totalSize = 0;
    final Map<String, int> counts = {'Audio': 0, 'Video': 0, 'Images': 0, 'Docs': 0};

    // Helper titles
    final titles = {
      'Audio': ['Song_Remix_2026.mp3', 'Podcast_Episode_45.mp3', 'Voice_Note_June.ogg', 'Audiobook_Ch1.mp3'],
      'Video': ['Tutorial_Flutter_Advanced.mp4', 'Funny_Cats_HD.mp4', 'Vacation_Vlog.mp4', 'Movie_Clip.mkv'],
      'Images': ['Infographic_Design.png', 'Wallpaper_OLED.jpg', 'Screenshot_TG.jpg', 'Diagram_Arch.png'],
      'Docs': ['Telegram_Guide.pdf', 'Source_Code_Archive.zip', 'Presentation_Slides.pptx', 'Readme_Build.txt'],
    };

    int index = 0;
    for (final cat in cats) {
      final numItems = random.nextInt(3) + 1; // 1 to 3 items per category
      for (int i = 0; i < numItems; i++) {
        final categoryTitles = titles[cat]!;
        final fileTitle = categoryTitles[random.nextInt(categoryTitles.length)] + ' (${random.nextInt(100) + 1})';
        final sizeBytes = (random.nextInt(80) + 5) * 1024 * 1024; // 5MB to 85MB
        
        final key = 'tg_media_${cat.toLowerCase()}_${DateTime.now().millisecondsSinceEpoch}_$index';
        items.add({
          'key': key,
          'title': fileTitle,
          'category': cat,
          'size_bytes': sizeBytes,
          'msg_id': random.nextInt(9000) + 100,
          'url': '$link/${random.nextInt(100)}',
        });
        
        totalSize += sizeBytes;
        counts[cat] = (counts[cat] ?? 0) + 1;
        index++;
      }
    }

    final int speedKbps = _storage.getSpeedLimit() > 0 ? _storage.getSpeedLimit() : 2048; // default 2MB/s mock speed
    final int estimatedSeconds = totalSize ~/ (speedKbps * 1024);

    _isScanning = false;
    notifyListeners();

    final summary = {
      'title': channelName,
      'count': items.length,
      'total_size': totalSize,
      'estimated_seconds': estimatedSeconds,
      'counts': counts,
      'items': items,
    };

    SemanticsService.announce(
      'Scan complete. Found ${items.length} files. Total size is ${_formatBytes(totalSize)}',
      TextDirection.ltr,
    );

    return summary;
  }

  // --- Start Downloads ---
  Future<void> startDownloads(Map<String, dynamic> scanSummary, List<String> selectedKeys) async {
    final List<dynamic> items = scanSummary['items'];
    
    for (final itemMap in items) {
      if (!selectedKeys.contains(itemMap['key'])) continue;
      
      final newItem = DownloadItem(
        id: itemMap['key'],
        title: itemMap['title'],
        category: itemMap['category'],
        url: itemMap['url'],
        status: DownloadStatus.pending,
        totalBytes: itemMap['size_bytes'],
        downloadedBytes: 0,
        timestamp: DateTime.now(),
      );

      // Add to database & memory queue
      await _storage.saveDownload(newItem);
      
      // Update local history
      final existingIndex = _history.indexWhere((h) => h.id == newItem.id);
      if (existingIndex != -1) {
        _history[existingIndex] = newItem;
      } else {
        _history.insert(0, newItem);
      }
      
      _activeQueue.add(newItem);
    }
    
    SemanticsService.announce(
      'Added ${selectedKeys.length} files to download queue',
      TextDirection.ltr,
    );
    notifyListeners();

    // Trigger queue processor
    _processQueue();
  }

  // --- Queue Processor ---
  bool _isProcessingQueue = false;
  
  Future<void> _processQueue() async {
    if (_isProcessingQueue) return;
    _isProcessingQueue = true;

    while (_activeQueue.isNotEmpty) {
      // Find the first pending item
      final pendingIndex = _activeQueue.indexWhere((item) => item.status == DownloadStatus.pending);
      if (pendingIndex == -1) {
        // No pending items left (could be downloading, paused, etc.)
        break;
      }

      final item = _activeQueue[pendingIndex];
      await _executeDownload(item);
    }

    _isProcessingQueue = false;
    notifyListeners();
  }

  Future<void> _executeDownload(DownloadItem item) async {
    int indexInQueue = _activeQueue.indexWhere((i) => i.id == item.id);
    if (indexInQueue == -1) return;

    // Start Downloading
    _activeQueue[indexInQueue] = _activeQueue[indexInQueue].copyWith(
      status: DownloadStatus.downloading,
      error: null,
    );
    await _storage.saveDownload(_activeQueue[indexInQueue]);
    
    // Also update history list
    _updateHistoryItem(_activeQueue[indexInQueue]);
    notifyListeners();

    SemanticsService.announce('Starting download of ${item.title}', TextDirection.ltr);

    // Simulated download loop
    int downloaded = 0;
    final total = item.totalBytes;
    
    // Track speed calculation
    DateTime lastTime = DateTime.now();
    int lastDownloaded = 0;
    
    const tickDuration = Duration(milliseconds: 250);
    
    while (downloaded < total) {
      await Future.delayed(tickDuration);
      
      // Fetch latest item state (to support Pause/Cancel mid-download)
      indexInQueue = _activeQueue.indexWhere((i) => i.id == item.id);
      if (indexInQueue == -1) {
        // Canceled/Deleted
        return;
      }
      
      final currentItemState = _activeQueue[indexInQueue];
      if (currentItemState.status == DownloadStatus.paused) {
        // Paused by user or Wi-Fi guard
        return;
      }

      // Check Wi-Fi Guard
      if (_storage.getWifiOnlyMode() && !_mockWifiConnected) {
        _activeQueue[indexInQueue] = currentItemState.copyWith(
          status: DownloadStatus.paused,
          error: 'Paused: Waiting for Wi-Fi',
        );
        await _storage.saveDownload(_activeQueue[indexInQueue]);
        _updateHistoryItem(_activeQueue[indexInQueue]);
        notifyListeners();
        SemanticsService.announce('Download paused. Wi-Fi connection lost.', TextDirection.ltr);
        return;
      }

      // Speed limit calculations (bytes per tick)
      final limitKbps = _storage.getSpeedLimit();
      int bytesPerTick = 1024 * 1024; // Default chunk: 1MB per tick (~4MB/s)
      
      if (limitKbps > 0) {
        // Throttle transfer rate based on limit setting
        // limitKbps is per second. We tick every 250ms (4 times per second).
        bytesPerTick = (limitKbps * 1024) ~/ 4;
        if (bytesPerTick <= 0) bytesPerTick = 1024; // safety minimum
      }

      // Increment downloaded bytes
      downloaded = math.min(downloaded + bytesPerTick, total);

      // Speed Estimation
      final now = DateTime.now();
      final elapsedSec = now.difference(lastTime).inMilliseconds / 1000.0;
      double currentSpeed = currentItemState.speedKbps;
      
      if (elapsedSec >= 0.5) {
        final diffBytes = downloaded - lastDownloaded;
        currentSpeed = (diffBytes / 1024) / elapsedSec;
        
        lastTime = now;
        lastDownloaded = downloaded;
      }

      _activeQueue[indexInQueue] = currentItemState.copyWith(
        downloadedBytes: downloaded,
        speedKbps: currentSpeed,
      );
      
      _updateHistoryItem(_activeQueue[indexInQueue]);
      notifyListeners();
    }

    // Download Complete
    indexInQueue = _activeQueue.indexWhere((i) => i.id == item.id);
    if (indexInQueue != -1) {
      final completedItem = _activeQueue[indexInQueue].copyWith(
        status: DownloadStatus.completed,
        downloadedBytes: total,
        speedKbps: 0.0,
      );
      
      await _storage.saveDownload(completedItem);
      _updateHistoryItem(completedItem);
      _activeQueue.removeAt(indexInQueue);
      
      SemanticsService.announce(
        'Download complete: ${item.title}. Saved in Download, Smart TG, ${item.category} directory.',
        TextDirection.ltr,
      );
      
      notifyListeners();
    }
  }

  void _updateHistoryItem(DownloadItem item) {
    final idx = _history.indexWhere((h) => h.id == item.id);
    if (idx != -1) {
      _history[idx] = item;
    }
  }

  // --- Pause Download ---
  Future<void> pauseDownload(String id) async {
    final idx = _activeQueue.indexWhere((i) => i.id == id);
    if (idx != -1) {
      _activeQueue[idx] = _activeQueue[idx].copyWith(
        status: DownloadStatus.paused,
        speedKbps: 0.0,
      );
      await _storage.saveDownload(_activeQueue[idx]);
      _updateHistoryItem(_activeQueue[idx]);
      
      SemanticsService.announce('Paused download of ${_activeQueue[idx].title}', TextDirection.ltr);
      notifyListeners();
    }
  }

  // --- Resume Download ---
  Future<void> resumeDownload(String id) async {
    final idx = _activeQueue.indexWhere((i) => i.id == id);
    if (idx != -1) {
      _activeQueue[idx] = _activeQueue[idx].copyWith(
        status: DownloadStatus.pending,
        error: null,
      );
      await _storage.saveDownload(_activeQueue[idx]);
      _updateHistoryItem(_activeQueue[idx]);
      
      SemanticsService.announce('Resuming download of ${_activeQueue[idx].title}', TextDirection.ltr);
      notifyListeners();
      
      _processQueue(); // trigger processing loop
    }
  }

  // --- Cancel/Delete Download ---
  Future<void> cancelDownload(String id) async {
    // Remove from active queue
    final activeIdx = _activeQueue.indexWhere((i) => i.id == id);
    String title = 'item';
    if (activeIdx != -1) {
      title = _activeQueue[activeIdx].title;
      _activeQueue.removeAt(activeIdx);
    }

    // Delete from database & history list
    await _storage.deleteDownload(id);
    _history.removeWhere((h) => h.id == id);
    
    SemanticsService.announce('Deleted $title from history', TextDirection.ltr);
    notifyListeners();
  }

  // --- Retry Failed Download ---
  Future<void> retryDownload(DownloadItem item) async {
    final pendingItem = item.copyWith(
      status: DownloadStatus.pending,
      downloadedBytes: 0,
      speedKbps: 0.0,
      error: null,
    );

    await _storage.saveDownload(pendingItem);
    
    final histIdx = _history.indexWhere((h) => h.id == item.id);
    if (histIdx != -1) {
      _history[histIdx] = pendingItem;
    }

    _activeQueue.add(pendingItem);
    
    SemanticsService.announce('Retrying download of ${item.title}', TextDirection.ltr);
    notifyListeners();
    
    _processQueue();
  }

  // --- Clear History ---
  Future<void> clearHistory() async {
    await _storage.clearDownloadsHistory();
    // Keep active/paused items, reload completed ones
    _history.removeWhere((item) => 
      item.status == DownloadStatus.completed || 
      item.status == DownloadStatus.failed
    );
    SemanticsService.announce('Download history cleared', TextDirection.ltr);
    notifyListeners();
  }

  void refreshSettings() {
    notifyListeners();
  }

  static String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const suffixes = ['B', 'KB', 'MB', 'GB'];
    var i = 0;
    double dBytes = bytes.toDouble();
    while (dBytes >= 1024 && i < suffixes.length - 1) {
      dBytes /= 1024;
      i++;
    }
    return '${dBytes.toStringAsFixed(1)} ${suffixes[i]}';
  }
}
