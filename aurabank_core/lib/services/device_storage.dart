import 'dart:io' show Directory, File, Platform;
import 'dart:math';
import 'package:flutter/foundation.dart' show debugPrint, kIsWeb;
import 'package:shared_preferences/shared_preferences.dart';

/// Persistent Device Storage using SharedPreferences and OS persistent storage.
/// Persists unique hardware device ID and authentication tokens across app restarts.
class DeviceStorage {
  static SharedPreferences? _prefs;
  static final Map<String, String> _memoryCache = {};

  static const String _deviceIdKey = 'bank_device_id';
  static const String _tokenKey = 'auth_access_token';
  static const String _userIdKey = 'auth_user_id';
  static const String _userEmailKey = 'auth_user_email';
  static const String _userNameKey = 'auth_user_name';
  static const String _userPhoneKey = 'auth_user_phone';
  static const String _userAddressKey = 'auth_user_address';
  static const String _userAccountIdKey = 'auth_user_account_id';
  static const String _userBalanceKey = 'auth_user_balance';
  static const String _lastLoginEmailKey = 'auth_last_login_email';
  static const String _lastLoginNameKey = 'auth_last_login_name';

  /// Initializes SharedPreferences instance. Called in main() before runApp().
  static Future<void> init() async {
    try {
      _prefs = await SharedPreferences.getInstance();
    } catch (e) {
      debugPrint('[DeviceStorage] SharedPreferences init exception: $e');
    }
  }

  /// Persistent Hardware / Unique Device ID across restarts, app updates, and reboots.
  static String getOrCreateDeviceId(String prefix) {
    // 1. Try SharedPreferences (Persistent across app restarts)
    if (_prefs != null) {
      final existing = _prefs!.getString(_deviceIdKey);
      if (existing != null && existing.isNotEmpty) {
        _memoryCache[prefix] = existing;
        return existing;
      }
    }

    // 2. Try Memory Cache
    if (_memoryCache.containsKey(prefix)) {
      return _memoryCache[prefix]!;
    }

    // 3. Try Persistent Local Storage File (Desktop / Native fallback)
    if (!kIsWeb) {
      try {
        final dir = _resolvePersistentDir();
        final file = File('$dir/.aura_bank_device_id.dat');
        if (file.existsSync()) {
          final content = file.readAsStringSync().trim();
          if (content.isNotEmpty) {
            _memoryCache[prefix] = content;
            _prefs?.setString(_deviceIdKey, content);
            return content;
          }
        }
      } catch (_) {}
    }

    // 4. Generate a stable device ID
    final rand = Random().nextInt(0xFFFFFF).toRadixString(16).padLeft(6, '0');
    final newId = '$prefix-$rand';

    _memoryCache[prefix] = newId;
    _prefs?.setString(_deviceIdKey, newId);

    if (!kIsWeb) {
      try {
        final dir = _resolvePersistentDir();
        final file = File('$dir/.aura_bank_device_id.dat');
        file.writeAsStringSync(newId);
      } catch (_) {}
    }

    return newId;
  }

  static String _resolvePersistentDir() {
    try {
      if (Platform.isWindows) {
        final appData = Platform.environment['APPDATA'] ?? Platform.environment['LOCALAPPDATA'];
        if (appData != null && Directory(appData).existsSync()) return appData;
      } else if (Platform.isLinux || Platform.isMacOS) {
        final home = Platform.environment['HOME'];
        if (home != null && Directory(home).existsSync()) return home;
      }
    } catch (_) {}
    return Directory.systemTemp.path;
  }

  /// Persists authentication JWT token
  static Future<void> saveAccessToken(String token) async {
    _memoryCache[_tokenKey] = token;
    await _prefs?.setString(_tokenKey, token);
  }

  /// Retrieves persisted authentication JWT token
  static String? getAccessToken() {
    return _prefs?.getString(_tokenKey) ?? _memoryCache[_tokenKey];
  }

  /// Persists user identifier
  static Future<void> saveUserId(String userId) async {
    _memoryCache[_userIdKey] = userId;
    await _prefs?.setString(_userIdKey, userId);
  }

