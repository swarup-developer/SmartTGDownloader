import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/account_provider.dart';
import 'package:smart_tg_downloader/screens/login_screen.dart';

class AccountSwitcherSheet extends StatelessWidget {
  const AccountSwitcherSheet({super.key});

  @override
  Widget build(BuildContext context) {
    final ap = Provider.of<AccountProvider>(context);
    final activeAccount = ap.activeAccount;

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
          
          const Text(
            'Switch Account',
            style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 16),
          
          // Accounts List
          ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.of(context).size.height * 0.3,
            ),
            child: ap.accounts.isEmpty
                ? Padding(
                    padding: const EdgeInsets.symmetric(vertical: 24.0),
                    child: Center(
                      child: Text(
                        'No accounts logged in yet',
                        style: TextStyle(color: Colors.grey[500], fontSize: 13),
                      ),
                    ),
                  )
                : ListView.builder(
                    shrinkWrap: true,
                    itemCount: ap.accounts.length,
                    itemBuilder: (context, index) {
                      final acc = ap.accounts[index];
                      final isActive = activeAccount?.id == acc.id;

                      // Accessibility message
                      final semanticText = 
                          'Account profile: ${acc.sessionName}, number: ${acc.phoneNumber}. ${isActive ? "Currently selected" : "Double-tap to select"}';

                      return MergeSemantics(
                        child: Semantics(
                          label: semanticText,
                          selected: isActive,
                          child: ListTile(
                            leading: CircleAvatar(
                              backgroundColor: isActive ? const Color(0xFF6C63FF) : Colors.grey[800],
                              child: Text(
                                acc.sessionName.isNotEmpty ? acc.sessionName[0].toUpperCase() : '?',
                                style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
                              ),
                            ),
                            title: Text(
                              acc.sessionName,
                              style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w500),
                            ),
                            subtitle: Text(
                              acc.phoneNumber,
                              style: TextStyle(color: Colors.grey[400], fontSize: 12),
                            ),
                            trailing: isActive
                                ? const Icon(Icons.check_circle, color: Color(0xFF4CAF50))
                                : null,
                            onTap: () {
                              ap.switchAccount(acc.id);
                              Navigator.pop(context);
                            },
                          ),
                        ),
                      );
                    },
                  ),
          ),
          const Divider(color: Colors.white12, height: 16),
          
          // Add Account Button
          MergeSemantics(
            child: Semantics(
              label: 'Add new Telegram account',
              hint: 'Double tap to open Telegram login wizard',
              child: ListTile(
                leading: const CircleAvatar(
                  backgroundColor: Color(0xFF12141C),
                  child: Icon(Icons.add, color: Color(0xFF8B80FF)),
                ),
                title: const Text(
                  'Add Telegram Account',
                  style: TextStyle(color: Color(0xFF8B80FF), fontSize: 14, fontWeight: FontWeight.bold),
                ),
                onTap: () {
                  Navigator.pop(context);
                  ap.resetLoginFlow();
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (context) => LoginScreen(
                        onLoginSuccess: () {
                          // Login Screen handles pop or state success
                        },
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}
