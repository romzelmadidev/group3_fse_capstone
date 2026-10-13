// Feature: kyc-tiered-transfer-limits, Property 11: Client check matches the server rule
//
// For any TransferLimits and any amount, lowestRemaining is the minimum of the
// per-transfer cap and the daily and monthly remaining amounts. check(amount) returns
// a breach iff the server rule rejects the amount, with the same capKind. The message
// names the cap, and includes the KYC line iff the tier is BASIC and not restricted.
// When limits are null, check is never reached and there is no breach.
//
// Validates: Requirements 8.1, 8.2, 8.4, 8.7
//
// Seeded randomized loops, no PBT library. Amounts are whole numbers so double
// arithmetic is exact and boundary equality is meaningful.
import 'dart:math';

import 'package:aurabank_core/models/bank_models.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:flutter_test/flutter_test.dart';

const _iterations = 500;
const _kycLine = 'Complete KYC verification to raise your limits.';

/// Picks a value biased toward boundaries: zero, small, and the given anchors.
double _boundaryCap(Random rng) {
  const anchors = <double>[0, 1, 5000, 10000, 50000, 100000, 500000];
  return rng.nextInt(3) == 0
      ? rng.nextInt(600001).toDouble()
      : anchors[rng.nextInt(anchors.length)];
}

/// Used amount biased toward 0, exactly the cap, just over the cap, and random.
double _boundaryUsed(Random rng, double cap) {
  switch (rng.nextInt(5)) {
    case 0:
      return 0;
    case 1:
      return cap;
    case 2:
      return cap + 1 + rng.nextInt(1000);
    case 3:
      return cap == 0 ? 0 : rng.nextInt(cap.toInt() + 1).toDouble();
    default:
      return cap == 0 ? 0 : (cap - 1).clamp(0, cap).toDouble();
  }
}

LimitWindow _window(Random rng) {
  final cap = _boundaryCap(rng);
  final used = _boundaryUsed(rng, cap);
  return LimitWindow(cap: cap, used: used, remaining: max(0.0, cap - used));
}

TransferLimits _genLimits(Random rng) {
  final tier = rng.nextBool() ? 'BASIC' : 'FULLY_VERIFIED';
  // Restricted roughly one time in five so the other branches get most of the volume.
  final restricted = rng.nextInt(5) == 0;
  return TransferLimits(
    tier: tier,
    restricted: restricted,
    kycStatus: tier == 'BASIC' ? 'PENDING' : 'VERIFIED',
    currency: 'PHP',
    perTransferCap: _boundaryCap(rng),
    dailyWindow: _window(rng),
    monthlyWindow: _window(rng),
  );
}

/// Amounts equal to, just below and just above every cap and remaining value, plus random.
List<double> _amountsFor(TransferLimits l, Random rng) {
  final anchors = <double>[
    l.perTransferCap,
    l.dailyWindow.cap,
    l.dailyWindow.remaining,
    l.monthlyWindow.cap,
    l.monthlyWindow.remaining,
    l.lowestRemaining,
  ];
  final out = <double>[];
  for (final a in anchors) {
    out..add(a)..add(a - 1)..add(a + 1);
  }
  out
    ..add(1)
    ..add(rng.nextInt(700001).toDouble() + 1)
    ..add(rng.nextInt(10) + 1);
  return out.where((a) => a > 0).toList();
}

/// Independent oracle for the server rule. Returns the breached capKind or null.
String? _oracleBreach(TransferLimits l, double amount) {
  if (l.restricted) return TransferLimits.perTransfer;
  final caps = <(String, double)>[
    (TransferLimits.perTransfer, l.perTransferCap),
    (TransferLimits.daily, max(0.0, l.dailyWindow.cap - l.dailyWindow.used)),
    (TransferLimits.monthly, max(0.0, l.monthlyWindow.cap - l.monthlyWindow.used)),
  ];
  final breached = caps.where((c) => amount > c.$2).toList();
  if (breached.isEmpty) return null;
  // Lowest remaining wins; List order PER_TRANSFER, DAILY, MONTHLY breaks ties
  // because the comparison below is strict.
  var best = breached.first;
  for (final c in breached.skip(1)) {
    if (c.$2 < best.$2) best = c;
  }
  return best.$1;
}

