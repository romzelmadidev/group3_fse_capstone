import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:aurabank_core/models/notification_model.dart';
import 'package:aurabank_core/services/notification_stream_service.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/widgets/notification_center_modal.dart';
import 'package:aurabank_app/screens/home/home_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('AppNotification Model Tests', () {
    test('Correctly serializes and deserializes AppNotification', () {
      final now = DateTime.now();
      final original = AppNotification(
        id: 'NOTIF-TEST-1',
        title: 'New Desktop Login',
        message: 'Chrome on Windows logged into your account.',
        category: NotificationCategory.session,
        severity: NotificationSeverity.warning,
        timestamp: now,
        isRead: false,
        metadata: {'clientIp': '192.168.1.5'},
      );

      final json = original.toJson();
      expect(json['id'], 'NOTIF-TEST-1');
      expect(json['category'], 'session');
      expect(json['severity'], 'warning');

      final restored = AppNotification.fromJson(json);
      expect(restored.id, original.id);
      expect(restored.title, original.title);
      expect(restored.category, NotificationCategory.session);
      expect(restored.severity, NotificationSeverity.warning);
      expect(restored.metadata['clientIp'], '192.168.1.5');
    });

    test('Friendly timeAgo calculates correctly', () {
      final now = DateTime.now();
      final notifJustNow = AppNotification(
        id: '1',
        title: 'T',
        message: 'M',
        category: NotificationCategory.security,
        timestamp: now.subtract(const Duration(seconds: 10)),
      );
      expect(notifJustNow.timeAgo, 'Just now');

      final notifMins = AppNotification(
        id: '2',
        title: 'T',
        message: 'M',
        category: NotificationCategory.transfer,
        timestamp: now.subtract(const Duration(minutes: 5)),
      );
      expect(notifMins.timeAgo, '5m ago');

      final notifHours = AppNotification(
        id: '3',
        title: 'T',
        message: 'M',
        category: NotificationCategory.session,
        timestamp: now.subtract(const Duration(hours: 3)),
      );
      expect(notifHours.timeAgo, '3h ago');
    });
  });

  group('NotificationStreamService Integration Tests', () {
    late NotificationStreamService service;

    setUp(() {
      service = NotificationStreamService();
      service.clearAll();
    });

    test('postNotification prepends notifications and increments unread count', () {
      expect(service.notifications.length, 0);
      expect(service.unreadCount, 0);

      service.notifyTransfer(
        amount: 2500.0,
        recipient: 'Maria Santos',
        reference: 'FT-991283',
        isIncoming: true,
      );

      expect(service.notifications.length, 1);
      expect(service.unreadCount, 1);
      expect(service.notifications.first.category, NotificationCategory.transfer);
      expect(service.notifications.first.title, 'Funds Received');
    });

    test('notifySessionAlert records session category alert', () {
      service.notifySessionAlert(
        title: 'New Desktop Session',
        message: 'Chrome logged in from 10.0.0.1',
        deviceName: 'Chrome on Windows',
        clientIp: '10.0.0.1',
      );

      expect(service.notifications.length, 1);
      final item = service.notifications.first;
      expect(item.category, NotificationCategory.session);
      expect(item.severity, NotificationSeverity.warning);
      expect(item.metadata['deviceName'], 'Chrome on Windows');
    });

    test('notifySecurityAlert records security category warning', () {
      service.notifySecurityAlert(
        title: 'Screen Recording Warning',
        message: 'Screen sharing software detected.',
        severity: NotificationSeverity.danger,
      );

      expect(service.notifications.length, 1);
      final item = service.notifications.first;
      expect(item.category, NotificationCategory.security);
      expect(item.severity, NotificationSeverity.danger);
    });

    test('markAsRead and markAllAsRead update read status', () {
      service.notifyTransfer(amount: 100, recipient: 'A', reference: 'R1');
      service.notifySessionAlert(title: 'S', message: 'M', deviceName: 'D');

      expect(service.unreadCount, 2);

      service.markAsRead(service.notifications.first.id);
      expect(service.unreadCount, 1);

      service.markAllAsRead();
      expect(service.unreadCount, 0);
    });

    test('injectAlert presents review alert on primary device and informational notice on secondary device', () {
      service.clearAll();
      AuthApiService().currentAccessToken = 'mock.jwt';

      // 1. Primary device receives full actionable alert
      AuthApiService().currentIsPrimaryDevice = true;
      service.injectAlert(SecurityAlertEvent(
        title: 'Security Alert: Desktop Login Request',
        message: 'A desktop/web session (Chrome) is requesting access.',
        deviceName: 'Chrome on Windows',
        clientIp: '192.168.1.10',
        timestamp: DateTime.now().toIso8601String(),
        targetDeviceId: 'dev-primary-mobile',
        notificationId: 'SEC-TEST-PRIMARY-1',
        status: 'PENDING_APPROVAL',
      ));

      expect(service.notifications.length, 1);
      expect(service.notifications.first.title, 'Security Alert: Desktop Login Request');
      expect(service.notifications.first.metadata['isCallerPrimary'], true);

      // 2. Secondary device receives informational advisory
      AuthApiService().currentIsPrimaryDevice = false;
      service.injectAlert(SecurityAlertEvent(
        title: 'Security Alert: Desktop Login Request',
        message: 'A desktop/web session (Chrome) is requesting access.',
        deviceName: 'Chrome on Windows',
        clientIp: '192.168.1.10',
        timestamp: DateTime.now().toIso8601String(),
        targetDeviceId: 'dev-primary-mobile',
        notificationId: 'SEC-TEST-SECONDARY-1',
        status: 'PENDING_APPROVAL',
      ));

      expect(service.notifications.length, 2);
      expect(service.notifications.first.title, 'Security Notice: New Login Attempt');
      expect(service.notifications.first.message, contains('primary mobile device'));
      expect(service.notifications.first.metadata['isCallerPrimary'], false);

      AuthApiService().currentIsPrimaryDevice = null;
    });

    test('postNotification suppresses duplicate banners for identical notification ID', () async {
      service.clearAll();
      final bannerEvents = <AppNotification>[];
      final sub = service.bannerStream.listen((n) => bannerEvents.add(n));

      final notif = AppNotification(
        id: 'NOTIF-DUP-TEST-1',
        title: 'Security Alert: New Device',
        message: 'A new device logged in.',
        category: NotificationCategory.security,
        timestamp: DateTime.now(),
      );

      service.postNotification(notif, showBanner: true);
      service.postNotification(notif, showBanner: true);
      await Future.delayed(const Duration(milliseconds: 10));

      expect(service.notifications.length, 1);
      expect(bannerEvents.length, 1);
      await sub.cancel();
    });

    test('fetchHistory populates notifications and emits banner for recent alert', () async {
      service.clearAll();
      final bannerEvents = <AppNotification>[];
      final sub = service.bannerStream.listen((n) => bannerEvents.add(n));

      final now = DateTime.now();
      service.httpClient = MockClient((request) async {
        if (request.url.path.contains('/notifications/history')) {
          final historyData = [
            {
              'notificationId': 'NOTIF-RECENT-SEC-1',
              'type': 'SECURITY_ALERT',
              'message': 'A desktop/web session is requesting access.',
              'sentAt': now.subtract(const Duration(seconds: 15)).toIso8601String(),
            },
            {
              'notificationId': 'NOTIF-OLD-SEC-2',
              'type': 'SECURITY_ALERT',
              'message': 'Older alert from hours ago.',
              'sentAt': now.subtract(const Duration(hours: 3)).toIso8601String(),
            },
          ];
          return http.Response(jsonEncode(historyData), 200, headers: {'content-type': 'application/json'});
        }
        return http.Response('[]', 200);
      });

      await service.fetchHistory('usr-test');
      await Future.delayed(const Duration(milliseconds: 10));

      expect(service.notifications.length, 2);
      expect(bannerEvents.length, 1);
      expect(bannerEvents.first.id, 'NOTIF-RECENT-SEC-1');
      service.httpClient = null;
      await sub.cancel();
    });

    test('device approval event posts notification for primary device', () async {
      service.clearAll();
      AuthApiService().currentIsPrimaryDevice = true;

      service.injectDeviceApprovalEvent({
        'type': 'DEVICE_APPROVED',
        'user_id': 'USR-100001',
        'device_id': 'laptop-chrome-12345',
        'notification_id': 'DEV-APPRV-999',
        'timestamp': DateTime.now().toIso8601String(),
      });

      expect(service.notifications.length, 1);
      expect(service.notifications.first.title, 'Device Authorized');
      expect(service.notifications.first.message, contains('laptop-chrome-12345'));
      AuthApiService().currentIsPrimaryDevice = null;
    });
  });

  group('Notification UI Widget Tests', () {
    late NotificationStreamService service;

    setUp(() {
      service = NotificationStreamService();
      service.clearAll();
      service.notifyTransfer(
        amount: 4500.0,
        recipient: 'Juan Dela Cruz',
        reference: 'FT-88219',
        isIncoming: false,
      );
      service.notifySessionAlert(
        title: 'Desktop Session Active',
        message: 'Active desktop session on Chrome.',
        deviceName: 'Chrome on MacOS',
      );
      service.notifySecurityAlert(
        title: 'Root Detection Check',
        message: 'Hardware integrity passed.',
        severity: NotificationSeverity.success,
      );
    });

    testWidgets('NotificationCenterModal renders tabs and lists items', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: NotificationCenterModal(),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Notifications'), findsOneWidget);
      expect(find.textContaining('All'), findsOneWidget);
      expect(find.textContaining('Transfers'), findsOneWidget);
      expect(find.textContaining('Sessions'), findsOneWidget);
      expect(find.textContaining('Security'), findsOneWidget);

      expect(find.text('Fund Transfer Settled'), findsOneWidget);
      expect(find.text('Desktop Session Active'), findsOneWidget);
      expect(find.text('Root Detection Check'), findsOneWidget);

      // Filter by Transfers tab
      await tester.tap(find.textContaining('Transfers'));
      await tester.pumpAndSettle();

      expect(find.text('Fund Transfer Settled'), findsOneWidget);
      expect(find.text('Desktop Session Active'), findsNothing);
      expect(find.text('Root Detection Check'), findsNothing);
    });

    testWidgets('NotificationCenterModal test simulator buttons trigger notifications', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(
            body: NotificationCenterModal(showTestSimulator: true),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Tap Show test controls
      await tester.tap(find.text('Show'));
      await tester.pumpAndSettle();

      expect(find.text('Session'), findsOneWidget);
      expect(find.text('Transfer'), findsOneWidget);
      expect(find.text('Security'), findsOneWidget);

      final prevCount = service.notifications.length;
      await tester.tap(find.text('Session'));
      await tester.pumpAndSettle();

      expect(service.notifications.length, prevCount + 1);
    });

    testWidgets('HomeScreen notification bell opens NotificationCenterModal', (tester) async {
      tester.view.physicalSize = const Size(800, 1200);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(() {
        tester.view.resetPhysicalSize();
        tester.view.resetDevicePixelRatio();
      });

      await tester.pumpWidget(
        MaterialApp(
          home: HomeScreen(onNavigateTab: (_) {}),
        ),
      );
      await tester.pumpAndSettle();

      final bell = find.byTooltip('Notifications');
      expect(bell, findsOneWidget);

      await tester.tap(bell);
      await tester.pumpAndSettle();

      expect(find.text('Notifications'), findsOneWidget);
    });
  });
}
