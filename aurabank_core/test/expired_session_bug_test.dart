// Bug condition exploration tests for the expired-session fix.
//
// Assertions encode the EXPECTED (fixed) behaviour, so they are expected to FAIL
// on unfixed code: the user stays on the authenticated route, tokens are kept and
// isAuthenticated stays true.
//
// **Validates: Requirements 1.1, 1.2, 1.3, 1.4**
import 'dart:convert';

import 'package:aurabank_core/navigation/root_navigator.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:aurabank_core/screens/auth/login_screen.dart';
import 'package:aurabank_core/services/device_storage.dart';
import 'package:aurabank_core/services/session_guard.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

/// Builds an unsigned JWT whose payload carries the given `exp` (epoch seconds).
String _jwt({required int expSecondsFromNow}) {
  String enc(Object o) => base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  final exp = DateTime.now().millisecondsSinceEpoch ~/ 1000 + expSecondsFromNow;
  return '${enc({'alg': 'none'})}.${enc({'sub': 'u1', 'exp': exp})}.sig';
}

/// Tracks the live route names so the test can assert the stack.
class _StackObserver extends NavigatorObserver {
  final stack = <String?>[];
  final pushed = <String?>[];
  final pushedArgs = <Object?>[];
  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previous) {
    stack.add(route.settings.name);
    pushed.add(route.settings.name);
    pushedArgs.add(route.settings.arguments);
  }
  @override
  void didPop(Route<dynamic> route, Route<dynamic>? previous) => stack.remove(route.settings.name);
  @override
  void didRemove(Route<dynamic> route, Route<dynamic>? previous) => stack.remove(route.settings.name);
  @override
  void didReplace({Route<dynamic>? newRoute, Route<dynamic>? oldRoute}) {
    stack.remove(oldRoute?.settings.name);
    if (newRoute != null) stack.add(newRoute.settings.name);
  }
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

Future<_StackObserver> _pumpApp(WidgetTester tester, {WidgetBuilder? login}) async {
  final observer = _StackObserver();
  await tester.pumpWidget(MaterialApp(
    navigatorKey: rootNavigatorKey,
    navigatorObservers: [observer],
    initialRoute: '/dashboard',
    routes: {
      '/dashboard': (_) => const Scaffold(body: Text('Authenticated screen')),
      '/login': login ?? (_) => const Scaffold(body: Text('Sign in')),
    },
  ));
  await tester.pumpAndSettle();
  return observer;
}

/// Runs [body] with a mock client that answers 401 to everything.
Future<T> _with401<T>(WidgetTester tester, Future<T> Function() body, {bool settle = true}) async {
  late T result;
  await tester.runAsync(() async {
    result = await http.runWithClient(body, () => MockClient((_) async => http.Response('', 401)));
  });
  // The real LoginScreen runs endless animations, so it is pumped for a fixed time.
  settle ? await tester.pumpAndSettle() : await tester.pump(const Duration(milliseconds: 500));
  return result;
}

void _expectSignedOut(_StackObserver observer) {
  expect(AuthApiService().isAuthenticated, isFalse, reason: 'isAuthenticated still true');
  expect(DeviceStorage.getAccessToken(), isNull, reason: 'access token kept in storage');
  expect(DeviceStorage.getRefreshToken(), isNull, reason: 'refresh token kept in storage');
  expect(AuthApiService().currentUserId, isNull, reason: 'currentUserId kept');
  expect(observer.stack, ['/login'], reason: 'route stack is ${observer.stack}');
}

Future<Map<String, dynamic>> _transfer() => BankService().executeTransfer(
      targetAccount: '1000-2000-3002',
      recipientName: 'Test Payee',
      amount: 100,
      destinationBank: 'AuraBank',
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late String token;

  setUp(() {
    token = _jwt(expSecondsFromNow: 600);
  });

  testWidgets('transfer 401 with refresh rejected signs out to /login', (tester) async {
    await _seedSession(accessToken: token, refreshToken: 'revoked-refresh');
    final observer = await _pumpApp(tester);

    await _with401(tester, _transfer);

    _expectSignedOut(observer);
  });

  testWidgets('transfer 401 with no refresh token signs out to /login', (tester) async {
    await _seedSession(accessToken: token);
    final observer = await _pumpApp(tester);

    await _with401(tester, _transfer);

    _expectSignedOut(observer);
  });

  testWidgets('syncKycStatus 401 signs out to /login', (tester) async {
    await _seedSession(accessToken: token);
    final observer = await _pumpApp(tester);

    await _with401(tester, () => BankService().syncKycStatus('u1'));

    _expectSignedOut(observer);
  });

  testWidgets('refreshBalance 401 signs out to /login', (tester) async {
    await _seedSession(accessToken: token);
    final observer = await _pumpApp(tester);

    await _with401(tester, () => BankService().refreshBalance());

    _expectSignedOut(observer);
  });

  test('expired JWT with no refresh token is not authenticated', () async {
    await _seedSession(accessToken: _jwt(expSecondsFromNow: -60));

    expect(AuthApiService().isAuthenticated, isFalse, reason: 'expired token still counts as signed in');
  });

  for (var n = 1; n <= 10; n++) {
    testWidgets('$n concurrent 401 events push /login once with the expiry argument', (tester) async {
      await _seedSession(accessToken: token);
      final observer = await _pumpApp(tester);
      final bank = BankService();

      await _with401(tester, () => Future.wait([
            for (var i = 0; i < n; i++) i.isEven ? bank.syncKycStatus('u1') : bank.refreshBalance(),
          ]));

      expect(observer.pushed.where((r) => r == '/login'), hasLength(1));
      _expectSignedOut(observer);
      expect(observer.pushedArgs[observer.pushed.indexOf('/login')], sessionExpiredArg);
    });
  }

  testWidgets('concurrent 401 events show exactly one expiry snackbar on the real LoginScreen', (tester) async {
    await _seedSession(accessToken: token);
    final observer = await _pumpApp(tester, login: (_) => const LoginScreen());
    final bank = BankService();

    await _with401(tester, () => Future.wait([
          bank.syncKycStatus('u1'),
          bank.refreshBalance(),
          bank.syncKycStatus('u1'),
          bank.refreshBalance(),
          bank.syncKycStatus('u1'),
        ]), settle: false);
    await tester.pump(const Duration(milliseconds: 500));

    expect(observer.pushed.where((r) => r == '/login'), hasLength(1));
    expect(find.text('Your session has expired. Please sign in again.'), findsOneWidget);
  });

  testWidgets('SessionGuard.checkExpiry redirects to /login on expired token with no refresh token', (tester) async {
    await _seedSession(accessToken: _jwt(expSecondsFromNow: -60));
    final observer = await _pumpApp(tester);

    await tester.runAsync(SessionGuard.checkExpiry);
    await tester.pumpAndSettle();

    _expectSignedOut(observer);
  });
}
