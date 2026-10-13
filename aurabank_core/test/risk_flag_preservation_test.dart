// Preservation tests for the risk-engine high-risk flag fix (Property 2).
//
// Observed on UNFIXED code and expected to keep passing after the fix. They only
// use the public HTTP-facing API of BankService, so they compile before
// effectiveRiskResult exists.
import 'dart:convert';
import 'dart:math';

import 'package:aurabank_core/services/bank_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Future<Map<String, dynamic>> _analyze(
  http.Response Function(http.Request) reply, {
  bool remote = false,
  bool call = false,
  bool pasted = false,
}) {
  return http.runWithClient(
    () => BankService().analyzeTransferRisk(
      targetAccount: '1000-2000-3002',
      amount: 500,
      remoteAppActive: remote ? true : null,
      isOnCall: call ? true : null,
      isPasted: pasted ? true : null,
    ),
    () => MockClient((req) async => reply(req)),
  );
}

Future<Map<String, dynamic>> _analyzeBody(Map<String, dynamic> body) =>
    _analyze((_) => http.Response(jsonEncode(body), 200));

http.Response _unreachable(http.Request _) => throw Exception('network down');

Future<Map<String, dynamic>> _execute(BankService bank, Map<String, dynamic> body, double amount) {
  return http.runWithClient(
    () => bank.executeTransfer(
      targetAccount: '1000-2000-3002',
      recipientName: 'Test Payee',
      amount: amount,
      destinationBank: 'AuraBank',
    ),
    () => MockClient((_) async => http.Response(jsonEncode(body), 200)),
  );
}

