import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';

class ScanResultsSheet extends StatefulWidget {
  final Map<String, dynamic> scanSummary;
  const ScanResultsSheet({super.key, required this.scanSummary});

  @override
  State<ScanResultsSheet> createState() => _ScanResultsSheetState();
}

class _ScanResultsSheetState extends State<ScanResultsSheet> {
  final List<String> _checkedKeys = [];
  String _selectedCategory = 'All';

  @override
  void initState() {
    super.initState();
    // Default check all items
    final List<dynamic> items = widget.scanSummary['items'] ?? [];
    for (final item in items) {
      _checkedKeys.add(item['key'] as String);
    }
  }

  void _selectCategory(String cat) {
    setState(() {
      _selectedCategory = cat;
    });
    SemanticsService.announce('Filtering files by category $cat', TextDirection.ltr);
  }

  void _selectAll() {
    setState(() {
      final List<dynamic> allItems = widget.scanSummary['items'] ?? [];
      final List<dynamic> items = _selectedCategory == 'All'
          ? allItems
          : allItems.where((item) => item['category'] == _selectedCategory).toList();
      for (final item in items) {
        final key = item['key'] as String;
        if (!_checkedKeys.contains(key)) {
          _checkedKeys.add(key);
        }
      }
    });
    SemanticsService.announce('Checked all filtered files', TextDirection.ltr);
  }

  void _clearAll() {
    setState(() {
      final List<dynamic> allItems = widget.scanSummary['items'] ?? [];
      final List<dynamic> items = _selectedCategory == 'All'
          ? allItems
          : allItems.where((item) => item['category'] == _selectedCategory).toList();
      for (final item in items) {
        _checkedKeys.remove(item['key'] as String);
      }
    });
    SemanticsService.announce('Unchecked all filtered files', TextDirection.ltr);
  }

