import 'package:aurabank_core/models/bank_models.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:aurabank_web/screens/web/dashboard/web_dashboard_screen.dart';
import 'package:aurabank_web/screens/web/profile/web_profile_screen.dart';
import 'package:aurabank_web/screens/web/transfer/web_transfer_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

TransferLimits _mockLimits({
  String tier = 'FULLY_VERIFIED',
  bool restricted = false,
  double perTransfer = 50000.0,
  double dailyCap = 100000.0,
  double dailyRemaining = 88100.0,
  double monthlyCap = 500000.0,
  double monthlyRemaining = 488100.0,
}) {
  return TransferLimits(
    tier: tier,
    restricted: restricted,
    kycStatus: tier == 'BASIC' ? 'PENDING' : 'VERIFIED',
    currency: 'PHP',
    perTransferCap: perTransfer,
    dailyWindow: LimitWindow(
      cap: dailyCap,
      used: dailyCap - dailyRemaining,
      remaining: dailyRemaining,
    ),
    monthlyWindow: LimitWindow(
      cap: monthlyCap,
      used: monthlyCap - monthlyRemaining,
      remaining: monthlyRemaining,
    ),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final bank = BankService();
  final auth = AuthApiService();

  setUp(() {
    bank.limits = null;
    bank.limitsLoading = false;
    bank.limitsError = false;
    auth.currentAccessToken = null;
  });

  tearDown(() {
    bank.limits = null;
    bank.limitsLoading = false;
    bank.limitsError = false;
    auth.currentAccessToken = null;
  });

  group('WebProfileScreen Transfer Limits', () {
    testWidgets('renders Transfer Limits navigation item and rail card', (tester) async {
      tester.view.physicalSize = const Size(1920, 1080);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      bank.limits = _mockLimits();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: WebProfileScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Transfer Limits'), findsOneWidget);
      expect(find.text('TRANSFER LIMITS'), findsOneWidget);
      expect(find.text('Daily remaining'), findsOneWidget);
      expect(find.text('₱88,100.00'), findsOneWidget);
    });

    testWidgets('switching to Transfer Limits section displays full metrics and tier table', (tester) async {
      tester.view.physicalSize = const Size(1920, 1080);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      bank.limits = _mockLimits();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: WebProfileScreen()),
        ),
      );
      await tester.pumpAndSettle();

      // Tap on the Transfer Limits navigation item
      await tester.tap(find.text('Transfer Limits'));
      await tester.pumpAndSettle();

      expect(find.text('Transfer Limits & Tiering'), findsOneWidget);
      expect(find.text('Fully Verified'), findsWidgets);
      expect(find.text('Per-Transaction Limit'), findsOneWidget);
      expect(find.text('₱50,000.00'), findsWidgets);
      expect(find.text('Daily Remaining'), findsOneWidget);
      expect(find.text('₱88,100.00'), findsWidgets);
      expect(find.text('Monthly Remaining'), findsOneWidget);
      expect(find.text('₱488,100.00'), findsOneWidget);
      expect(find.text('Tier Comparison & Privileges'), findsOneWidget);
      expect(find.text('CURRENT TIER'), findsOneWidget);
    });

    testWidgets('BASIC tier displays Basic Tier badge and upgrade promotion', (tester) async {
      tester.view.physicalSize = const Size(1920, 1080);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      bank.limits = _mockLimits(
        tier: 'BASIC',
        perTransfer: 10000.0,
        dailyCap: 20000.0,
        dailyRemaining: 15000.0,
      );

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: WebProfileScreen()),
        ),
      );
      await tester.pumpAndSettle();

      // Tap to switch to Transfer Limits section
      await tester.tap(find.text('Transfer Limits'));
      await tester.pumpAndSettle();

      expect(find.text('Basic Tier'), findsWidgets);
      expect(find.text('Upgrade to Fully Verified for higher allowances'), findsOneWidget);
      expect(find.text('₱10,000.00'), findsWidgets);
    });
  });

  group('WebTransferScreen Limits Banner', () {
    testWidgets('renders transfer limits indicator in sidebar', (tester) async {
      tester.view.physicalSize = const Size(1920, 1080);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      bank.limits = _mockLimits();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: WebTransferScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('TRANSFER LIMIT'), findsOneWidget);
      expect(find.text('Fully Verified'), findsOneWidget);
      expect(find.text('PHP 88,100.00'), findsOneWidget);
      expect(find.text('PHP 50,000.00'), findsOneWidget);
    });

    testWidgets('breach warning appears if entered amount exceeds per-transfer cap', (tester) async {
      tester.view.physicalSize = const Size(1920, 1080);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      bank.limits = _mockLimits(perTransfer: 50000.0, dailyRemaining: 88100.0);

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: WebTransferScreen()),
        ),
      );
      await tester.pumpAndSettle();

      // Find the amount text field and enter 60000
      final amountField = find.byType(TextField).at(2);
      await tester.enterText(amountField, '60000');
      await tester.pumpAndSettle();

      expect(find.textContaining('This transfer is above your per-transfer limit of PHP 50,000.00'), findsOneWidget);
    });
  });

  group('WebDashboardScreen Transfer Limits KPI', () {
    testWidgets('renders Daily Limit Left KPI tile on the dashboard', (tester) async {
      tester.view.physicalSize = const Size(1920, 1080);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      bank.limits = _mockLimits();

      await tester.pumpWidget(
        const MaterialApp(
          home: Scaffold(body: WebDashboardScreen()),
        ),
      );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));

      expect(find.text('Daily Limit Left'), findsOneWidget);
      expect(find.text('₱88,100.00'), findsOneWidget);
      expect(find.text('Fully Verified'), findsOneWidget);
    });
  });
}
