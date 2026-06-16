import 'dart:convert';
import 'dart:io';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter/foundation.dart';
import 'package:sqflite/sqflite.dart';
import 'package:smart_tg_downloader/models/account.dart';
import 'package:smart_tg_downloader/models/download_item.dart';

class StorageService {
  static const String _keyActiveAccountId = 'active_account_id';
  static const String _keyWifiOnly = 'wifi_only';
  static const String _keySpeedLimit = 'speed_limit_kbps';
  static const String _keyDownloadDir = 'download_directory';
  static const String _keyAccounts = 'accounts_list';
  static const String _keyPermissionSetupCompleted = 'permission_setup_completed';

  late SharedPreferences _prefs;
  Database? _db;

  // Private constructor
  StorageService._();

  static final StorageService instance = StorageService._();

  Future<void> init() async {
    _prefs = await SharedPreferences.getInstance();
    try {
      await _initDatabase();
    } catch (e) {
      // Safe fallback for host OS test environments without sqlite ffi
      debugPrint('Database initialization bypassed/failed: $e');
    }
  }

  // --- SQLite Database for Downloads History ---
  Future<void> _initDatabase() async {
    if (Platform.environment.containsKey('FLUTTER_TEST')) {
      return;
    }
    final dbPath = await getDatabasesPath();
    final path = p.join(dbPath, 'tg_downloader.db');
    
    _db = await openDatabase(
      path,
      version: 1,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE downloads (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            category TEXT NOT NULL,
            url TEXT NOT NULL,
            status TEXT NOT NULL,
            totalBytes INTEGER NOT NULL,
            downloadedBytes INTEGER NOT NULL,
            timestamp TEXT NOT NULL,
            error TEXT
          )
        ''');
      },
    );
  }

  Future<List<DownloadItem>> getAllDownloads() async {
    final db = _db;
    if (db == null) return [];
    
    final List<Map<String, dynamic>> maps = await db.query(
      'downloads',
      orderBy: 'timestamp DESC',
    );
    
    return maps.map((map) => DownloadItem.fromMap(map)).toList();
  }

  Future<void> saveDownload(DownloadItem item) async {
    final db = _db;
    if (db == null) return;
    
    await db.insert(
      'downloads',
      item.toMap(),
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<void> deleteDownload(String id) async {
    final db = _db;
    if (db == null) return;
    
    await db.delete(
      'downloads',
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<void> clearDownloadsHistory() async {
    final db = _db;
    if (db == null) return;
    
    // Clear completed or failed downloads from database
    await db.delete(
      'downloads',
      where: 'status = ? OR status = ?',
      whereArgs: [DownloadStatus.completed.name, DownloadStatus.failed.name],
    );
  }

  // --- General Settings (SharedPreferences) ---

  bool getPermissionSetupCompleted() {
    return _prefs.getBool(_keyPermissionSetupCompleted) ?? false;
  }

  Future<void> setPermissionSetupCompleted(bool value) async {
    await _prefs.setBool(_keyPermissionSetupCompleted, value);
  }
  
  String? getActiveAccountId() {
    return _prefs.getString(_keyActiveAccountId);
  }

  Future<void> setActiveAccountId(String? id) async {
    if (id == null) {
      await _prefs.remove(_keyActiveAccountId);
    } else {
      await _prefs.setString(_keyActiveAccountId, id);
    }
  }

  bool getWifiOnlyMode() {
    return _prefs.getBool(_keyWifiOnly) ?? true;
  }

  Future<void> setWifiOnlyMode(bool value) async {
    await _prefs.setBool(_keyWifiOnly, value);
  }

  int getSpeedLimit() {
    return _prefs.getInt(_keySpeedLimit) ?? 0;
  }

  Future<void> setSpeedLimit(int kbps) async {
    await _prefs.setInt(_keySpeedLimit, kbps);
  }

  Future<String> getDownloadDirectory() async {
    final savedPath = _prefs.getString(_keyDownloadDir);
    if (savedPath != null && savedPath.isNotEmpty) {
      return savedPath;
    }
    
    // Default to the standard system Downloads folder on Android
    Directory? extDir;
    try {
      extDir = await getExternalStorageDirectory();
    } catch (_) {}
    
    if (extDir != null) {
      // Typically: /storage/emulated/0/Android/data/com.example.smart_tg_downloader/files
      // We will save to a clean folder under the device's downloads if possible
      final basePath = extDir.path.split('/Android/data')[0];
      return p.join(basePath, 'Download', 'SmartTG');
    }
    
    final appDocDir = await getApplicationDocumentsDirectory();
    return p.join(appDocDir.path, 'SmartTG');
  }

  Future<void> setDownloadDirectory(String path) async {
    await _prefs.setString(_keyDownloadDir, path);
  }

  // --- Account Profiles (SharedPreferences JSON list) ---

  List<Account> loadAccounts() {
    final accountsJson = _prefs.getStringList(_keyAccounts);
    if (accountsJson == null) return [];
    
    try {
      return accountsJson.map((jsonStr) {
        final Map<String, dynamic> map = json.decode(jsonStr);
        return Account.fromMap(map);
      }).toList();
    } catch (_) {
      return [];
    }
  }

  Future<void> saveAccounts(List<Account> accounts) async {
    final List<String> listJson = accounts.map((acc) {
      return json.encode(acc.toMap());
    }).toList();
    await _prefs.setStringList(_keyAccounts, listJson);
  }
}
