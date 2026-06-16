import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'package:tg/tg.dart';
import 'package:t/t.dart' as t;

abstract class TelegramAuthService {
  Future<void> connect();
  Future<String> sendCode(String phoneNumber);
  Future<bool> signIn(String phoneNumber, String phoneCodeHash, String code);
  Future<bool> checkPassword(String password);
  Future<void> disconnect();
  String? get generatedCode; // For mock/testing code inspection
}

class TelegramSocket implements SocketAbstraction {
  final Socket _socket;
  final _controller = StreamController<Uint8List>.broadcast();
  StreamSubscription<List<int>>? _subscription;
  VoidCallback? onClose;

  TelegramSocket(this._socket) {
    _subscription = _socket.listen(
      (data) {
        if (!_controller.isClosed) {
          _controller.add(Uint8List.fromList(data));
        }
      },
      onError: (err) {
        if (!_controller.isClosed) {
          _controller.addError(err);
        }
      },
      onDone: () {
        if (!_controller.isClosed) {
          _controller.close();
        }
        onClose?.call();
      },
      cancelOnError: true,
    );
  }

  @override
  Stream<Uint8List> get receiver => _controller.stream;

  @override
  Future<void> send(List<int> data) async {
    _socket.add(data);
    await _socket.flush();
  }

  Future<void> close() async {
    await _subscription?.cancel();
    await _socket.close();
    if (!_controller.isClosed) {
      await _controller.close();
    }
  }
}

class RealTelegramAuthService implements TelegramAuthService {
  Client? _client;
  TelegramSocket? _socket;
  bool _isConnected = false;
  
  static const Map<int, String> _dcIps = {
    1: '149.154.175.53',
    2: '149.154.167.51',
    3: '149.154.175.100',
    4: '149.154.167.91',
    5: '91.108.56.130',
  };
  String _currentDcIp = '149.154.167.91'; // Default DC 4 / DC 2

  String _getIpForDc(int dcId) {
    return _dcIps[dcId] ?? '149.154.167.91';
  }

  @override
  String? get generatedCode => null; // Real client does not expose/know code

  @override
  Future<void> connect() async {
    if (_isConnected) return;
    try {
      final rawSocket = await Socket.connect(_currentDcIp, 443, timeout: const Duration(seconds: 10));
      final obfuscation = Obfuscation.random(false, 4);
      rawSocket.add(obfuscation.preamble);
      await rawSocket.flush();

      _socket = TelegramSocket(rawSocket);
      _socket!.onClose = () {
        _isConnected = false;
        _client = null;
      };
      final idGenerator = MessageIdGenerator();

      final authKey = await Client.authorize(_socket!, obfuscation, idGenerator);

      _client = Client(
        socket: _socket!,
        obfuscation: obfuscation,
        authorizationKey: authKey,
        idGenerator: idGenerator,
      );
      _isConnected = true;
    } catch (e) {
      _isConnected = false;
      throw Exception("CONNECTION_FAILED");
    }
  }

  @override
  Future<String> sendCode(String phoneNumber) async {
    await connect();
    final sendCodeQuery = t.InvokeWithLayer(
      layer: 227,
      query: t.InitConnection(
        apiId: 0,
        deviceModel: "Android Device",
        systemVersion: "Android 13",
        appVersion: "1.0.1",
        systemLangCode: "en",
        langPack: "",
        langCode: "en",
        query: t.AuthSendCode(
          phoneNumber: phoneNumber,
          apiId: 0,
          apiHash: 'REDACTED',
          settings: const t.CodeSettings(
            allowFlashcall: false,
            currentNumber: false,
            allowAppHash: false,
            allowMissedCall: false,
            allowFirebase: false,
            unknownNumber: false,
          ),
        ),
      ),
    );

    final result = await _client!.invoke(sendCodeQuery);
    if (result.result != null) {
      if (result.result is t.AuthSentCode) {
        final sentCode = result.result as t.AuthSentCode;
        return sentCode.phoneCodeHash;
      } else {
        throw Exception("UNKNOWN_RESPONSE");
      }
    } else {
      final rpcError = result.error;
      if (rpcError != null) {
        final msg = rpcError.errorMessage;
        
        // Handle DC redirection on PHONE_MIGRATE_X, USER_MIGRATE_X, or NETWORK_MIGRATE_X
        if (msg.contains("PHONE_MIGRATE_") || msg.contains("USER_MIGRATE_") || msg.contains("NETWORK_MIGRATE_")) {
          final regExp = RegExp(r'(PHONE_MIGRATE_|NETWORK_MIGRATE_|USER_MIGRATE_)(\d+)');
          final match = regExp.firstMatch(msg);
          if (match != null) {
            final dcId = int.parse(match.group(2)!);
            _currentDcIp = _getIpForDc(dcId);
            await disconnect(); // Reset socket and _isConnected state
            return sendCode(phoneNumber); // Retry dynamically on the migrated DC!
          }
        }
        
        if (msg.contains("PHONE_NUMBER_INVALID")) {
          throw Exception("PHONE_NUMBER_INVALID");
        } else if (msg.contains("FLOOD_WAIT")) {
          throw Exception("FLOOD_WAIT");
        }
        throw Exception(msg);
      }
      throw Exception("SEND_CODE_FAILED");
    }
  }

