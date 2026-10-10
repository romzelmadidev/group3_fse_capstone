import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:aurabank_core/models/notification_model.dart';
import 'package:aurabank_core/services/notification_stream_service.dart';
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
