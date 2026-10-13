import 'package:aurabank_app/screens/transfer/review_transfer_screen.dart';
import 'package:aurabank_app/screens/transfer/send_money_screen.dart';
import 'package:aurabank_app/services/auth_api_service.dart';
import 'package:aurabank_app/services/bank_service.dart';
import 'package:aurabank_core/models/bank_models.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

// BASIC tier: per-transfer 5,000, daily remaining 3,200.50 (lowest), monthly remaining 42,000.
TransferLimits _limits({bool restricted = false, String tier = 'BASIC'}) {
  return TransferLimits(
    tier: tier,
    restricted: restricted,
    kycStatus: tier == 'BASIC' ? 'PENDING' : 'VERIFIED',
    currency: 'PHP',
    perTransferCap: 5000,
    dailyWindow: const LimitWindow(cap: 10000, used: 6799.5, remaining: 3200.5),
    monthlyWindow: const LimitWindow(cap: 50000, used: 8000, remaining: 42000),
  );
}

const _overDailyAmount = 4000.0; // above daily remaining (3,200.50), below per-transfer cap
const _withinAmount = 1000.0;

Future<void> _setLargeViewport(WidgetTester tester) async {
  // flutter_test renders text with the Ahem font (every glyph is a full-em square),
  // which is far wider than the real font, so use a wide logical viewport (1000 x 4000)
  // to avoid RenderFlex overflow. This is a test-font artifact, not a screen defect.
  tester.view.physicalSize = const Size(2000, 8000);
  tester.view.devicePixelRatio = 2.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

Future<void> _pumpSend(WidgetTester tester) async {
  await _setLargeViewport(tester);
  await tester.pumpWidget(const MaterialApp(home: SendMoneyScreen()));
  // Let the post-frame loadLimits (only when limits are null) settle.
  await tester.pump();
  await tester.pump();
}

Future<void> _pumpReview(WidgetTester tester, double amount) async {
  await _setLargeViewport(tester);
  await tester.pumpWidget(MaterialApp(
    home: ReviewTransferScreen(
      senderName: 'Test Sender',
      senderAccount: 'AUR-SAV-9821',
      recipientName: 'Test Recipient',
      recipientAccount: '1234568898951',
      recipientBank: 'Aura Bank',
      amount: amount,
    ),
  ));
  await tester.pump();
}

Finder get _amountField => find.byWidgetPredicate(
      (w) => w is TextField && w.decoration?.hintText == '0.00',
    );

Finder get _confirmButton => find.widgetWithText(ElevatedButton, 'Confirm & Transfer');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final bank = BankService();
  final auth = AuthApiService();

  void resetState() {
    bank.limits = null;
    bank.limitsLoading = false;
    bank.limitsError = false;
    auth.currentAccessToken = null;
    auth.currentIsApproved = null;
  }

  setUp(() {
    resetState();
    // Approved device so RequireDeviceApproval renders its child as-is.
    auth.currentIsApproved = true;
  });
  tearDown(resetState);

  group('SendMoneyScreen transfer limits', () {
    testWidgets('shows the lowest remaining under the amount card (8.1)', (tester) async {
      bank.limits = _limits();

      await _pumpSend(tester);

      expect(bank.limits!.lowestRemaining, 3200.5);
      expect(find.text('You can send up to ₱3,200.50'), findsOneWidget);
    });

    testWidgets('shows the check(amount) breach message after entering an amount above a cap (8.2)',
        (tester) async {
      bank.limits = _limits();
      final breach = bank.limits!.check(_overDailyAmount)!;

      await _pumpSend(tester);

      // Nothing is breached before an amount is entered.
      expect(find.text(breach.message), findsNothing);

      await tester.enterText(_amountField, '4000');
      await tester.pump();

      expect(breach.capKind, TransferLimits.daily);
      expect(find.text(breach.message), findsOneWidget);
      // The lowest-remaining hint stays visible.
      expect(find.text('You can send up to ₱3,200.50'), findsOneWidget);
    });

    testWidgets('shows no breach message for an amount within all caps (8.2)', (tester) async {
      bank.limits = _limits();
      final overMessage = bank.limits!.check(_overDailyAmount)!.message;

      await _pumpSend(tester);
      await tester.enterText(_amountField, '4000');
      await tester.pump();
      expect(find.text(overMessage), findsOneWidget);

      await tester.enterText(_amountField, '1000');
      await tester.pump();

      expect(bank.limits!.check(_withinAmount), isNull);
      expect(find.text(overMessage), findsNothing);
      expect(find.byIcon(Icons.error_outline), findsNothing);
    });

    testWidgets('shows no limits block when limits are null (8.7)', (tester) async {
      expect(bank.limits, isNull);

      await _pumpSend(tester);
      await tester.enterText(_amountField, '999999');
      await tester.pump();

      expect(bank.limits, isNull);
      expect(find.textContaining('You can send up to'), findsNothing);
      expect(find.textContaining('limit of'), findsNothing);
      expect(find.byIcon(Icons.error_outline), findsNothing);
    });
  });

  group('ReviewTransferScreen transfer limits', () {
    testWidgets('Confirm & Transfer is disabled and the breach message is shown above it (8.3)',
        (tester) async {
      bank.limits = _limits();
      final breach = bank.limits!.check(_overDailyAmount)!;

      await _pumpReview(tester, _overDailyAmount);

      expect(_confirmButton, findsOneWidget);
      expect(tester.widget<ElevatedButton>(_confirmButton).onPressed, isNull);

      final message = find.text(breach.message);
      expect(message, findsOneWidget);
      // "Above the button": the message sits higher on screen than the button.
      expect(tester.getBottomLeft(message).dy, lessThan(tester.getTopLeft(_confirmButton).dy));
    });

    testWidgets('Confirm & Transfer is enabled with no breach message when within limits (8.3)',
        (tester) async {
      bank.limits = _limits();
      expect(bank.limits!.check(_withinAmount), isNull);

      await _pumpReview(tester, _withinAmount);

      expect(tester.widget<ElevatedButton>(_confirmButton).onPressed, isNotNull);
      expect(find.byIcon(Icons.error_outline_rounded), findsNothing);
      expect(find.textContaining('limit of'), findsNothing);
    });

    testWidgets('Confirm & Transfer is enabled when limits are null (8.7)', (tester) async {
      expect(bank.limits, isNull);

      await _pumpReview(tester, 999999);

      expect(tester.widget<ElevatedButton>(_confirmButton).onPressed, isNotNull);
      expect(find.byIcon(Icons.error_outline_rounded), findsNothing);
    });

    testWidgets('button state follows limits changes notified by BankService (8.3)', (tester) async {
      bank.limits = _limits();
      await _pumpReview(tester, _overDailyAmount);
      expect(tester.widget<ElevatedButton>(_confirmButton).onPressed, isNull);

      // Fully verified caps are high enough that the same amount is allowed.
      bank.limits = TransferLimits(
        tier: 'FULLY_VERIFIED',
        restricted: false,
        kycStatus: 'VERIFIED',
        currency: 'PHP',
        perTransferCap: 50000,
        dailyWindow: const LimitWindow(cap: 100000, used: 0, remaining: 100000),
        monthlyWindow: const LimitWindow(cap: 500000, used: 0, remaining: 500000),
      );
      bank.notifyListeners();
      await tester.pump();

      expect(tester.widget<ElevatedButton>(_confirmButton).onPressed, isNotNull);
    });
  });
}
