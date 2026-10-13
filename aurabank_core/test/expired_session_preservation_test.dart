// Preservation tests for the expired-session fix.
//
// Observed on UNFIXED code and expected to PASS both before and after the fix:
// recoverable and non-auth failures keep behaving exactly as they do today.
// Property-style checks are seeded generator loops over plain flutter_test.
//
// **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7**
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:aurabank_core/navigation/root_navigator.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:aurabank_core/services/device_storage.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// Builds an unsigned JWT; [payload] is encoded as-is.
String _jwtWith(Map<String, Object?> payload) {
  String enc(Object o) => base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${enc({'alg': 'none'})}.${enc(payload)}.sig';
}

String _jwt({required int expSecondsFromNow}) {
  final exp = DateTime.now().millisecondsSinceEpoch ~/ 1000 + expSecondsFromNow;
  return _jwtWith({'sub': 'u1', 'exp': exp});
}

Future<void> _seedSession({required String accessToken, String? refreshToken}) async {
  await DeviceStorage.clearSession();
  await DeviceStorage.saveAccessToken(accessToken);
  await DeviceStorage.saveUserId('u1');
  if (refreshToken != null) await DeviceStorage.saveRefreshToken(refreshToken);
  final auth = AuthApiService();
  auth.currentAccessToken = accessToken;
  auth.currentUserId = 'u1';
}

/// Runs [body] against a mock client and records every request it makes.
Future<T> _withClient<T>(
  Future<http.Response> Function(http.Request) handler,
  List<http.Request> log,
  Future<T> Function() body,
) {
  return http.runWithClient(body, () => MockClient((req) {
        log.add(req);
        return handler(req);
      }));
}

Future<Map<String, dynamic>> _transfer() => BankService().executeTransfer(
      targetAccount: '1000-2000-3002',
      recipientName: 'Test Payee',
      amount: 100,
      destinationBank: 'AuraBank',
    );

bool _isRefresh(http.Request r) => r.url.path.endsWith('/auth/refresh');

