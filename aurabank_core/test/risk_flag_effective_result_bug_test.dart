// Direct bug condition tests for the effectiveRiskResult helper (defects 1.3, 1.4).
// The helper does not exist on unfixed code, so this file fails to compile there.
// That is the expected red state.
import 'dart:math';

import 'package:aurabank_core/services/bank_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('ALLOW with fraud_score 82 returns BLOCK (1.3)', () {
    expect(effectiveRiskResult({'decision': 'ALLOW', 'fraud_score': 82})['decision'], 'BLOCK');
  });

  test('ALLOW with fraud_score 40-49 returns at least REQUIRE_2FA (1.3)', () {
    final rng = Random(0);
    final scores = {40, 49, for (var i = 0; i < 5; i++) 40 + rng.nextInt(10)};
    for (final s in scores) {
      final d = effectiveRiskResult({'decision': 'ALLOW', 'fraud_score': s})['decision'];
      expect(d, anyOf('REQUIRE_2FA', 'STEP_UP', 'BLOCK'), reason: 'score $s');
    }
  });

  test("' require_2fa ' normalizes to REQUIRE_2FA (1.4)", () {
    expect(effectiveRiskResult({'decision': ' require_2fa '})['decision'], 'REQUIRE_2FA');
  });

  test('ALLOW + advisory_tier ADVISORY_WARNING returns ADVISORY_WARNING (1.4)', () {
    final r = effectiveRiskResult({
      'decision': 'ALLOW',
      'fraud_score': 10,
      'advisory_tier': 'ADVISORY_WARNING',
    });
    expect(r['decision'], 'ADVISORY_WARNING');
  });
}
