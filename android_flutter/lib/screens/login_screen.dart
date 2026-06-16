import 'dart:convert';
import 'package:flutter/material.dart';

import 'package:provider/provider.dart';
import 'package:smart_tg_downloader/providers/account_provider.dart';

class LoginScreen extends StatefulWidget {
  final VoidCallback? onLoginSuccess;
  const LoginScreen({super.key, this.onLoginSuccess});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final PageController _pageController = PageController();
  
  // Controllers
  final TextEditingController _countryCodeController = TextEditingController(text: '+91');
  final TextEditingController _phoneController = TextEditingController();
  final TextEditingController _labelController = TextEditingController();
  final TextEditingController _codeController = TextEditingController();
  final TextEditingController _passwordController = TextEditingController();

  bool _isPasswordVisible = false;

  List<Map<String, String>> _countries = [];
  Map<String, String>? _selectedCountry;

  @override
  void initState() {
    super.initState();
    _loadCountries();
    _countryCodeController.addListener(_onCountryCodeChanged);
  }

  @override
  void dispose() {
    _countryCodeController.removeListener(_onCountryCodeChanged);
    _pageController.dispose();
    _countryCodeController.dispose();
    _phoneController.dispose();
    _labelController.dispose();
    _codeController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _loadCountries() async {
    try {
      final jsonString = await DefaultAssetBundle.of(context).loadString('assets/CountryCodes.json');
      final List<dynamic> jsonList = json.decode(jsonString);
      final list = jsonList.map((item) {
        return {
          'name': (item['name'] ?? '').toString(),
          'dial_code': (item['dial_code'] ?? '').toString(),
          'code': (item['code'] ?? '').toString(),
        };
      }).toList();
      
      setState(() {
        _countries = list;
        _setDefaultCountry();
      });
    } catch (e) {
      debugPrint('Failed to load country codes asset, using fallback: $e');
      setState(() {
        _countries = [
          {'name': 'India', 'dial_code': '+91', 'code': 'IN'},
          {'name': 'United States', 'dial_code': '+1', 'code': 'US'},
          {'name': 'United Kingdom', 'dial_code': '+44', 'code': 'GB'},
        ];
        _setDefaultCountry();
      });
    }
  }

  void _setDefaultCountry() {
    final india = _countries.firstWhere(
      (c) => c['code'] == 'IN',
      orElse: () => _countries.isNotEmpty ? _countries.first : {},
    );
    if (india.isNotEmpty) {
      setState(() {
        _selectedCountry = india;
        _countryCodeController.text = india['dial_code'] ?? '+91';
      });
    }
  }

  void _onCountryCodeChanged() {
    final code = _countryCodeController.text.trim();
    if (_selectedCountry != null && _selectedCountry!['dial_code'] == code) {
      return;
    }
    final match = _countries.firstWhere(
      (c) => c['dial_code'] == code,
      orElse: () => {},
    );
    if (match.isNotEmpty && match != _selectedCountry) {
      setState(() {
        _selectedCountry = match;
      });
    }
  }



  @override
  Widget build(BuildContext context) {
    final ap = Provider.of<AccountProvider>(context);

    // Sync UI Page with state transitions
    WidgetsBinding.instance.addPostFrameCallback((_) {
      int targetPageIndex = 0;
      if (ap.currentStep == LoginStep.phoneNumber) {
        targetPageIndex = 0;
      } else if (ap.currentStep == LoginStep.verificationCode) {
        targetPageIndex = 1;
      } else if (ap.currentStep == LoginStep.twoFactorPassword) {
        targetPageIndex = 2;
      }
      if (_pageController.hasClients && _pageController.page?.round() != targetPageIndex) {
        if (mounted) {
          ScaffoldMessenger.of(context).clearSnackBars();
        }
        _pageController.animateToPage(
          targetPageIndex,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeInOut,
        );
      }
    });

    return Scaffold(
      backgroundColor: const Color(0xFF12141C),
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            child: Padding(
              padding: const EdgeInsets.all(24.0),
              child: Card(
                color: const Color(0xFF1B1E2B),
                elevation: 8,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(24),
                ),
                child: Container(
                  width: double.infinity,
                  constraints: const BoxConstraints(maxWidth: 400),
                  height: 520,
                  child: PageView(
                    controller: _pageController,
                    physics: const NeverScrollableScrollPhysics(),
                    children: [
                      _buildPhoneStep(context, ap),
                      _buildCodeStep(context, ap),
                      _buildPasswordStep(context, ap),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  // STEP 1: PHONE NUMBER ENTRY
  Widget _buildPhoneStep(BuildContext context, AccountProvider ap) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 20.0),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            const Center(
              child: ExcludeSemantics(
                child: CircleAvatar(
                  radius: 28,
                  backgroundColor: Color(0xFF6C63FF),
                  child: Icon(Icons.send_rounded, color: Colors.white, size: 28),
                ),
              ),
            ),
            const SizedBox(height: 12),
            const Center(
              child: Text(
                'Sign in to Telegram',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            const SizedBox(height: 6),
            Center(
              child: Text(
                'Use your phone number to log in',
                style: TextStyle(
                  color: Colors.grey[400],
                  fontSize: 14,
                ),
              ),
            ),
            const SizedBox(height: 12),
            
            // Country Selector Dropdown
            Semantics(
              label: 'Country dropdown selector',
              hint: 'Double tap to select country. Defaults to India.',
              child: DropdownButtonFormField<Map<String, String>>(
                value: _selectedCountry,
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Country',
                  labelStyle: TextStyle(color: Colors.white70),
                  enabledBorder: UnderlineInputBorder(
                    borderSide: BorderSide(color: Colors.white30),
                  ),
                  focusedBorder: UnderlineInputBorder(
                    borderSide: BorderSide(color: Color(0xFF6C63FF)),
                  ),
                ),
                dropdownColor: const Color(0xFF1B1E2B),
                style: const TextStyle(color: Colors.white),
                items: _countries.map((country) {
                  return DropdownMenuItem<Map<String, String>>(
                    value: country,
                    child: Text(
                      '${country['name']} (${country['dial_code']})',
                      overflow: TextOverflow.ellipsis,
                    ),
                  );
                }).toList(),
                onChanged: (val) {
                  if (val != null) {
                    setState(() {
                      _selectedCountry = val;
                      _countryCodeController.text = val['dial_code'] ?? '';
                    });
                  }
                },
              ),
            ),
            const SizedBox(height: 12),
            
            // Phone Input Fields
            Row(
              children: [
                SizedBox(
                  width: 70,
                  child: Semantics(
                    label: 'Country code input',
                    hint: 'Enter your country code prefix starting with plus',
                    child: TextField(
                      controller: _countryCodeController,
                      keyboardType: TextInputType.phone,
                      style: const TextStyle(color: Colors.white),
                      decoration: InputDecoration(
                        hintText: '+1',
                        hintStyle: TextStyle(color: Colors.grey[600]),
                        enabledBorder: const UnderlineInputBorder(
                          borderSide: BorderSide(color: Colors.white30),
                        ),
                        focusedBorder: const UnderlineInputBorder(
                          borderSide: BorderSide(color: Color(0xFF6C63FF)),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: Semantics(
                    label: 'Phone number input',
                    hint: 'Enter your Telegram mobile number without country code',
                    child: TextField(
                      controller: _phoneController,
                      keyboardType: TextInputType.phone,
                      style: const TextStyle(color: Colors.white),
                      decoration: InputDecoration(
                        hintText: 'Phone Number',
                        hintStyle: TextStyle(color: Colors.grey[600]),
                        enabledBorder: const UnderlineInputBorder(
                          borderSide: BorderSide(color: Colors.white30),
                        ),
                        focusedBorder: const UnderlineInputBorder(
                          borderSide: BorderSide(color: Color(0xFF6C63FF)),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            
            // Account Nickname/Label
            Semantics(
              label: 'Account label optional',
              hint: 'Give this account profile a custom name like Work or Personal',
              child: TextField(
                controller: _labelController,
                style: const TextStyle(color: Colors.white),
                decoration: InputDecoration(
                  hintText: 'Account Name (e.g. Work, Personal) - Optional',
                  hintStyle: TextStyle(color: Colors.grey[600]),
                  enabledBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: Colors.white30),
                  ),
                  focusedBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: Color(0xFF6C63FF)),
                  ),
                ),
              ),
            ),
            
            if (ap.status != LoginStatus.idle && ap.status != LoginStatus.codeSent && ap.status != LoginStatus.failed) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF6C63FF).withOpacity(0.1),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: const Color(0xFF6C63FF).withOpacity(0.3)),
                ),
                child: Row(
                  children: [
                    const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF6C63FF)),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        ap.statusMessage,
                        style: const TextStyle(color: Colors.white70, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ],

            if (ap.loginError != null) ...[
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.redAccent.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.redAccent.withOpacity(0.3)),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.error_outline, color: Colors.redAccent, size: 20),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Connection Error',
                            style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold, fontSize: 13),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            ap.loginError!,
                            style: const TextStyle(color: Colors.white70, fontSize: 12),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 20),
            
            Align(
              alignment: Alignment.bottomRight,
              child: ap.isLoading
                  ? const SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF6C63FF)),
                    )
                  : ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF6C63FF),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                      ),
                      onPressed: () async {
                        final fullPhone = '${_countryCodeController.text.trim()}${_phoneController.text.trim()}';
                        final success = await ap.sendVerificationCode(fullPhone, _labelController.text.trim());
                        if (success && mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            const SnackBar(
                              content: Row(
                                children: [
                                  Icon(Icons.mark_email_unread_outlined, color: Color(0xFF6C63FF)),
                                  SizedBox(width: 12),
                                  Expanded(
                                    child: Text(
                                      'Verification code requested. Please check your Telegram app or SMS messages.',
                                      style: TextStyle(fontWeight: FontWeight.bold),
                                    ),
                                  ),
                                ],
                              ),
                              backgroundColor: Color(0xFF1B1E2B),
                              duration: Duration(seconds: 8),
                              behavior: SnackBarBehavior.floating,
                            ),
                          );
                        }
                      },
                      child: const Text('Next', style: TextStyle(color: Colors.white)),
                    ),
            ),
          ],
        ),
      ),
    );
  }

  // STEP 2: VERIFICATION CODE ENTRY
  Widget _buildCodeStep(BuildContext context, AccountProvider ap) {
    return Padding(
      padding: const EdgeInsets.all(32.0),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.arrow_back, color: Colors.white),
              onPressed: () {
                ap.prevStep();
              },
            ),
            const SizedBox(height: 8),
            const Center(
              child: Text(
                'Enter Code',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            const SizedBox(height: 8),
            Center(
              child: Text(
                'We sent a verification code to your Telegram. Please check and enter the 5-digit code. Do not share this code with anyone.',
                style: TextStyle(
                  color: Colors.grey[400],
                  fontSize: 13,
                ),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 40),
            
            // Code Textbox
            Semantics(
              label: 'Telegram verification code input',
              hint: 'Enter the 5-digit verification code sent to your Telegram app. Do not share this code with anyone.',
              child: TextField(
                controller: _codeController,
                keyboardType: TextInputType.number,
                textAlign: TextAlign.center,
                style: const TextStyle(color: Colors.white, fontSize: 24, letterSpacing: 8),
                maxLength: 5,
                decoration: InputDecoration(
                  counterText: '',
                  hintText: '•••••',
                  hintStyle: TextStyle(color: Colors.grey[700], letterSpacing: 8),
                  enabledBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: Colors.white30),
                  ),
                  focusedBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: Color(0xFF6C63FF)),
                  ),
                ),
              ),
            ),
            
            if (ap.status == LoginStatus.verifyingCode) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF6C63FF).withOpacity(0.1),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: const Color(0xFF6C63FF).withOpacity(0.3)),
                ),
                child: Row(
                  children: [
                    const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF6C63FF)),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        ap.statusMessage,
                        style: const TextStyle(color: Colors.white70, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ],

            if (ap.loginError != null) ...[
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.redAccent.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.redAccent.withOpacity(0.3)),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.error_outline, color: Colors.redAccent, size: 20),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Verification Error',
                            style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold, fontSize: 13),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            ap.loginError!,
                            style: const TextStyle(color: Colors.white70, fontSize: 12),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],
            
            const SizedBox(height: 20),
            
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Semantics(
                  button: true,
                  label: 'Resend verification code',
                  hint: 'Double tap to request a new verification code from Telegram',
                  child: TextButton(
                    onPressed: ap.isLoading
                        ? null
                        : () async {
                            final success = await ap.sendVerificationCode(ap.phoneNumber, ap.sessionLabel);
                            if (success && mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(
                                  content: Text('A new verification code has been requested.'),
                                  backgroundColor: Color(0xFF1B1E2B),
                                  duration: Duration(seconds: 4),
                                  behavior: SnackBarBehavior.floating,
                                ),
                              );
                            }
                          },
                    child: const Text(
                      'Resend Code',
                      style: TextStyle(color: Color(0xFF8B80FF), fontWeight: FontWeight.bold),
                    ),
                  ),
                ),
                ap.isLoading
                    ? const SizedBox(
                        width: 24,
                        height: 24,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF6C63FF)),
                      )
                    : Semantics(
                        button: true,
                        label: 'Next step',
                        hint: 'Double tap to verify code and proceed',
                        child: ElevatedButton(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: const Color(0xFF6C63FF),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                            padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                          ),
                          onPressed: () async {
                            final success = await ap.submitVerificationCode(_codeController.text.trim());
                            if (success && !ap.requiresTwoFactor) {
                              if (widget.onLoginSuccess != null) {
                                widget.onLoginSuccess!();
                              } else {
                                Navigator.pop(context);
                              }
                            }
                          },
                          child: const Text('Next', style: TextStyle(color: Colors.white)),
                        ),
                      ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  // STEP 3: 2FA PASSWORD ENTRY
  Widget _buildPasswordStep(BuildContext context, AccountProvider ap) {
    return Padding(
      padding: const EdgeInsets.all(32.0),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              icon: const Icon(Icons.arrow_back, color: Colors.white),
              onPressed: () {
                ap.prevStep();
              },
            ),
            const SizedBox(height: 8),
            const Center(
              child: Text(
                'Enter 2FA Password',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 22,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            const SizedBox(height: 8),
            Center(
              child: Text(
                'Your account has Two-Step Verification enabled. Enter password (hint: "12345").',
                style: TextStyle(
                  color: Colors.grey[400],
                  fontSize: 13,
                ),
                textAlign: TextAlign.center,
              ),
            ),
            const SizedBox(height: 40),
            
            // Password Textbox
            Semantics(
              label: 'Two factor password input',
              hint: 'Enter your custom two factor account password',
              child: TextField(
                controller: _passwordController,
                obscureText: !_isPasswordVisible,
                style: const TextStyle(color: Colors.white),
                decoration: InputDecoration(
                  hintText: 'Password',
                  hintStyle: TextStyle(color: Colors.grey[600]),
                  enabledBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: Colors.white30),
                  ),
                  focusedBorder: const UnderlineInputBorder(
                    borderSide: BorderSide(color: Color(0xFF6C63FF)),
                  ),
                  suffixIcon: IconButton(
                    icon: Icon(
                      _isPasswordVisible ? Icons.visibility : Icons.visibility_off,
                      color: Colors.white54,
                    ),
                    onPressed: () {
                      setState(() {
                        _isPasswordVisible = !_isPasswordVisible;
                      });
                    },
                  ),
                ),
              ),
            ),
            
            if (ap.status == LoginStatus.verifyingPwd) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0xFF6C63FF).withOpacity(0.1),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: const Color(0xFF6C63FF).withOpacity(0.3)),
                ),
                child: Row(
                  children: [
                    const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF6C63FF)),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        ap.statusMessage,
                        style: const TextStyle(color: Colors.white70, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ],

            if (ap.loginError != null) ...[
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.redAccent.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(color: Colors.redAccent.withOpacity(0.3)),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(Icons.error_outline, color: Colors.redAccent, size: 20),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text(
                            'Password Error',
                            style: TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold, fontSize: 13),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            ap.loginError!,
                            style: const TextStyle(color: Colors.white70, fontSize: 12),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],
            
            const SizedBox(height: 20),
            
            Align(
              alignment: Alignment.bottomRight,
              child: ap.isLoading
                  ? const SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF6C63FF)),
                    )
                  : ElevatedButton(
                      style: ElevatedButton.styleFrom(
                        backgroundColor: const Color(0xFF6C63FF),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                      ),
                      onPressed: () async {
                        final success = await ap.submitPassword(_passwordController.text.trim());
                        if (success) {
                          if (widget.onLoginSuccess != null) {
                            widget.onLoginSuccess!();
                          } else {
                            Navigator.pop(context);
                          }
                        }
                      },
                      child: const Text('Submit', style: TextStyle(color: Colors.white)),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
