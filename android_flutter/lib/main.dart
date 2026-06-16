import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/account_provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';
import 'package:smart_tg_downloader/screens/welcome_screen.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';

void main() async {
  // Ensure Flutter framework binding is initialized
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize Storage Service (Shared Preferences and SQLite)
  await StorageService.instance.init();

  runApp(
    MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => AccountProvider()),
        ChangeNotifierProvider(create: (_) => DownloadProvider()),
      ],
      child: const SmartTGDownloaderApp(),
    ),
  );
}

class SmartTGDownloaderApp extends StatelessWidget {
  const SmartTGDownloaderApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Smart TG Downloader',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        primaryColor: const Color(0xFF6C63FF),
        scaffoldBackgroundColor: const Color(0xFF12141C),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF6C63FF),
          secondary: Color(0xFF8B80FF),
          surface: Color(0xFF1B1E2B),
          background: Color(0xFF12141C),
          error: Colors.redAccent,
        ),
        textTheme: const TextTheme(
          titleLarge: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
          bodyLarge: TextStyle(color: Colors.white),
          bodyMedium: TextStyle(color: Colors.white70),
        ),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF1B1E2B),
          elevation: 0,
          titleTextStyle: TextStyle(
            color: Colors.white,
            fontSize: 18,
            fontWeight: FontWeight.bold,
          ),
        ),
        navigationBarTheme: NavigationBarThemeData(
          backgroundColor: const Color(0xFF1B1E2B),
          indicatorColor: const Color(0xFF6C63FF).withOpacity(0.2),
        ),
      ),
      home: const WelcomeScreen(),
    );
  }
}
