// Bug condition exploration tests for the risk-engine high-risk flag fix.
//
// Assertions encode the EXPECTED (fixed) behaviour, so they are expected to FAIL
// on unfixed code. Covers defects 1.2, 1.3, 1.4 (via the HTTP path of
// analyzeTransferRisk) and 1.5 (executeTransfer). Direct tests of the
// effectiveRiskResult helper live in risk_flag_effective_result_bug_test.dart,
// because that helper does not exist yet and a missing symbol would stop this
// file from compiling.
import 'dart:convert';
import 'dart:math';

import 'package:aurabank_core/services/bank_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Future<Map<String, dynamic>> _analyze(http.Response Function(http.Request) reply) {
  return http.runWithClient(
    () => BankService().analyzeTransferRisk(targetAccount: '1000-2000-3002', amount: 500),
    () => MockClient((req) async => reply(req)),
  );
}

Future<Map<String, dynamic>> _analyzeWithBody(Map<String, dynamic> body) =>
    _analyze((_) => http.Response(jsonEncode(body), 200));

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('1.2 fail-open on failed risk call', () {
    test('HTTP 500 with no local signal is not ALLOW', () async {
      final r = await _analyze((_) => http.Response('boom', 500));
      expect(r['decision'], isNot('ALLOW'), reason: 'got $r');
    });

    test('thrown exception with no local signal is not ALLOW', () async {
      final r = await http.runWithClient(
        () => BankService().analyzeTransferRisk(targetAccount: '1000-2000-3002', amount: 500),
        () => MockClient((_) async => throw Exception('network down')),
      );
      expect(r['decision'], isNot('ALLOW'), reason: 'got $r');
    });
  });

  group('1.3 fraud_score ignored when decision is ALLOW', () {
    test('score 82 with ALLOW is BLOCK', () async {
      final r = await _analyzeWithBody({'decision': 'ALLOW', 'fraud_score': 82});
      expect(r['decision'], 'BLOCK', reason: 'got $r');
    });

    test('scores 40-49 with ALLOW are at least REQUIRE_2FA', () async {
      final rng = Random(0);
      final scores = {40, 49, for (var i = 0; i < 5; i++) 40 + rng.nextInt(10)};
      for (final s in scores) {
        final r = await _analyzeWithBody({'decision': 'ALLOW', 'fraud_score': s});
        expect(r['decision'], anyOf('REQUIRE_2FA', 'STEP_UP', 'BLOCK'), reason: 'score $s -> $r');
      }
    });
  });

  group('1.4 decision string not normalized / advisory_tier ignored', () {
    test("' require_2fa ' normalizes to REQUIRE_2FA", () async {
      final r = await _analyzeWithBody({'decision': ' require_2fa ', 'fraud_score': 45});
      expect(r['decision'], 'REQUIRE_2FA', reason: 'got $r');
    });

    test('ALLOW + advisory_tier ADVISORY_WARNING returns ADVISORY_WARNING', () async {
      final r = await _analyzeWithBody({
        'decision': 'ALLOW',
        'fraud_score': 10,
        'advisory_tier': 'ADVISORY_WARNING',
      });
      expect(r['decision'], 'ADVISORY_WARNING', reason: 'got $r');
    });
  });

  group('1.5 ledger ADVISORY_WARNING reply treated as success', () {
    test('snake_case ADVISORY_WARNING returns success:false and leaves balance unchanged', () async {
      final bank = BankService();
      final before = bank.availableBalance;
      final r = await http.runWithClient(
        () => bank.executeTransfer(
          targetAccount: '1000-2000-3002',
          recipientName: 'Test Payee',
          amount: 1000,
          destinationBank: 'AuraBank',
        ),
        () => MockClient((_) async => http.Response(
              jsonEncode({'status': 'ADVISORY_WARNING', 'risk_decision': 'ADVISORY_WARNING'}),
              200,
            )),
      );
      expect(r['success'], false, reason: 'got $r');
      expect(bank.availableBalance, before);
    });
  });
}