  @override
  Future<bool> signIn(String phoneNumber, String phoneCodeHash, String code) async {
    await connect();
    final result = await _client!.invoke(t.AuthSignIn(
      phoneNumber: phoneNumber,
      phoneCodeHash: phoneCodeHash,
      phoneCode: code,
    )).timeout(const Duration(seconds: 15), onTimeout: () {
      throw Exception("CONNECTION_TIMEOUT");
    });

    if (result.result != null) {
      return true;
    } else {
      final rpcError = result.error;
      if (rpcError != null) {
        final msg = rpcError.errorMessage;
        if (msg.contains("SESSION_PASSWORD_NEEDED")) {
          throw Exception("SESSION_PASSWORD_NEEDED");
        } else if (msg.contains("PHONE_CODE_INVALID")) {
          throw Exception("PHONE_CODE_INVALID");
        } else if (msg.contains("PHONE_CODE_EXPIRED")) {
          throw Exception("PHONE_CODE_EXPIRED");
        }
        throw Exception(msg);
      }
      throw Exception("SIGN_IN_FAILED");
    }
  }

  @override
  Future<bool> checkPassword(String password) async {
    await connect();
    final getPwdResult = await _client!.invoke(const t.AccountGetPassword());
    if (getPwdResult.result is! t.AccountPassword) {
      throw Exception("GET_PASSWORD_FAILED");
    }
    final accountPassword = getPwdResult.result as t.AccountPassword;
    final inputCheckPasswordSRP = await check2FA(accountPassword, password);
    final checkResult = await _client!.invoke(t.AuthCheckPassword(password: inputCheckPasswordSRP));

    if (checkResult.result != null) {
      return true;
    } else {
      final rpcError = checkResult.error;
      if (rpcError != null) {
        final msg = rpcError.errorMessage;
        if (msg.contains("PASSWORD_HASH_INVALID")) {
          throw Exception("PASSWORD_HASH_INVALID");
        }
        throw Exception(msg);
      }
      throw Exception("CHECK_PASSWORD_FAILED");
    }
  }

  @override
  Future<void> disconnect() async {
    if (_socket != null) {
      await _socket!.close();
      _socket = null;
    }
    _client = null;
    _isConnected = false;
  }
}

class MockTelegramAuthService implements TelegramAuthService {
  String? _mockCode;
  bool _isConnected = false;

  @override
  String? get generatedCode => _mockCode;

  @override
  Future<void> connect() async {
    if (_isConnected) return;
    await Future.delayed(const Duration(milliseconds: 500));
    _isConnected = true;
  }

  @override
  Future<String> sendCode(String phoneNumber) async {
    await connect();
    if (phoneNumber.isEmpty) {
      throw Exception("PHONE_NUMBER_INVALID");
    }
    final random = math.Random();
    _mockCode = (10000 + random.nextInt(90000)).toString();
    return "mock_hash_for_${phoneNumber}_$_mockCode";
  }

  @override
  Future<bool> signIn(String phoneNumber, String phoneCodeHash, String code) async {
    await connect();
    await Future.delayed(const Duration(milliseconds: 500));
    if (code != _mockCode) {
      throw Exception("PHONE_CODE_INVALID");
    }
    if (phoneNumber.endsWith("0")) {
      throw Exception("SESSION_PASSWORD_NEEDED");
    }
    return true;
  }

  @override
  Future<bool> checkPassword(String password) async {
    await connect();
    await Future.delayed(const Duration(milliseconds: 500));
    if (password != '12345' && password.toLowerCase() != 'password') {
      throw Exception("PASSWORD_HASH_INVALID");
    }
    return true;
  }

  @override
  Future<void> disconnect() async {
    _isConnected = false;
    _mockCode = null;
  }
}
