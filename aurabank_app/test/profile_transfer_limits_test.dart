import 'dart:async';
import 'dart:convert';

import 'package:aurabank_app/screens/profile/profile_screen.dart';
import 'package:aurabank_app/services/auth_api_service.dart';
import 'package:aurabank_app/services/bank_service.dart';
import 'package:aurabank_core/models/bank_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

const _kycPrompt = 'Complete KYC verification to raise your limits.';
const _restrictedMessage = 'Your account is restricted. Transfers are unavailable right now.';

TransferLimits _limits({
  String tier = 'BASIC',
  bool restricted = false,
  double perTransfer = 5000,
  double dailyCap = 10000,
  double dailyRemaining = 7500.5,
  double monthlyCap = 50000,
  double monthlyRemaining = 42000,
}) {
  return TransferLimits(
    tier: tier,
    restricted: restricted,
    kycStatus: tier == 'BASIC' ? 'PENDING' : 'VERIFIED',
    currency: 'PHP',
    perTransferCap: perTransfer,
    dailyWindow: LimitWindow(cap: dailyCap, used: dailyCap - dailyRemaining, remaining: dailyRemaining),
    monthlyWindow: LimitWindow(cap: monthlyCap, used: monthlyCap - monthlyRemaining, remaining: monthlyRemaining),
  );
}

Map<String, dynamic> _limitsJson() => _limits().toJson();

Future<void> _pumpProfile(WidgetTester tester) async {
  // flutter_test renders text with the Ahem font (every glyph is a full-em square),
  // which is far wider than the real font, so use a wide logical viewport (1000 x 4000)
  // to keep the limit rows from overflowing. This is a test-font artifact, not a card defect.
  tester.view.physicalSize = const Size(2000, 8000);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(const MaterialApp(home: ProfileScreen()));
  // Let the post-frame loadLimits (if any) and its mocked response settle.
  await tester.pump();
  await tester.pump();
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

  group('Profile Transfer limits card', () {
    testWidgets('shows a loading indicator while limits are being fetched (7.1)', (tester) async {
      auth.currentAccessToken = 'test-token';
      final gate = Completer<http.Response>();
      final client = MockClient((_) => gate.future);

      await http.runWithClient(() async {
        await _pumpProfile(tester);

        expect(find.text('Transfer limits'), findsOneWidget);
        expect(find.text('Loading your limits...'), findsOneWidget);
        expect(find.byType(CircularProgressIndicator), findsWidgets);
        expect(find.text('Per transfer'), findsNothing);
        expect(find.text('Retry'), findsNothing);

        // Release the pending request so no timeout timer is left behind.
        gate.complete(http.Response(jsonEncode(_limitsJson()), 200));
        await tester.pump();
        await tester.pump();
      }, () => client);

      expect(find.text('Loading your limits...'), findsNothing);
      expect(find.text('Per transfer'), findsOneWidget);
    });

    testWidgets('BASIC shows tier, per-transfer cap, daily/monthly remaining and cap, and KYC prompt (7.2, 7.3)',
        (tester) async {
      bank.limits = _limits();

      await _pumpProfile(tester);

      expect(find.text('Transfer limits'), findsOneWidget);
      expect(find.text('Basic'), findsOneWidget);
      expect(find.text('Per transfer'), findsOneWidget);
      expect(find.text('₱5,000.00'), findsOneWidget);
      expect(find.text('Daily remaining'), findsOneWidget);
      expect(find.text('₱7,500.50 of ₱10,000.00'), findsOneWidget);
      expect(find.text('Monthly remaining'), findsOneWidget);
      expect(find.text('₱42,000.00 of ₱50,000.00'), findsOneWidget);
      expect(find.text(_kycPrompt), findsOneWidget);
      expect(find.text('Retry'), findsNothing);
      expect(find.text('Loading your limits...'), findsNothing);
    });

    testWidgets('FULLY_VERIFIED shows values without the KYC prompt (7.2, 7.3)', (tester) async {
      bank.limits = _limits(
        tier: 'FULLY_VERIFIED',
        perTransfer: 50000,
        dailyCap: 100000,
        dailyRemaining: 80000,
        monthlyCap: 500000,
        monthlyRemaining: 420000.25,
      );

      await _pumpProfile(tester);

      expect(find.text('Fully Verified'), findsOneWidget);
      expect(find.text('Basic'), findsNothing);
      expect(find.text('₱50,000.00'), findsOneWidget);
      expect(find.text('₱80,000.00 of ₱100,000.00'), findsOneWidget);
      expect(find.text('₱420,000.25 of ₱500,000.00'), findsOneWidget);
      expect(find.text(_kycPrompt), findsNothing);
      expect(find.text('Retry'), findsNothing);
    });

    testWidgets('restricted account shows the transfers unavailable message instead of values (7.5)',
        (tester) async {
      bank.limits = _limits(
        restricted: true,
        perTransfer: 0,
        dailyCap: 0,
        dailyRemaining: 0,
        monthlyCap: 0,
        monthlyRemaining: 0,
      );

      await _pumpProfile(tester);

      expect(find.text('Restricted'), findsOneWidget);
      expect(find.text(_restrictedMessage), findsOneWidget);
      expect(find.text('Per transfer'), findsNothing);
      expect(find.text('Daily remaining'), findsNothing);
      expect(find.text('Monthly remaining'), findsNothing);
      expect(find.text(_kycPrompt), findsNothing);
    });

    testWidgets('error shows a Retry action instead of values, and Retry calls loadLimits (7.4)',
        (tester) async {
      auth.currentAccessToken = 'test-token';
      var requests = 0;
      final client = MockClient((request) async {
        expect(request.url.path, '/api/v1/accounts/me/limits');
        requests++;
        // First load fails; the retry succeeds.
        return requests == 1
            ? http.Response('boom', 500)
            : http.Response(jsonEncode(_limitsJson()), 200, headers: {'content-type': 'application/json'});
      });

      await http.runWithClient(() async {
        await _pumpProfile(tester);

        expect(requests, 1);
        expect(bank.limitsError, isTrue);
        expect(find.text("We couldn't load your transfer limits."), findsOneWidget);
        expect(find.text('Retry'), findsOneWidget);
        expect(find.text('Per transfer'), findsNothing);
        expect(find.text('Daily remaining'), findsNothing);
        expect(find.text('Monthly remaining'), findsNothing);

        await tester.tap(find.text('Retry'));
        await tester.pump();
        await tester.pump();

        expect(requests, 2);
        expect(bank.limitsError, isFalse);
        expect(bank.limits, isNotNull);
        expect(find.text('Retry'), findsNothing);
        expect(find.text('Per transfer'), findsOneWidget);
        expect(find.text('₱5,000.00'), findsOneWidget);
      }, () => client);
    });

    testWidgets('Retry that fails again keeps showing the retry action (7.4)', (tester) async {
      auth.currentAccessToken = 'test-token';
      var requests = 0;
      final client = MockClient((_) async {
        requests++;
        return http.Response('unavailable', 503);
      });

      await http.runWithClient(() async {
        await _pumpProfile(tester);
        expect(requests, 1);

        await tester.tap(find.text('Retry'));
        await tester.pump();
        await tester.pump();

        expect(requests, 2);
        expect(find.text('Retry'), findsOneWidget);
        expect(find.text('Per transfer'), findsNothing);
      }, () => client);
    });
  });
}