String _label(String capKind) => switch (capKind) {
      TransferLimits.perTransfer => 'per-transfer',
      TransferLimits.daily => 'daily',
      _ => 'monthly',
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Property 11: Client check matches the server rule', () {
    test('lowestRemaining equals min(per-transfer cap, daily remaining, monthly remaining)', () {
      final rng = Random(0);
      for (var i = 0; i < _iterations; i++) {
        final l = _genLimits(rng);
        final expected = [l.perTransferCap, l.dailyWindow.remaining, l.monthlyWindow.remaining]
            .reduce(min);
        expect(l.lowestRemaining, expected, reason: 'iteration $i');
      }
    });

    test('check returns a breach iff the server rule rejects, with the same capKind', () {
      final rng = Random(0);
      var breaches = 0;
      var allowed = 0;
      for (var i = 0; i < _iterations; i++) {
        final l = _genLimits(rng);
        for (final amount in _amountsFor(l, rng)) {
          final expectedKind = _oracleBreach(l, amount);
          final actual = l.check(amount);
          final reason = 'iteration $i amount=$amount tier=${l.tier} restricted=${l.restricted} '
              'perTransfer=${l.perTransferCap} daily=${l.dailyWindow.remaining} '
              'monthly=${l.monthlyWindow.remaining}';
          if (expectedKind == null) {
            allowed++;
            expect(actual, isNull, reason: reason);
          } else {
            breaches++;
            expect(actual, isNotNull, reason: reason);
            expect(actual!.capKind, expectedKind, reason: reason);
          }
        }
      }
      // The generators must exercise both outcomes.
      expect(breaches, greaterThan(100));
      expect(allowed, greaterThan(100));
    });

    test('breach carries the cap value and remaining of the named cap', () {
      final rng = Random(0);
      for (var i = 0; i < _iterations; i++) {
        final l = _genLimits(rng);
        if (l.restricted) continue;
        for (final amount in _amountsFor(l, rng)) {
          final b = l.check(amount);
          if (b == null) continue;
          final (cap, remaining) = switch (b.capKind) {
            TransferLimits.perTransfer => (l.perTransferCap, l.perTransferCap),
            TransferLimits.daily => (l.dailyWindow.cap, l.dailyWindow.remaining),
            _ => (l.monthlyWindow.cap, l.monthlyWindow.remaining),
          };
          expect(b.capValue, cap, reason: 'iteration $i amount=$amount');
          expect(b.remaining, remaining, reason: 'iteration $i amount=$amount');
        }
      }
    });

    test('amounts equal to a cap or remaining are allowed; one more breaches', () {
      final rng = Random(0);
      for (var i = 0; i < _iterations; i++) {
        final base = _genLimits(rng);
        final l = TransferLimits(
          tier: base.tier,
          restricted: false,
          kycStatus: base.kycStatus,
          currency: base.currency,
          perTransferCap: base.perTransferCap,
          dailyWindow: base.dailyWindow,
          monthlyWindow: base.monthlyWindow,
        );
        final lowest = l.lowestRemaining;
        if (lowest > 0) {
          expect(l.check(lowest), isNull, reason: 'iteration $i lowest=$lowest');
        }
        expect(l.check(lowest + 1), isNotNull, reason: 'iteration $i lowest=$lowest');
      }
    });

    test('ties break PER_TRANSFER, then DAILY, then MONTHLY', () {
      LimitWindow w(double remaining) =>
          LimitWindow(cap: remaining, used: 0, remaining: remaining);
      TransferLimits make(double perTransfer, double daily, double monthly) => TransferLimits(
            tier: 'FULLY_VERIFIED',
            restricted: false,
            kycStatus: 'VERIFIED',
            currency: 'PHP',
            perTransferCap: perTransfer,
            dailyWindow: w(daily),
            monthlyWindow: w(monthly),
          );

      expect(make(100, 100, 100).check(101)!.capKind, TransferLimits.perTransfer);
      expect(make(500, 100, 100).check(101)!.capKind, TransferLimits.daily);
      expect(make(500, 500, 100).check(101)!.capKind, TransferLimits.monthly);
      expect(make(100, 100, 500).check(101)!.capKind, TransferLimits.perTransfer);
      expect(make(500, 100, 500).check(101)!.capKind, TransferLimits.daily);
    });

    test('restricted always breaches positive amounts, as PER_TRANSFER, with no KYC line', () {
      final rng = Random(0);
      for (var i = 0; i < _iterations; i++) {
        final base = _genLimits(rng);
        final l = TransferLimits(
          tier: base.tier,
          restricted: true,
          kycStatus: base.kycStatus,
          currency: base.currency,
          perTransferCap: base.perTransferCap,
          dailyWindow: base.dailyWindow,
          monthlyWindow: base.monthlyWindow,
        );
        for (final amount in [1.0, 0.01, base.perTransferCap + 1, rng.nextInt(1000000) + 1.0]) {
          final b = l.check(amount);
          expect(b, isNotNull, reason: 'iteration $i amount=$amount');
          expect(b!.capKind, TransferLimits.perTransfer);
          expect(b.message.toLowerCase(), contains('restricted'));
          expect(b.message, isNot(contains(_kycLine)));
        }
      }
    });

    test('message names the cap; KYC line appears iff tier is BASIC and not restricted', () {
      final rng = Random(0);
      var basicSeen = 0;
      var verifiedSeen = 0;
      for (var i = 0; i < _iterations; i++) {
        final l = _genLimits(rng);
        for (final amount in _amountsFor(l, rng)) {
          final b = l.check(amount);
          if (b == null) continue;
          final reason = 'iteration $i amount=$amount tier=${l.tier} restricted=${l.restricted}';
          if (l.restricted) {
            expect(b.message.toLowerCase(), contains('restricted'), reason: reason);
          } else {
            expect(b.message, contains(_label(b.capKind)), reason: reason);
          }
          final expectKyc = l.tier == 'BASIC' && !l.restricted;
          expect(b.message.contains(_kycLine), expectKyc, reason: reason);
          if (!l.restricted) {
            l.isBasic ? basicSeen++ : verifiedSeen++;
          }
        }
      }
      expect(basicSeen, greaterThan(50));
      expect(verifiedSeen, greaterThan(50));
    });

    test('non-positive amounts never breach', () {
      final rng = Random(0);
      for (var i = 0; i < _iterations; i++) {
        final l = _genLimits(rng);
        expect(l.check(0), isNull);
        expect(l.check(-1.0 - rng.nextInt(1000)), isNull);
      }
    });

    test('null limits: null-safe call path returns null (Req 8.7)', () {
      // BankService.limits is null until the first successful load.
      expect(BankService().limits, isNull);
      expect(BankService().limits?.check(1e12), isNull);
      expect(BankService().limits?.check(1), isNull);

      const TransferLimits? none = null;
      expect(none?.check(1e12), isNull);
    });
  });
}
