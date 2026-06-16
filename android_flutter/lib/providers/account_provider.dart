import 'dart:io' show Platform;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:smart_tg_downloader/models/account.dart';
import 'package:smart_tg_downloader/services/storage_service.dart';
import 'package:smart_tg_downloader/services/telegram_service.dart';

enum LoginStep {
  phoneNumber,
  verificationCode,
  twoFactorPassword,
}

enum LoginStatus {
  idle,
  connecting,
  sendingCode,
  codeSent,
  verifyingCode,
  pwdRequired,
  verifyingPwd,
  success,
  failed,
}

class AccountProvider with ChangeNotifier {
  final StorageService _storage = StorageService.instance;

  List<Account> _accounts = [];
  Account? _activeAccount;
  bool _isLoading = false;

  // Login Wizard State
  LoginStep _currentStep = LoginStep.phoneNumber;
  LoginStatus _status = LoginStatus.idle;
  String _phoneNumber = '';
  String _verificationCode = '';
  String _sessionLabel = '';
  bool _requiresTwoFactor = false;
  String? _loginError;
  String _phoneCodeHash = '';

  // Embedded default API ID and Hash (no user configuration needed)
  static const int defaultApiId = 0;
  static const String defaultApiHash = 'REDACTED';

  // Pluggable authentication service
  late TelegramAuthService _authService;

  AccountProvider() {
    _authService = Platform.environment.containsKey('FLUTTER_TEST')
        ? MockTelegramAuthService()
        : RealTelegramAuthService();
    _loadFromStorage();
  }

  // Inject a custom service for tests
  void setAuthService(TelegramAuthService service) {
    _authService = service;
  }

  // Getters
  List<Account> get accounts => _accounts;
  Account? get activeAccount => _activeAccount;
  bool get isLoggedIn => _activeAccount?.isLoggedIn ?? false;
  bool get isLoading => _isLoading;
  
  LoginStep get currentStep => _currentStep;
  LoginStatus get status => _status;
  String get phoneNumber => _phoneNumber;
  String get verificationCode => _verificationCode;
  String get sessionLabel => _sessionLabel;
  bool get requiresTwoFactor => _requiresTwoFactor;
  String? get loginError => _loginError;
  
  String? get generatedCode => _authService.generatedCode;

  String get statusMessage {
    switch (_status) {
      case LoginStatus.connecting:
        return 'Connecting to Telegram...';
      case LoginStatus.sendingCode:
        return 'Sending code request...';
      case LoginStatus.codeSent:
        return 'Verification code sent!';
      case LoginStatus.verifyingCode:
        return 'Verifying code on server...';
      case LoginStatus.pwdRequired:
        return '2FA password required.';
      case LoginStatus.verifyingPwd:
        return 'Verifying 2FA password...';
      case LoginStatus.success:
        return 'Login successful!';
      case LoginStatus.failed:
        return 'Login failed.';
      case LoginStatus.idle:
        return '';
    }
  }

  void _loadFromStorage() {
    _accounts = _storage.loadAccounts();
    final activeId = _storage.getActiveAccountId();
    if (activeId != null) {
      _activeAccount = _accounts.firstWhere(
        (acc) => acc.id == activeId,
        orElse: () => _accounts.isNotEmpty ? _accounts.first : Account(id: '', phoneNumber: '', sessionName: '', isLoggedIn: false, apiId: 0, apiHash: ''),
      );
      if (_activeAccount?.id == '') {
        _activeAccount = null;
      }
    } else if (_accounts.isNotEmpty) {
      _activeAccount = _accounts.first;
    }
    notifyListeners();
  }

  // Switch Active Account
  Future<void> switchAccount(String id) async {
    final account = _accounts.firstWhere((acc) => acc.id == id);
    _activeAccount = account;
    await _storage.setActiveAccountId(id);
    
    // Announce to Screen Reader
    SemanticsService.announce(
      'Switched active account to ${account.sessionName}',
      TextDirection.ltr,
    );
    
    notifyListeners();
  }