  /// Retrieves persisted user identifier
  static String? getUserId() {
    return _prefs?.getString(_userIdKey) ?? _memoryCache[_userIdKey];
  }

  /// Persists full user profile session data
  static Future<void> saveUserSessionProfile({
    required String userId,
    required String email,
    required String name,
    String? phone,
    String? address,
    String? accountId,
    double? balance,
  }) async {
    await saveUserId(userId);
    _memoryCache[_userEmailKey] = email;
    _memoryCache[_userNameKey] = name;
    await _prefs?.setString(_userEmailKey, email);
    await _prefs?.setString(_userNameKey, name);
    if (phone != null && phone.isNotEmpty) {
      _memoryCache[_userPhoneKey] = phone;
      await _prefs?.setString(_userPhoneKey, phone);
    }
    if (address != null && address.isNotEmpty) {
      _memoryCache[_userAddressKey] = address;
      await _prefs?.setString(_userAddressKey, address);
    }
    if (accountId != null && accountId.isNotEmpty) {
      _memoryCache[_userAccountIdKey] = accountId;
      await _prefs?.setString(_userAccountIdKey, accountId);
    }
    if (balance != null) {
      _memoryCache[_userBalanceKey] = balance.toString();
      await _prefs?.setDouble(_userBalanceKey, balance);
    }
  }

  static String? getUserEmail() => _prefs?.getString(_userEmailKey) ?? _memoryCache[_userEmailKey];
  static String? getUserName() => _prefs?.getString(_userNameKey) ?? _memoryCache[_userNameKey];
  static String? getUserPhone() => _prefs?.getString(_userPhoneKey) ?? _memoryCache[_userPhoneKey];
  static String? getUserAddress() => _prefs?.getString(_userAddressKey) ?? _memoryCache[_userAddressKey];
  static String? getUserAccountId() => _prefs?.getString(_userAccountIdKey) ?? _memoryCache[_userAccountIdKey];
  static double? getUserBalance() {
    final b = _prefs?.getDouble(_userBalanceKey);
    if (b != null) return b;
    final cached = _memoryCache[_userBalanceKey];
    return cached != null ? double.tryParse(cached) : null;
  }

  static Future<void> saveLastLoginEmail(String email) async {
    _memoryCache[_lastLoginEmailKey] = email;
    await _prefs?.setString(_lastLoginEmailKey, email);
  }

  static String? getLastLoginEmail() =>
      _prefs?.getString(_lastLoginEmailKey) ?? _memoryCache[_lastLoginEmailKey];

  static Future<void> saveLastLoginName(String name) async {
    _memoryCache[_lastLoginNameKey] = name;
    await _prefs?.setString(_lastLoginNameKey, name);
  }

  static String? getLastLoginName() =>
      _prefs?.getString(_lastLoginNameKey) ?? _memoryCache[_lastLoginNameKey];

  static Future<void> clearLastLoginEmail() async {
    _memoryCache.remove(_lastLoginEmailKey);
    _memoryCache.remove(_lastLoginNameKey);
    await _prefs?.remove(_lastLoginEmailKey);
    await _prefs?.remove(_lastLoginNameKey);
  }

  /// Clears active user tokens and session data upon logout while preserving device ID
  static Future<void> clearSession() async {
    _memoryCache.remove(_tokenKey);
    _memoryCache.remove(_userIdKey);
    _memoryCache.remove(_userEmailKey);
    _memoryCache.remove(_userNameKey);
    _memoryCache.remove(_userPhoneKey);
    _memoryCache.remove(_userAddressKey);
    _memoryCache.remove(_userAccountIdKey);
    _memoryCache.remove(_userBalanceKey);
    await _prefs?.remove(_tokenKey);
    await _prefs?.remove(_userIdKey);
    await _prefs?.remove(_userEmailKey);
    await _prefs?.remove(_userNameKey);
    await _prefs?.remove(_userPhoneKey);
    await _prefs?.remove(_userAddressKey);
    await _prefs?.remove(_userAccountIdKey);
    await _prefs?.remove(_userBalanceKey);
  }
}
