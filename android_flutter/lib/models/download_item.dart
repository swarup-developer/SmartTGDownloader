enum DownloadStatus {
  pending,
  downloading,
  paused,
  completed,
  failed,
}

class DownloadItem {
  final String id;
  final String title;
  final String category; // 'Audio', 'Video', 'Images', 'Docs'
  final String url;
  final DownloadStatus status;
  final int totalBytes;
  final int downloadedBytes;
  final double speedKbps;
  final DateTime timestamp;
  final String? error;

  DownloadItem({
    required this.id,
    required this.title,
    required this.category,
    required this.url,
    this.status = DownloadStatus.pending,
    this.totalBytes = 0,
    this.downloadedBytes = 0,
    this.speedKbps = 0.0,
    required this.timestamp,
    this.error,
  });

  double get progress {
    if (totalBytes <= 0) return 0.0;
    return (downloadedBytes / totalBytes).clamp(0.0, 1.0);
  }

  String get formattedSize {
    return _formatBytes(totalBytes);
  }

  String get formattedDownloaded {
    return _formatBytes(downloadedBytes);
  }

  String get formattedSpeed {
    if (speedKbps <= 0) return '';
    if (speedKbps >= 1024) {
      return '${(speedKbps / 1024).toStringAsFixed(1)} MB/s';
    }
    return '${speedKbps.toStringAsFixed(0)} KB/s';
  }

  String get etaString {
    if (status != DownloadStatus.downloading || speedKbps <= 0) return '';
    final remainingBytes = totalBytes - downloadedBytes;
    if (remainingBytes <= 0) return '';
    
    final speedBytes = speedKbps * 1024;
    final seconds = remainingBytes / speedBytes;
    
    if (seconds < 60) {
      return '${seconds.toStringAsFixed(0)}s remaining';
    } else {
      final minutes = (seconds / 60).floor();
      final remSec = (seconds % 60).floor();
      return '${minutes}m ${remSec}s remaining';
    }
  }

  static String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const suffixes = ['B', 'KB', 'MB', 'GB', 'TB'];
    var i = 0;
    double dBytes = bytes.toDouble();
    while (dBytes >= 1024 && i < suffixes.length - 1) {
      dBytes /= 1024;
      i++;
    }
    return '${dBytes.toStringAsFixed(i == 0 ? 0 : 1)} ${suffixes[i]}';
  }

  DownloadItem copyWith({
    String? id,
    String? title,
    String? category,
    String? url,
    DownloadStatus? status,
    int? totalBytes,
    int? downloadedBytes,
    double? speedKbps,
    DateTime? timestamp,
    String? error,
  }) {
    return DownloadItem(
      id: id ?? this.id,
      title: title ?? this.title,
      category: category ?? this.category,
      url: url ?? this.url,
      status: status ?? this.status,
      totalBytes: totalBytes ?? this.totalBytes,
      downloadedBytes: downloadedBytes ?? this.downloadedBytes,
      speedKbps: speedKbps ?? this.speedKbps,
      timestamp: timestamp ?? this.timestamp,
      error: error ?? this.error,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'title': title,
      'category': category,
      'url': url,
      'status': status.name,
      'totalBytes': totalBytes,
      'downloadedBytes': downloadedBytes,
      'timestamp': timestamp.toIso8601String(),
      'error': error,
    };
  }

  factory DownloadItem.fromMap(Map<String, dynamic> map) {
    return DownloadItem(
      id: map['id'] as String,
      title: map['title'] as String,
      category: map['category'] as String,
      url: map['url'] as String,
      status: DownloadStatus.values.firstWhere(
        (e) => e.name == map['status'],
        orElse: () => DownloadStatus.pending,
      ),
      totalBytes: map['totalBytes'] as int? ?? 0,
      downloadedBytes: map['downloadedBytes'] as int? ?? 0,
      timestamp: DateTime.parse(map['timestamp'] as String),
      error: map['error'] as String?,
    );
  }
}
