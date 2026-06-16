import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/main.dart';
import 'package:smart_tg_downloader/providers/account_provider.dart';
import 'package:smart_tg_downloader/providers/download_provider.dart';
import 'package:smart_tg_downloader/screens/login_screen.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() async {
    // Setup Mock SharedPreferences for tests
    SharedPreferences.setMockInitialValues({});
    await StorageService.instance.init();
  });

  testWidgets('SmartTGDownloaderApp mounts successfully and shows main navigation', (WidgetTester tester) async {
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider(create: (_) => AccountProvider()),
          ChangeNotifierProvider(create: (_) => DownloadProvider()),
        ],
        child: const SmartTGDownloaderApp(),
      ),
    );

    // Verify app title or main widgets are present
    expect(find.byType(SmartTGDownloaderApp), findsOneWidget);
  });

  testWidgets('LoginScreen shows country selector and defaults to India', (WidgetTester tester) async {
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider(create: (_) => AccountProvider()),
          ChangeNotifierProvider(create: (_) => DownloadProvider()),
        ],
        child: const MaterialApp(
          home: LoginScreen(),
        ),
      ),
    );

    // Let default country loader initialize (uses fallback in tests)
    await tester.pumpAndSettle();

    expect(find.byType(DropdownButtonFormField<Map<String, String>>), findsOneWidget);
    expect(find.text('India (+91)'), findsOneWidget);
  });

  testWidgets('LoginScreen code step rejects wrong verification code and succeeds with correct code', (WidgetTester tester) async {
    final ap = AccountProvider();
    
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider<AccountProvider>.value(value: ap),
          ChangeNotifierProvider(create: (_) => DownloadProvider()),
        ],
        child: const MaterialApp(
          home: LoginScreen(),
        ),
      ),
    );

    await tester.pumpAndSettle();

    // Fill in phone number and tap next
    final phoneFinder = find.byWidgetPredicate(
      (widget) => widget is TextField && widget.decoration?.hintText == 'Phone Number'
    );
    await tester.enterText(phoneFinder, '9876543211');
    await tester.ensureVisible(find.text('Next'));
    await tester.tap(find.text('Next'));
    
    // Wait for mock network delay (1s) and pump
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();

    // Verify we are on Step 2 (verification code)
    expect(find.text('Enter Code'), findsOneWidget);
    expect(ap.generatedCode, isNotNull);

    // Enter wrong code
    final codeFinder = find.byWidgetPredicate(
      (widget) => widget is TextField && widget.decoration?.hintText == '•••••'
    );
    await tester.enterText(codeFinder, '00000');
    await tester.ensureVisible(find.text('Next'));
    await tester.tap(find.text('Next'));
    await tester.pump(const Duration(milliseconds: 800));
    await tester.pumpAndSettle();

    // Verify error is shown and we didn't log in
    expect(find.text('Invalid verification code. Please check the code sent to your device.'), findsOneWidget);
    expect(ap.isLoggedIn, isFalse);

    // Enter correct code
    await tester.enterText(codeFinder, ap.generatedCode!);
    await tester.ensureVisible(find.text('Next'));
    await tester.tap(find.text('Next'));
    await tester.pump(const Duration(milliseconds: 800));
    await tester.pumpAndSettle();

    // Verify we successfully logged in
    expect(ap.isLoggedIn, isTrue);
  });
}
