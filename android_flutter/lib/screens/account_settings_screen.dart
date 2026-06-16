import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/account_provider.dart';
import 'package:smart_tg_downloader/screens/login_screen.dart';

class AccountSettingsScreen extends StatelessWidget {
  const AccountSettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final ap = Provider.of<AccountProvider>(context);
    final activeAccount = ap.activeAccount;
    final otherAccounts = ap.accounts.where((acc) => acc.id != activeAccount?.id).toList();

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
          'Account Settings',
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
              // Active Account Section
              const Text(
                'Active Account',
                style: TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 12),
              if (activeAccount != null)
                Card(
                  color: const Color(0xFF1B1E2B),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                  elevation: 0,
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Row(
                      children: [
                        CircleAvatar(
                          radius: 24,
                          backgroundColor: const Color(0xFF6C63FF),
                          child: Text(
                            activeAccount.sessionName.isNotEmpty
                                ? activeAccount.sessionName[0].toUpperCase()
                                : '?',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 18,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                activeAccount.sessionName,
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 15,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                activeAccount.phoneNumber,
                                style: TextStyle(
                                  color: Colors.grey[400],
                                  fontSize: 12,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Semantics(
                          label: 'Log out active account button',
                          hint: 'Double tap to sign out and remove this account',
                          button: true,
                          child: IconButton(
                            icon: const Icon(Icons.logout_rounded, color: Colors.redAccent),
                            tooltip: 'Log Out',
                            onPressed: () {
                              _showLogoutConfirmDialog(context, ap);
                            },
                          ),
                        ),
                      ],
                    ),
                  ),
                )
              else
                Card(
                  color: const Color(0xFF1B1E2B),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
                  child: const Padding(
                    padding: EdgeInsets.all(20.0),
                    child: Center(
                      child: Text(
                        'No active account linked.',
                        style: TextStyle(color: Colors.white60),
                      ),
                    ),
                  ),
                ),
              const SizedBox(height: 32),

              // Other Accounts Section
              if (otherAccounts.isNotEmpty) ...[
                const Text(
                  'Other Linked Accounts',
                  style: TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 12),
                ListView.separated(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  itemCount: otherAccounts.length,
                  separatorBuilder: (context, index) => const SizedBox(height: 10),
                  itemBuilder: (context, index) {
                    final acc = otherAccounts[index];
                    return Semantics(
                      label: 'Switch to account ${acc.sessionName}',
                      hint: 'Double tap to activate this account',
                      button: true,
                      child: Card(
                        color: const Color(0xFF1B1E2B).withOpacity(0.6),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                        elevation: 0,
                        child: ListTile(
                          onTap: () {
                            ap.switchAccount(acc.id);
                          },
                          leading: CircleAvatar(
                            radius: 18,
                            backgroundColor: Colors.white24,
                            child: Text(
                              acc.sessionName.isNotEmpty ? acc.sessionName[0].toUpperCase() : '?',
                              style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold),
                            ),
                          ),
                          title: Text(
                            acc.sessionName,
                            style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600),
                          ),
                          subtitle: Text(
                            acc.phoneNumber,
                            style: TextStyle(color: Colors.grey[500], fontSize: 11),
                          ),
                          trailing: Semantics(
                            label: 'Remove account ${acc.sessionName}',
                            hint: 'Double tap to unlink and log out this account',
                            button: true,
                            child: IconButton(
                              icon: const Icon(Icons.delete_outline_rounded, color: Colors.white30, size: 20),
                              tooltip: 'Remove Account',
                              onPressed: () {
                                ap.removeAccount(acc.id);
                              },
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
                const SizedBox(height: 32),
              ],

              // Actions Footer
              Semantics(
                label: 'Add another Telegram account button',
                hint: 'Double tap to link a new phone number',
                button: true,
                child: SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(
                      side: const BorderSide(color: Color(0xFF8B80FF), width: 1.5),
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                    ),
                    onPressed: () {
                      ap.resetLoginFlow();
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (context) => LoginScreen(
                            onLoginSuccess: () {
                              Navigator.pop(context); // Go back to account settings
                            },
                          ),
                        ),
                      );
                    },
                    icon: const Icon(Icons.add_rounded, color: Color(0xFF8B80FF)),
                    label: const Text(
                      'Add Telegram Account',
                      style: TextStyle(color: Color(0xFF8B80FF), fontWeight: FontWeight.bold, fontSize: 14),
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

  void _showLogoutConfirmDialog(BuildContext context, AccountProvider ap) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: const Color(0xFF1B1E2B),
        title: const Text('Log Out', style: TextStyle(color: Colors.white)),
        content: const Text(
          'Are you sure you want to log out of the active Telegram account? You will need to sign in again to download files.',
          style: TextStyle(color: Colors.white70),
        ),
        actions: [
          Semantics(
            label: 'Cancel logout button',
            button: true,
            child: TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel', style: TextStyle(color: Colors.white54)),
            ),
          ),
          Semantics(
            label: 'Confirm logout button',
            button: true,
            child: ElevatedButton(
              style: ElevatedButton.styleFrom(backgroundColor: Colors.redAccent),
              onPressed: () {
                ap.logoutActiveAccount();
                Navigator.pop(context);
                Navigator.pop(context); // Also pop the settings sub-screen back to main shell (which redirects to welcome screen)
              },
              child: const Text('Log Out', style: TextStyle(color: Colors.white)),
            ),
          ),
        ],
      ),
    );
  }
}