  void _toggleItem(String key, String title) {
    setState(() {
      if (_checkedKeys.contains(key)) {
        _checkedKeys.remove(key);
        SemanticsService.announce('Unchecked $title', TextDirection.ltr);
      } else {
        _checkedKeys.add(key);
        SemanticsService.announce('Checked $title', TextDirection.ltr);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final dp = Provider.of<DownloadProvider>(context, listen: false);

    final title = widget.scanSummary['title'] ?? 'Unknown Source';
    final count = widget.scanSummary['count'] ?? 0;
    final totalSize = widget.scanSummary['total_size'] ?? 0;
    final estSeconds = widget.scanSummary['estimated_seconds'] ?? 0;
    final counts = widget.scanSummary['counts'] as Map<String, int>? ?? {};
    final List<dynamic> allItems = widget.scanSummary['items'] ?? [];
    final List<dynamic> items = _selectedCategory == 'All'
        ? allItems
        : allItems.where((item) => item['category'] == _selectedCategory).toList();

    final formattedSize = _formatBytes(totalSize);
    final formattedDuration = _formatDuration(estSeconds);

    return Container(
      decoration: const BoxDecoration(
        color: Color(0xFF1B1E2B),
        borderRadius: BorderRadius.only(
          topLeft: Radius.circular(24),
          topRight: Radius.circular(24),
        ),
      ),
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 12,
        bottom: MediaQuery.of(context).viewInsets.bottom + 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Drag handle
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.white24,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: 16),
          
          // Header info
          Text(
            'Source: $title',
            style: const TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          
          // Summary text
          Text(
            'Total files: $count  •  Size: $formattedSize  •  ETA: $formattedDuration\n'
            'Audio: ${counts['Audio'] ?? 0} | Video: ${counts['Video'] ?? 0} | Images: ${counts['Images'] ?? 0} | Docs: ${counts['Docs'] ?? 0}',
            style: TextStyle(color: Colors.grey[400], fontSize: 12, height: 1.4),
          ),
          const SizedBox(height: 16),

          // Category Filter Chips
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: ['All', 'Audio', 'Video', 'Images', 'Docs'].map((cat) {
                final isSelected = _selectedCategory == cat;
                return Padding(
                  padding: const EdgeInsets.only(right: 8.0),
                  child: Semantics(
                    label: '$cat category filter in results',
                    selected: isSelected,
                    child: FilterChip(
                      selectedColor: const Color(0xFF6C63FF).withOpacity(0.2),
                      checkmarkColor: const Color(0xFF8B80FF),
                      label: Text(
                        cat,
                        style: TextStyle(
                          color: isSelected ? const Color(0xFF8B80FF) : Colors.white60,
                          fontSize: 12,
                        ),
                      ),
                      selected: isSelected,
                      backgroundColor: const Color(0xFF12141C),
                      onSelected: (_) => _selectCategory(cat),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
          const SizedBox(height: 12),
          
          // Selection shortcuts row
          Row(
            children: [
              TextButton.icon(
                icon: const Icon(Icons.select_all, size: 16, color: Color(0xFF8B80FF)),
                label: const Text('Select All', style: TextStyle(color: Color(0xFF8B80FF), fontSize: 12)),
                onPressed: _selectAll,
              ),
              const SizedBox(width: 12),
              TextButton.icon(
                icon: const Icon(Icons.deselect, size: 16, color: Colors.white60),
                label: const Text('Clear All', style: TextStyle(color: Colors.white60, fontSize: 12)),
                onPressed: _clearAll,
              ),
            ],
          ),
          const Divider(color: Colors.white12, height: 12),
          
          // List of items
          ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.of(context).size.height * 0.4,
            ),
            child: ListView.builder(
              shrinkWrap: true,
              itemCount: items.length,
              itemBuilder: (context, index) {
                final item = items[index];
                final key = item['key'] as String;
                final fileTitle = item['title'] as String;
                final category = item['category'] as String;
                final size = item['size_bytes'] as int;
                final isChecked = _checkedKeys.contains(key);

                final sizeStr = _formatBytes(size);
                
                // Screen reader semantic message
                final accessibilityMsg = 
                    'Selectable file: $fileTitle, category: $category, size: $sizeStr. ${isChecked ? "Checked" : "Unchecked"}';

                return MergeSemantics(
                  child: Semantics(
                    label: accessibilityMsg,
                    selected: isChecked,
                    hint: 'Double tap to toggle selection',
                    child: CheckboxListTile(
                      activeColor: const Color(0xFF6C63FF),
                      checkColor: Colors.white,
                      title: Text(
                        fileTitle,
                        style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w500),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        '$category • $sizeStr',
                        style: TextStyle(color: Colors.grey[400], fontSize: 11),
                      ),
                      value: isChecked,
                      onChanged: (_) => _toggleItem(key, fileTitle),
                      controlAffinity: ListTileControlAffinity.leading,
                      contentPadding: EdgeInsets.zero,
                    ),
                  ),
                );
              },
            ),
          ),
          const Divider(color: Colors.white12, height: 24),
          
          // Actions
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel', style: TextStyle(color: Colors.white60)),
              ),
              const SizedBox(width: 16),
              ElevatedButton(
                style: ElevatedButton.styleFrom(
                  backgroundColor: _checkedKeys.isNotEmpty ? const Color(0xFF6C63FF) : Colors.grey[850],
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                ),
                onPressed: _checkedKeys.isNotEmpty
                    ? () {
                        dp.startDownloads(widget.scanSummary, _checkedKeys);
                        Navigator.pop(context);
                      }
                    : null,
                child: Text(
                  'Download Selected (${_checkedKeys.length})',
                  style: TextStyle(
                    color: _checkedKeys.isNotEmpty ? Colors.white : Colors.white30,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
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

  static String _formatDuration(int seconds) {
    if (seconds < 60) return '${seconds}s';
    final minutes = seconds ~/ 60;
    final remSec = seconds % 60;
    return '${minutes}m ${remSec}s';
  }
}