  // Logout Active Account
  Future<void> logoutActiveAccount() async {
    if (_activeAccount == null) return;
    
    final name = _activeAccount!.sessionName;
    _accounts.removeWhere((acc) => acc.id == _activeAccount!.id);
    _activeAccount = _accounts.isNotEmpty ? _accounts.first : null;
    
    await _storage.saveAccounts(_accounts);
    await _storage.setActiveAccountId(_activeAccount?.id);
    
    SemanticsService.announce(
      'Logged out of $name. Active account is now ${_activeAccount?.sessionName ?? "none"}',
      TextDirection.ltr,
    );
    
    notifyListeners();
  }

  // Remove and Logout Specific Account
  Future<void> removeAccount(String id) async {
    final accountIndex = _accounts.indexWhere((acc) => acc.id == id);
    if (accountIndex == -1) return;
    
    final account = _accounts[accountIndex];
    final bool isActive = _activeAccount?.id == id;
    
    _accounts.removeAt(accountIndex);
    
    if (isActive) {
      _activeAccount = _accounts.isNotEmpty ? _accounts.first : null;
      await _storage.setActiveAccountId(_activeAccount?.id);
    }
    
    await _storage.saveAccounts(_accounts);
    
    SemanticsService.announce(
      'Logged out account ${account.sessionName}.',
      TextDirection.ltr,
    );
    
    notifyListeners();
  }

  // --- Login Wizard Steps ---

  void resetLoginFlow() {
    _currentStep = LoginStep.phoneNumber;
    _phoneNumber = '';
    _verificationCode = '';
    _sessionLabel = '';
    _requiresTwoFactor = false;
    _loginError = null;
    _isLoading = false;
    _status = LoginStatus.idle;
    _phoneCodeHash = '';
    notifyListeners();
  }

  // Step 1: Send Verification Code
  Future<bool> sendVerificationCode(String phone, String label) async {
    if (phone.isEmpty) {
      _loginError = 'Phone number is required';
      _status = LoginStatus.failed;
      notifyListeners();
      return false;
    }

    _isLoading = true;
    _loginError = null;
    _status = LoginStatus.connecting;
    notifyListeners();

    try {
      SemanticsService.announce('Connecting to Telegram...', TextDirection.ltr);
      await _authService.connect();

      _status = LoginStatus.sendingCode;
      notifyListeners();
      SemanticsService.announce('Sending code request...', TextDirection.ltr);

      _phoneCodeHash = await _authService.sendCode(phone);

      _phoneNumber = phone;
      _sessionLabel = label.isEmpty ? 'Account ${phone.substring(Math.max(0, phone.length - 4))}' : label;
      _currentStep = LoginStep.verificationCode;
      _status = LoginStatus.codeSent;
      _isLoading = false;
      
      SemanticsService.announce('Verification code sent to your Telegram app. Please check it and enter the 5-digit code.', TextDirection.ltr);
      notifyListeners();
      return true;
    } catch (e) {
      _isLoading = false;
      _status = LoginStatus.failed;
      final errorStr = e.toString();
      if (errorStr.contains('NO_INTERNET_CONNECTION')) {
        _loginError = 'No active internet connection detected. Please check your network and try again.';
      } else if (errorStr.contains('PHONE_NUMBER_INVALID')) {
        _loginError = 'The phone number is invalid. Please check the format and try again.';
      } else if (errorStr.contains('FLOOD_WAIT')) {
        _loginError = 'Rate limit exceeded. Please wait a few minutes before trying again.';
      } else if (errorStr.contains('CONNECTION_FAILED')) {
        _loginError = 'Failed to connect to Telegram servers. Please check your internet connection.';
      } else {
        _loginError = 'Failed to send code: ${errorStr.replaceAll('Exception: ', '')}';
      }
      notifyListeners();
      return false;
    }
  }

