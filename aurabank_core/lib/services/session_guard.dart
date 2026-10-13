import 'dart:async';
import 'package:flutter/widgets.dart';
import 'auth_api_service.dart';

/// Re-checks access token expiry on app resume and every 30 seconds so an idle
/// app does not keep showing authenticated screens after the session ends.
class SessionGuard with WidgetsBindingObserver {
  SessionGuard._();
  static final SessionGuard _instance = SessionGuard._();

  Timer? _timer;

  /// Starts the guard. Calling it again has no effect.
  static void start() {
    if (_instance._timer != null) return;
    WidgetsBinding.instance.addObserver(_instance);
    _instance._timer = Timer.periodic(const Duration(seconds: 30), (_) => checkExpiry());
  }

  /// Does nothing without a session. Refreshes an expired token, or ends the session.
  static Future<void> checkExpiry() async {
    final auth = AuthApiService();
    if (auth.isAccessTokenExpired) await auth.recoverFromUnauthorized();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) checkExpiry();
  }
}
