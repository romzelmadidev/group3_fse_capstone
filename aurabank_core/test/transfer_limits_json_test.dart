// Feature: kyc-tiered-transfer-limits, Property 4: Remaining amount and snapshot shape
//
// Client side of Property 4: parsing the limits endpoint JSON keeps every field,
// remaining = max(0, cap - used) is never negative, and fromJson(toJson(x)) is
// equivalent to x. Seeded randomized loop, no PBT library.
//
// **Validates: Requirements 6.1**
import 'dart:math';

import 'package:aurabank_core/models/bank_models.dart';
import 'package:flutter_test/flutter_test.dart';

const _iterations = 200;

double _money(Random r, int maxCents) => r.nextInt(maxCents + 1) / 100.0;

String _resetsAt(Random r) {
  final y = 2024 + r.nextInt(4);
  final mo = r.nextInt(12) + 1;
  final d = r.nextInt(28) + 1;
  final h = r.nextInt(24);
  final mi = r.nextInt(60);
  final s = r.nextInt(60);
  String p(int v) => v.toString().padLeft(2, '0');
  final frac = r.nextBool() ? '.${r.nextInt(1000).toString().padLeft(3, '0')}' : '';
  return '$y-${p(mo)}-${p(d)}T${p(h)}:${p(mi)}:${p(s)}$frac+08:00';
}

/// One window as the endpoint emits it. [withRemaining] false drops the field to
/// exercise the client fallback; used may exceed cap.
Map<String, dynamic> _window(Random r, {required bool withRemaining}) {
  final cap = _money(r, 100000000);
  final used = r.nextInt(5) == 0 ? cap + _money(r, 500000) : _money(r, 100000000);
  return {
    'cap': cap,
    'used': used,
    if (withRemaining) 'remaining': max(0.0, cap - used),
    'resets_at': _resetsAt(r),
  };
}

Map<String, dynamic> _snapshot(Random r) {
  final withRemaining = r.nextBool();
  return {
    'tier': r.nextBool() ? 'BASIC' : 'FULLY_VERIFIED',
    'restricted': r.nextBool(),
    'kyc_status': ['PENDING', 'PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'APPROVED'][r.nextInt(5)],
    'currency': 'PHP',
    'per_transfer_cap': _money(r, 100000000),
    'daily': _window(r, withRemaining: withRemaining),
    'monthly': _window(r, withRemaining: withRemaining),
  };
}

void _expectWindow(LimitWindow w, Map<String, dynamic> json) {
  final cap = (json['cap'] as num).toDouble();
  final used = (json['used'] as num).toDouble();
  expect(w.cap, cap);
  expect(w.used, used);
  expect(w.remaining, max(0.0, cap - used));
  expect(w.remaining, greaterThanOrEqualTo(0));
  expect(w.resetsAt, isNotNull);
  expect(w.resetsAt!.isAtSameMomentAs(DateTime.parse(json['resets_at'] as String)), isTrue);
}

void _expectEquivalentWindow(LimitWindow a, LimitWindow b) {
  expect(b.cap, a.cap);
  expect(b.used, a.used);
  expect(b.remaining, a.remaining);
  expect(a.resetsAt!.isAtSameMomentAs(b.resetsAt!), isTrue);
}

void main() {
  test('Feature: kyc-tiered-transfer-limits, Property 4: fromJson parses every field and remaining is never negative', () {
    final r = Random(0);
    for (var i = 0; i < _iterations; i++) {
      final json = _snapshot(r);
      final limits = TransferLimits.fromJson(json);

      expect(limits.tier, json['tier'], reason: 'iteration $i');
      expect(limits.restricted, json['restricted'], reason: 'iteration $i');
      expect(limits.kycStatus, json['kyc_status'], reason: 'iteration $i');
      expect(limits.currency, json['currency'], reason: 'iteration $i');
      expect(limits.perTransferCap, json['per_transfer_cap'], reason: 'iteration $i');
      _expectWindow(limits.dailyWindow, json['daily'] as Map<String, dynamic>);
      _expectWindow(limits.monthlyWindow, json['monthly'] as Map<String, dynamic>);
    }
  });

  test('Feature: kyc-tiered-transfer-limits, Property 4: a negative remaining in the payload is clamped to 0', () {
    final r = Random(0);
    for (var i = 0; i < _iterations; i++) {
      final cap = _money(r, 1000000);
      final w = LimitWindow.fromJson({
        'cap': cap,
        'used': cap + 1 + _money(r, 1000000),
        'remaining': -(1 + _money(r, 1000000)),
        'resets_at': _resetsAt(r),
      });
      expect(w.remaining, 0.0, reason: 'iteration $i');
    }
  });

  test('Feature: kyc-tiered-transfer-limits, Property 4: fromJson(toJson(x)) is equivalent to x', () {
    final r = Random(0);
    for (var i = 0; i < _iterations; i++) {
      final original = TransferLimits.fromJson(_snapshot(r));
      final copy = TransferLimits.fromJson(original.toJson());

      expect(copy.tier, original.tier, reason: 'iteration $i');
      expect(copy.restricted, original.restricted, reason: 'iteration $i');
      expect(copy.kycStatus, original.kycStatus, reason: 'iteration $i');
      expect(copy.currency, original.currency, reason: 'iteration $i');
      expect(copy.perTransferCap, original.perTransferCap, reason: 'iteration $i');
      _expectEquivalentWindow(original.dailyWindow, copy.dailyWindow);
      _expectEquivalentWindow(original.monthlyWindow, copy.monthlyWindow);
    }
  });

  test('Feature: kyc-tiered-transfer-limits, Property 4: toJson emits the endpoint shape', () {
    final r = Random(0);
    for (var i = 0; i < _iterations; i++) {
      final out = TransferLimits.fromJson(_snapshot(r)).toJson();
      expect(
        out.keys.toSet(),
        {'tier', 'restricted', 'kyc_status', 'currency', 'per_transfer_cap', 'daily', 'monthly'},
      );
      for (final k in ['daily', 'monthly']) {
        final w = out[k] as Map<String, dynamic>;
        expect(w.keys.toSet(), {'cap', 'used', 'remaining', 'resets_at'});
        expect(w['resets_at'], isA<String>());
      }
    }
  });
}