  // Step 2: Submit Verification Code
  Future<bool> submitVerificationCode(String code) async {
    if (code.length < 5) {
      _loginError = 'Code must be at least 5 digits';
      notifyListeners();
      return false;
    }

    _isLoading = true;
    _loginError = null;
    _status = LoginStatus.verifyingCode;
    notifyListeners();
    SemanticsService.announce('Verifying code on server...', TextDirection.ltr);

    try {
      final success = await _authService.signIn(_phoneNumber, _phoneCodeHash, code);
      if (success) {
        _verificationCode = code;
        return await _completeLogin();
      }
      _isLoading = false;
      _status = LoginStatus.failed;
      _loginError = 'Failed to sign in. Please try again.';
      notifyListeners();
      return false;
    } catch (e) {
      _isLoading = false;
      final errorStr = e.toString();
      if (errorStr.contains('SESSION_PASSWORD_NEEDED')) {
        _verificationCode = code;
        _currentStep = LoginStep.twoFactorPassword;
        _requiresTwoFactor = true;
        _status = LoginStatus.pwdRequired;
        SemanticsService.announce('Two factor authorization password required', TextDirection.ltr);
        notifyListeners();
        return true;
      } else if (errorStr.contains('NO_INTERNET_CONNECTION')) {
        _status = LoginStatus.failed;
        _loginError = 'No active internet connection detected. Please check your network and try again.';
        notifyListeners();
        return false;
      } else if (errorStr.contains('CONNECTION_FAILED')) {
        _status = LoginStatus.failed;
        _loginError = 'Failed to connect to Telegram servers. Please check your internet connection.';
        notifyListeners();
        return false;
      } else if (errorStr.contains('PHONE_CODE_INVALID')) {
        _status = LoginStatus.failed;
        _loginError = 'Invalid verification code. Please check the code sent to your device.';
        notifyListeners();
        return false;
      } else if (errorStr.contains('PHONE_CODE_EXPIRED')) {
        _status = LoginStatus.failed;
        _loginError = 'Verification code has expired. Please request a new code.';
        notifyListeners();
        return false;
      } else {
        _status = LoginStatus.failed;
        _loginError = 'Error: ${errorStr.replaceAll('Exception: ', '')}';
        notifyListeners();
        return false;
      }
    }
  }

  // Step 3: Submit 2FA Password
  Future<bool> submitPassword(String password) async {
    if (password.isEmpty) {
      _loginError = 'Password is required';
      notifyListeners();
      return false;
    }

    _isLoading = true;
    _loginError = null;
    _status = LoginStatus.verifyingPwd;
    notifyListeners();
    SemanticsService.announce('Verifying 2FA password...', TextDirection.ltr);

    try {
      final success = await _authService.checkPassword(password);
      if (success) {
        return await _completeLogin();
      }
      _isLoading = false;
      _status = LoginStatus.failed;
      _loginError = 'Failed to verify password. Please try again.';
      notifyListeners();
      return false;
    } catch (e) {
      _isLoading = false;
      _status = LoginStatus.failed;
      final errorStr = e.toString();
      if (errorStr.contains('PASSWORD_HASH_INVALID')) {
        _loginError = 'Invalid Telegram 2FA password. Please try again.';
      } else if (errorStr.contains('NO_INTERNET_CONNECTION')) {
        _loginError = 'No active internet connection detected. Please check your network and try again.';
      } else if (errorStr.contains('CONNECTION_FAILED')) {
        _loginError = 'Failed to connect to Telegram servers. Please check your internet connection.';
      } else {
        _loginError = 'Error: ${errorStr.replaceAll('Exception: ', '')}';
      }
      notifyListeners();
      return false;
    }
  }

  Future<bool> _completeLogin() async {
    try {
      final String accId = DateTime.now().millisecondsSinceEpoch.toString();
      final newAccount = Account(
        id: accId,
        phoneNumber: _phoneNumber,
        sessionName: _sessionLabel,
        isLoggedIn: true,
        apiId: defaultApiId,
        apiHash: defaultApiHash,
      );

      // Save to memory
      _accounts.add(newAccount);
      _activeAccount = newAccount;

      // Save to Storage
      await _storage.saveAccounts(_accounts);
      await _storage.setActiveAccountId(accId);

      _status = LoginStatus.success;
      _isLoading = false;
      SemanticsService.announce('Login successful for $_sessionLabel', TextDirection.ltr);
      notifyListeners();
      return true;
    } catch (e) {
      _status = LoginStatus.failed;
      _loginError = 'Failed to create session: $e';
      _isLoading = false;
      notifyListeners();
      return false;
    }
  }

  void prevStep() {
    if (_currentStep == LoginStep.verificationCode) {
      _currentStep = LoginStep.phoneNumber;
      _loginError = null;
      _status = LoginStatus.idle;
    } else if (_currentStep == LoginStep.twoFactorPassword) {
      _currentStep = LoginStep.verificationCode;
      _loginError = null;
      _status = LoginStatus.codeSent;
    }
    notifyListeners();
  }
}

class Math {
  static int max(int a, int b) => a > b ? a : b;
}
