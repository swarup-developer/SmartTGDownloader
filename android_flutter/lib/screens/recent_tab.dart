import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/models/download_item.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';

class RecentTab extends StatefulWidget {
  const RecentTab({super.key});

  @override
  State<RecentTab> createState() => _RecentTabState();
}

class _RecentTabState extends State<RecentTab> {
  final TextEditingController _searchController = TextEditingController();
  String _searchQuery = '';
  String _statusFilter = 'All'; // 'All', 'Completed', 'Failed', 'Downloading'

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final dp = Provider.of<DownloadProvider>(context);

    // Apply filters to history list
    final filteredHistory = dp.history.where((item) {
      final matchesSearch = item.title.toLowerCase().contains(_searchQuery.toLowerCase());
      
      bool matchesStatus = true;
      if (_statusFilter == 'Completed') {
        matchesStatus = item.status == DownloadStatus.completed;
      } else if (_statusFilter == 'Failed') {
        matchesStatus = item.status == DownloadStatus.failed;
      } else if (_statusFilter == 'Downloading') {
        matchesStatus = item.status == DownloadStatus.downloading || 
            item.status == DownloadStatus.pending || 
            item.status == DownloadStatus.paused;
      }
      
      return matchesSearch && matchesStatus;
    }).toList();

    return Scaffold(
      backgroundColor: const Color(0xFF12141C),
      body: Column(
        children: [
          // Search and Filters Bar
          _buildSearchAndFiltersHeader(context, dp),
          
          // List of Downloads
          Expanded(
            child: filteredHistory.isEmpty
                ? _buildEmptyState()
                : ListView.builder(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    itemCount: filteredHistory.length,
                    itemBuilder: (context, index) {
                      final item = filteredHistory[index];
                      return _buildDownloadHistoryCard(context, dp, item);
                    },
                  ),
          ),
        ],
      ),
    );
  }

  // SEARCH AND CHIPS FILTER HEADER
  Widget _buildSearchAndFiltersHeader(BuildContext context, DownloadProvider dp) {
    return Container(
      color: const Color(0xFF1B1E2B),
      padding: const EdgeInsets.only(left: 20, right: 16, top: 16, bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Title & Action Buttons Row
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text(
                'Recent Downloads',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 20,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 0.3,
                ),
              ),
              Row(
                children: [
                  Semantics(
                    label: 'Clear history list button',
                    hint: 'Removes completed and failed downloads from history list',
                    child: IconButton(
                      icon: const Icon(Icons.delete_sweep_outlined, color: Colors.redAccent, size: 22),
                      tooltip: 'Clear History',
                      onPressed: () {
                        _showClearHistoryDialog(context, dp);
                      },
                    ),
                  ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 12),
          
          // Search box
          Semantics(
            label: 'Search download history',
            hint: 'Type a filename here to search',
            child: TextField(
              controller: _searchController,
              onChanged: (val) {
                setState(() {
                  _searchQuery = val;
                });
              },
              style: const TextStyle(color: Colors.white, fontSize: 13),
              decoration: InputDecoration(
                hintText: 'Search downloads...',
                hintStyle: TextStyle(color: Colors.grey[600], fontSize: 13),
                prefixIcon: const Icon(Icons.search_rounded, color: Colors.white54, size: 18),
                suffixIcon: _searchQuery.isNotEmpty
                    ? Semantics(
                        label: 'Clear search query button',
                        button: true,
                        child: IconButton(
                          icon: const Icon(Icons.clear_rounded, color: Colors.white54, size: 18),
                          tooltip: 'Clear search',
                          onPressed: () {
                            setState(() {
                              _searchController.clear();
                              _searchQuery = '';
                            });
                          },
                        ),
                      )
                    : null,
                contentPadding: const EdgeInsets.symmetric(vertical: 14),
                filled: true,
                fillColor: const Color(0xFF12141C),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10),
                  borderSide: BorderSide(color: Colors.white.withOpacity(0.04)),
                ),
                enabledBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10),
                  borderSide: BorderSide(color: Colors.white.withOpacity(0.04)),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10),
                  borderSide: const BorderSide(color: Color(0xFF6C63FF), width: 1.2),
                ),
              ),
            ),
          ),
          const SizedBox(height: 14),
          
          // Status Filters Row
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            child: Row(
              children: ['All', 'Completed', 'Failed', 'Downloading'].map((status) {
                final isSelected = _statusFilter == status;
                return Padding(
                  padding: const EdgeInsets.only(right: 8.0),
                  child: Semantics(
                    label: '$status filter chip',
                    selected: isSelected,
                    child: ChoiceChip(
                      selectedColor: const Color(0xFF6C63FF).withOpacity(0.15),
                      backgroundColor: const Color(0xFF12141C),
                      checkmarkColor: const Color(0xFF8B80FF),
                      side: BorderSide(
                        color: isSelected ? const Color(0xFF8B80FF).withOpacity(0.5) : Colors.white.withOpacity(0.06),
                        width: 1,
                      ),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
                      label: Text(
                        status,
                        style: TextStyle(
                          color: isSelected ? const Color(0xFF8B80FF) : Colors.white60,
                          fontSize: 11,
                          fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                        ),
                      ),
                      selected: isSelected,
                      onSelected: (val) {
                        if (val) {
                          setState(() {
                            _statusFilter = status;
                          });
                        }
                      },
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }

  // EMPTY STATE STATE
  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.history_toggle_off, color: Colors.grey[700], size: 60),
          const SizedBox(height: 16),
          Text(
            'No matching downloads',
            style: TextStyle(color: Colors.grey[400], fontSize: 14, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 4),
          Text(
            'Active or completed items will show up here.',
            style: TextStyle(color: Colors.grey[600], fontSize: 12),
          ),
        ],
      ),
    );
  }

  // CONSOLIDATED SEMANTICS CARD FOR DOWNLOAD ROW
  Widget _buildDownloadHistoryCard(BuildContext context, DownloadProvider dp, DownloadItem item) {
    // Generate description string for Screen Readers
    final statusStr = item.status == DownloadStatus.completed
        ? 'completed'
        : item.status == DownloadStatus.failed
            ? 'failed. error is ${item.error ?? "unknown"}'
            : item.status == DownloadStatus.paused
                ? 'paused'
                : 'downloading';
    
    final accessibilityMessage = 
        'Download: ${item.title}, category: ${item.category}, size: ${item.formattedSize}, status: $statusStr.';

    return Card(
      color: const Color(0xFF1B1E2B),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: Colors.white.withOpacity(0.04), width: 1),
      ),
      margin: const EdgeInsets.only(bottom: 10),
      elevation: 0,
      child: Semantics(
        label: accessibilityMessage,
        hint: 'Double tap to toggle actions panel',
        child: ExpansionTile(
          backgroundColor: Colors.transparent,
          collapsedBackgroundColor: Colors.transparent,
          iconColor: const Color(0xFF8B80FF),
          collapsedIconColor: Colors.white54,
          shape: const Border(), // Removes top/bottom line divider when expanded
          collapsedShape: const Border(),
          leading: ExcludeSemantics(
            child: _buildStatusIcon(item.status),
          ),
          title: Text(
            item.title,
            style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
          subtitle: Text(
            '${item.category} • ${item.formattedSize}',
            style: TextStyle(color: Colors.grey[400], fontSize: 11),
          ),
          children: [
            // Expandable actions panel
            Padding(
              padding: const EdgeInsets.only(left: 16.0, right: 16.0, bottom: 12.0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  // Status messages or errors
                  Expanded(
                    child: Text(
                      item.status == DownloadStatus.failed
                          ? 'Error: ${item.error ?? "Transfer aborted"}'
                          : item.status == DownloadStatus.completed
                              ? 'Sorted: Downloads/SmartTG/${item.category}'
                              : 'Progress: ${(item.progress * 100).toStringAsFixed(0)}%',
                      style: TextStyle(
                        color: item.status == DownloadStatus.failed ? Colors.redAccent : Colors.white54,
                        fontSize: 11,
                      ),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 8),
      
                  // Action buttons
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      // Retry for failed downloads
                      if (item.status == DownloadStatus.failed)
                        Semantics(
                          label: 'Retry download',
                          child: IconButton(
                            icon: const Icon(Icons.refresh_rounded, color: Colors.orangeAccent, size: 20),
                            onPressed: () => dp.retryDownload(item),
                          ),
                        ),
                      
                      // Open completed files
                      if (item.status == DownloadStatus.completed) ...[
                        Semantics(
                          label: 'Open file',
                          child: IconButton(
                            icon: const Icon(Icons.open_in_new_rounded, color: Color(0xFF8B80FF), size: 20),
                            onPressed: () {
                              _simulateOpenFile(context, item);
                            },
                          ),
                        ),
                        Semantics(
                          label: 'Share file',
                          child: IconButton(
                            icon: const Icon(Icons.share_rounded, color: Color(0xFF8B80FF), size: 20),
                            onPressed: () {
                              _simulateShareFile(context, item);
                            },
                          ),
                        ),
                      ],
                      
                      // Cancel/Delete
                      Semantics(
                        label: 'Delete file from history',
                        child: IconButton(
                          icon: const Icon(Icons.delete_rounded, color: Colors.redAccent, size: 20),
                          onPressed: () => dp.cancelDownload(item.id),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            )
          ],
        ),
      ),
    );
  }

  Widget _buildStatusIcon(DownloadStatus status) {
    switch (status) {
      case DownloadStatus.completed:
        return Container(
          width: 28,
          height: 28,
          decoration: BoxDecoration(
            color: const Color(0xFF4CAF50).withOpacity(0.15),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.check_rounded, color: Color(0xFF4CAF50), size: 16),
        );
      case DownloadStatus.failed:
        return Container(
          width: 28,
          height: 28,
          decoration: BoxDecoration(
            color: const Color(0xFFE53935).withOpacity(0.15),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.priority_high_rounded, color: Colors.redAccent, size: 16),
        );
      case DownloadStatus.paused:
        return Container(
          width: 28,
          height: 28,
          decoration: BoxDecoration(
            color: const Color(0xFFFF9800).withOpacity(0.15),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.pause_rounded, color: Colors.orangeAccent, size: 16),
        );
      case DownloadStatus.downloading:
        return const SizedBox(
          width: 24,
          height: 24,
          child: CircularProgressIndicator(strokeWidth: 2.5, color: Color(0xFF8B80FF)),
        );
      case DownloadStatus.pending:
        return Container(
          width: 28,
          height: 28,
          decoration: BoxDecoration(
            color: Colors.white.withOpacity(0.08),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.schedule_rounded, color: Colors.white30, size: 16),
        );
    }
  }

  // SIMULATE ACTIONS
  void _simulateOpenFile(BuildContext context, DownloadItem item) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Opening ${item.title} with default player...'),
        backgroundColor: const Color(0xFF6C63FF),
        duration: const Duration(seconds: 1),
      ),
    );
  }

  void _simulateShareFile(BuildContext context, DownloadItem item) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Opening Android system share sheet for ${item.title}...'),
        backgroundColor: const Color(0xFF6C63FF),
        duration: const Duration(seconds: 1),
      ),
    );
  }

  void _showClearHistoryDialog(BuildContext context, DownloadProvider dp) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF1B1E2B),
        title: const Text('Clear History', style: TextStyle(color: Colors.white)),
        content: const Text(
          'Are you sure you want to clear completed and failed downloads from history? Active downloads will not be affected.',
          style: TextStyle(color: Colors.white70),
        ),
        actions: [
          Semantics(
            label: 'Cancel clear history button',
            button: true,
            child: TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel', style: TextStyle(color: Colors.white54)),
            ),
          ),
          Semantics(
            label: 'Confirm clear history button',
            button: true,
            child: ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: Colors.redAccent),
              onPressed: () {
                dp.clearHistory();
                Navigator.pop(context);
              },
              child: const Text('Clear', style: TextStyle(color: Colors.white)),
            ),
          ),
        ],
      ),
    );
  }
}