int _txnCount(BankService bank) => bank.statements['2026-10']?.transactions.length ?? 0;

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('3.2 valid 200 ALLOW is returned unchanged', () {
    test('ALLOW, score < 40, advisory_tier NONE', () async {
      final body = {
        'decision': 'ALLOW',
        'fraud_score': 12,
        'primary_flag': 'NONE',
        'advisory_tier': 'NONE',
      };
      expect(await _analyzeBody(body), body);
    });
  });

  group('3.6 responses whose decision is already the highest tier are unchanged', () {
    test('seeded loop over BLOCK / REQUIRE_2FA / STEP_UP / ADVISORY_WARNING', () async {
      final rng = Random(0);
      final decisions = ['BLOCK', 'REQUIRE_2FA', 'STEP_UP', 'ADVISORY_WARNING'];
      for (var i = 0; i < 20; i++) {
        final d = decisions[i % decisions.length];
        final body = {
          'decision': d,
          'fraud_score': d == 'REQUIRE_2FA' ? 40 + rng.nextInt(61) : rng.nextInt(101),
          'primary_flag': 'X',
          'advisory_tier': d == 'ADVISORY_WARNING' ? 'ADVISORY_WARNING' : 'NONE',
        };
        expect(await _analyzeBody(body), body, reason: 'iteration $i');
      }
    });

    test('Gate 0 REQUIRE_2FA shape with score 75 stays REQUIRE_2FA', () async {
      final body = {'decision': 'REQUIRE_2FA', 'fraud_score': 75, 'advisory_tier': 'NONE'};
      expect(await _analyzeBody(body), body);
    });
  });

  group('3.3 local advisories when the service is unreachable', () {
    test('remote app -> ADVISORY_WARNING 85', () async {
      final r = await _analyze(_unreachable, remote: true);
      expect(r['decision'], 'ADVISORY_WARNING');
      expect(r['fraud_score'], 85);
      expect(r['primary_flag'], 'REMOTE_SCREEN_SHARE');
    });

    test('active call -> ADVISORY_WARNING 85', () async {
      final r = await _analyze(_unreachable, call: true);
      expect(r['decision'], 'ADVISORY_WARNING');
      expect(r['fraud_score'], 85);
      expect(r['primary_flag'], 'ACTIVE_VOICE_CALL_COERCION');
    });

    test('pasted payee -> ADVISORY_WARNING 70', () async {
      final r = await _analyze((_) => http.Response('boom', 500), pasted: true);
      expect(r['decision'], 'ADVISORY_WARNING');
      expect(r['fraud_score'], 70);
      expect(r['primary_flag'], 'EXTERNAL_CLIPBOARD_PASTE');
    });
  });

  // 3.4: the forced block/advisory override lives in the private _RiskScanDialog of
  // aurabank_app, which aurabank_core tests cannot reach. What BankService owns is the
  // scenario's request flags and the local advisories, which are pinned here.
  group('3.4 simulation scenarios (BankService side)', () {
    Future<Map<String, dynamic>> sentPayload({
      bool? remote,
      bool? hooking,
      bool? rooted,
      bool emulator = false,
      bool? call,
      bool? pasted,
      List<String>? pkgs,
    }) async {
      late Map<String, dynamic> sent;
      await http.runWithClient(
        () => BankService().analyzeTransferRisk(
          targetAccount: '1000-2000-3002',
          amount: 500,
          remoteAppActive: remote,
          hooking: hooking,
          rooted: rooted,
          isEmulator: emulator,
          isOnCall: call,
          isPasted: pasted,
          runningPackages: pkgs,
        ),
        () => MockClient((req) async {
          sent = jsonDecode(req.body) as Map<String, dynamic>;
          return http.Response(jsonEncode({'decision': 'ALLOW', 'fraud_score': 5, 'advisory_tier': 'NONE'}), 200);
        }),
      );
      return sent;
    }

    test('REMOTE / ANYDESK send remote_app_active; ANYDESK adds its package', () async {
      expect((await sentPayload(remote: true))['remote_app_active'], true);
      final p = await sentPayload(remote: true, pkgs: ['com.anydesk.anydeskandroid']);
      expect(p['remote_app_active'], true);
      expect(p['device_context']['running_packages'], ['com.anydesk.anydeskandroid']);
    });

    test('HOOKING and BLOCK scenarios send hooking / rooted / emulator', () async {
      expect((await sentPayload(hooking: true))['hooking'], true);
      final p = await sentPayload(rooted: true, emulator: true);
      expect(p['rooted'], true);
      expect(p['emulator'], true);
    });

    test('CALL and SCAM scenarios send active_call / is_pasted', () async {
      expect((await sentPayload(call: true))['active_call'], true);
      expect((await sentPayload(pasted: true))['is_pasted'], true);
    });

    test('CANARY scenario sends its package in device_context', () async {
      final p = await sentPayload(pkgs: ['com.guoshi.httpcanary']);
      expect(p['device_context']['running_packages'], ['com.guoshi.httpcanary']);
    });

    test('remote / call / scam scenarios stay advisory when the service is down', () async {
      for (final r in [
        await _analyze(_unreachable, remote: true),
        await _analyze(_unreachable, call: true),
        await _analyze(_unreachable, pasted: true),
      ]) {
        expect(r['decision'], 'ADVISORY_WARNING');
      }
    });
  });

  group('3.7 executeTransfer receipts', () {
    test('PENDING_APPROVAL + risk_decision REQUIRE_2FA is success and held', () async {
      final bank = BankService();
      final before = bank.availableBalance;
      final txns = _txnCount(bank);
      final r = await _execute(
        bank,
        {'status': 'PENDING_APPROVAL', 'risk_decision': 'REQUIRE_2FA', 'balance_after': before - 1000},
        1000,
      );
      expect(r['success'], true);
      expect(r['status'], 'PENDING_APPROVAL');
      expect(r['risk_decision'], 'REQUIRE_2FA');
      // Funds are reserved (balance follows the server figure) but no settled
      // transaction is recorded locally.
      expect(bank.availableBalance, before - 1000);
      expect(_txnCount(bank), txns);
    });

    test('COMMITTED debits exactly once', () async {
      final bank = BankService();
      final before = bank.availableBalance;
      final txns = _txnCount(bank);
      final r = await _execute(bank, {'status': 'COMMITTED', 'risk_decision': 'ALLOW'}, 1000);
      expect(r['success'], true);
      expect(bank.availableBalance, before - 1000);
      expect(_txnCount(bank), txns + 1);
    });
  });

  group('3.6 orchestrator-shaped reply', () {
    test('Processing + biometricRequired:false returns success:false', () async {
      final bank = BankService();
      final before = bank.availableBalance;
      final r = await _execute(
        bank,
        {'status': 'Processing', 'biometricRequired': false, 'message': 'SCAM_ADVISORY: please confirm'},
        1000,
      );
      expect(r['success'], false);
      expect(bank.availableBalance, before);
    });
  });
}