void _expectStillSignedIn(String token) {
  expect(AuthApiService().isAuthenticated, isTrue);
  expect(DeviceStorage.getAccessToken(), token);
  expect(AuthApiService().currentUserId, 'u1');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('3.1 refresh then retry', () {
    testWidgets('401 -> refresh 200 -> retry 200: one refresh, one retry, token replaced, user stays',
        (tester) async {
      final oldToken = _jwt(expSecondsFromNow: 600);
      final newToken = _jwt(expSecondsFromNow: 900);
      await _seedSession(accessToken: oldToken, refreshToken: 'good-refresh');

      await tester.pumpWidget(MaterialApp(
        navigatorKey: rootNavigatorKey,
        initialRoute: '/dashboard',
        routes: {
          '/dashboard': (_) => const Scaffold(body: Text('Authenticated screen')),
          '/login': (_) => const Scaffold(body: Text('Sign in')),
        },
      ));
      await tester.pumpAndSettle();

      final log = <http.Request>[];
      late Map<String, dynamic> result;
      await tester.runAsync(() async {
        result = await _withClient((req) async {
          if (_isRefresh(req)) {
            return http.Response(jsonEncode({'access_token': newToken}), 200,
                headers: {'set-cookie': 'refresh_token=rotated-refresh; Path=/; HttpOnly'});
          }
          if (req.headers['Authorization'] == 'Bearer $oldToken') return http.Response('', 401);
          return http.Response(jsonEncode({'status': 'COMMITTED', 'transactionId': 'T-1'}), 200);
        }, log, _transfer);
      });
      await tester.pumpAndSettle();

      final refreshes = log.where(_isRefresh).toList();
      final transfers = log.where((r) => !_isRefresh(r)).toList();
      expect(result['success'], isTrue);
      expect(refreshes, hasLength(1));
      expect(transfers, hasLength(2), reason: 'one original call plus one retry');
      expect(transfers.last.headers['Authorization'], 'Bearer $newToken');
      expect(DeviceStorage.getAccessToken(), newToken);
      expect(DeviceStorage.getRefreshToken(), 'rotated-refresh');
      expect(AuthApiService().isAuthenticated, isTrue);
      expect(find.text('Authenticated screen'), findsOneWidget);
      expect(find.text('Sign in'), findsNothing);
    });
  });

  group('3.4 / 3.5 transfer failures keep the session', () {
    test('400/403/422 return the same failureReason and keep the session', () async {
      final rng = Random(42);
      for (var i = 0; i < 30; i++) {
        final status = const [400, 403, 422][rng.nextInt(3)];
        final reason = 'Reason ${rng.nextInt(100000)} for $status';
        final shape = rng.nextInt(3); // message, error, non-JSON body
        final body = switch (shape) {
          0 => jsonEncode({'message': reason}),
          1 => jsonEncode({'error': reason}),
          _ => 'not json',
        };
        final expected = shape == 2 ? 'Transfer rejected by core banking ($status)' : reason;
        final token = _jwt(expSecondsFromNow: 60 + rng.nextInt(5000));
        await _seedSession(accessToken: token, refreshToken: 'rt-$i');

        final log = <http.Request>[];
        final result = await _withClient((_) async => http.Response(body, status), log, _transfer);

        expect(result['success'], isFalse, reason: 'status $status shape $shape');
        expect(result['failureReason'], expected, reason: 'status $status shape $shape');
        expect(log.where(_isRefresh), isEmpty);
        _expectStillSignedIn(token);
        expect(DeviceStorage.getRefreshToken(), 'rt-$i');
      }
    });

    test('timeout and connection errors fail the transfer and keep the session', () async {
      final errors = <Object Function()>[
        () => TimeoutException('slow'),
        () => http.ClientException('connection refused'),
        () => const SocketException('unreachable'),
      ];
      for (var i = 0; i < errors.length * 3; i++) {
        final token = _jwt(expSecondsFromNow: 600);
        await _seedSession(accessToken: token, refreshToken: 'rt');

        final log = <http.Request>[];
        final result = await _withClient((_) async => throw errors[i % errors.length](), log, _transfer);

        expect(result['success'], isFalse, reason: 'unreachable backend must never report success');
        expect(result['reference'], startsWith('FT'));
        expect(result['failureReason'], isNotNull);
        _expectStillSignedIn(token);
      }
    });
  });

  group('3.6 login errors', () {
    test('login 401/403 return the existing invalid-credentials result', () async {
      final rng = Random(7);
      for (var i = 0; i < 12; i++) {
        final status = i.isEven ? 401 : 403;
        final detail = rng.nextBool() ? 'Bad creds ${rng.nextInt(1000)}' : null;
        final token = _jwt(expSecondsFromNow: 600);
        await _seedSession(accessToken: token);

        final log = <http.Request>[];
        final result = await _withClient(
          (_) async => http.Response(detail == null ? '{}' : jsonEncode({'detail': detail}), status),
          log,
          () => AuthApiService().login(email: 'user$i@example.com', password: 'pw'),
        );

        expect(result.status, AuthStatus.failed);
        expect(result.errorMessage, detail ?? 'Invalid email or password credentials.');
        expect(log.where(_isRefresh), isEmpty);
        expect(log.where((r) => r.url.path.endsWith('/auth/logout')), isEmpty);
        _expectStillSignedIn(token);
      }
    });
  });

  group('3.3 / 3.7 manual logout', () {
    test('logout() calls the endpoint, clears state and keeps the biometric profile', () async {
      final token = _jwt(expSecondsFromNow: 600);
      await _seedSession(accessToken: token, refreshToken: 'rt');
      await DeviceStorage.saveBiometricProfile(userId: 'u1', email: 'bio@example.com', name: 'Bio User');

      final log = <http.Request>[];
      await _withClient((_) async => http.Response('', 200), log, () => AuthApiService().logout());

      final logouts = log.where((r) => r.url.path.endsWith('/api/v1/auth/logout')).toList();
      expect(logouts, hasLength(1));
      expect(logouts.single.headers['Authorization'], 'Bearer $token');
      expect(AuthApiService().currentAccessToken, isNull);
      expect(AuthApiService().currentUserId, isNull);
      expect(DeviceStorage.getAccessToken(), isNull);
      expect(DeviceStorage.getRefreshToken(), isNull);
      expect(AuthApiService().isAuthenticated, isFalse);
      expect(DeviceStorage.getBiometricUserEmail(), 'bio@example.com');
      expect(DeviceStorage.getBiometricUserId(), 'u1');
    });
  });

  group('3.2 non-expired token shapes stay authenticated', () {
    test('opaque, missing exp, malformed, two-part and future-exp tokens', () async {
      final rng = Random(99);
      final fixed = <String>[
        'mock-valid-token-12345',
        'active_token',
        _jwtWith({'sub': 'u1'}), // no exp
        'aaa.bbb', // two parts
        'aaa.@@not-base64@@.ccc', // malformed payload
        'aaa.${base64Url.encode(utf8.encode('not json')).replaceAll('=', '')}.ccc',
      ];
      final generated = [
        for (var i = 0; i < 25; i++) _jwt(expSecondsFromNow: 5 + rng.nextInt(86400 * 30)),
        for (var i = 0; i < 10; i++) 'opaque-${rng.nextInt(1 << 30).toRadixString(36)}',
      ];

      for (final token in [...fixed, ...generated]) {
        await _seedSession(accessToken: token);
        expect(AuthApiService().isAuthenticated, isTrue, reason: 'token "$token"');

        // Same answer when only storage holds the token.
        AuthApiService().currentAccessToken = null;
        expect(AuthApiService().isAuthenticated, isTrue, reason: 'storage-only token "$token"');
      }
    });

    test('no token at all is not authenticated', () async {
      await DeviceStorage.clearSession();
      AuthApiService().currentAccessToken = null;
      expect(AuthApiService().isAuthenticated, isFalse);
    });
  });
}
