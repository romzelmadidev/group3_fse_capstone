// Feature: kyc-tiered-transfer-limits, Property 12: Typed error is surfaced and never masked
//
// Validates: Requirements 8.5, 4.10, 8.6
//
// For any valid Limit_Exceeded_Error body returned with status 402, executeTransfer
// returns success:false, a failureReason containing the server message (and any upgrade
// guidance), and a limitError map equal to the body fields, with no local debit and no
// further endpoints tried. A 503 with code TX_LIMIT_UNVERIFIED is surfaced the same way
// without the offline fallback. A real success is followed by a refresh of
// GET /api/v1/accounts/me/limits.
import 'dart:convert';
import 'dart:math';

import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

class _Run {
  _Run(this.result, this.requests, this.balanceBefore, this.balanceAfter);
  final Map<String, dynamic> result;
  final List<http.BaseRequest> requests;
  final double balanceBefore;
  final double balanceAfter;

  List<http.BaseRequest> get posts => requests.where((r) => r.method == 'POST').toList();
  List<http.BaseRequest> get limitGets =>
      requests.where((r) => r.method == 'GET' && r.url.path == '/api/v1/accounts/me/limits').toList();
}

Future<_Run> _execute(http.Response Function(http.Request) reply) async {
  final bank = BankService();
  final before = bank.availableBalance;
  final requests = <http.BaseRequest>[];
  final result = await http.runWithClient(
    () async {
      final r = await bank.executeTransfer(
        targetAccount: '1000-2000-3002',
        recipientName: 'Test Payee',
        amount: 1000,
        destinationBank: 'AuraBank',
      );
      // loadLimits() is fired without await; let it reach the client.
      await Future<void>.delayed(const Duration(milliseconds: 20));
      return r;
    },
    () => MockClient((req) async {
      requests.add(req);
      if (req.method == 'GET') return http.Response('{}', 500);
      return reply(req);
    }),
  );
  return _Run(result, requests, before, bank.availableBalance);
}

String _word(Random rng, int len) =>
    String.fromCharCodes(List.generate(len, (_) => 97 + rng.nextInt(26)));

Map<String, dynamic> _limitBody(Random rng) {
  final capKind = ['PER_TRANSFER', 'DAILY', 'MONTHLY'][rng.nextInt(3)];
  final capValue = (rng.nextInt(50000000) + 100) / 100;
  final remaining = rng.nextInt(capValue * 100 > 1 ? (capValue * 100).toInt() : 1) / 100;
  final tier = rng.nextBool() ? 'BASIC' : 'FULLY_VERIFIED';
  final restricted = rng.nextInt(5) == 0;
  return {
    'status': 402,
    'error': 'LIMIT_EXCEEDED',
    'code': 'TX_LIMIT_EXCEEDED',
    'message': 'Your $capKind limit of PHP $capValue was exceeded ${_word(rng, 1 + rng.nextInt(12))}.',
    'capKind': capKind,
    'capValue': capValue,
    'remaining': remaining,
    'tier': tier,
    'restricted': restricted,
    if (rng.nextBool()) 'upgradeGuidance': 'Verify your ID to raise your limit ${_word(rng, 1 + rng.nextInt(12))}.',
  };
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    // loadLimits() needs a token to issue its GET.
    AuthApiService().currentAccessToken = 'test-token';
  });

  tearDown(() {
    AuthApiService().currentAccessToken = null;
  });

  group('Property 12: typed error is surfaced and never masked', () {
    test('402 TX_LIMIT_EXCEEDED is surfaced as limitError (100+ random bodies)', () async {
      final rng = Random(0);
      for (var i = 0; i < 120; i++) {
        final body = _limitBody(rng);
        final run = await _execute((_) => http.Response(jsonEncode(body), 402));
        final r = run.result;
        final reason = 'iteration $i body=$body result=$r';

        expect(r['success'], false, reason: reason);
        expect(r['failureReason'], contains(body['message'] as String), reason: reason);
        if (body.containsKey('upgradeGuidance')) {
          expect(r['failureReason'], contains(body['upgradeGuidance'] as String), reason: reason);
        }

        final limitError = r['limitError'] as Map?;
        expect(limitError, isNotNull, reason: reason);
        expect(limitError, {
          'code': body['code'],
          'capKind': body['capKind'],
          'capValue': body['capValue'],
          'remaining': body['remaining'],
          'tier': body['tier'],
          'restricted': body['restricted'],
          'upgradeGuidance': body['upgradeGuidance'],
        }, reason: reason);

        // No offline fallback and no local debit.
        expect(run.balanceAfter, run.balanceBefore, reason: reason);
        // The 402 is final: exactly one transfer POST, no further candidates.
        expect(run.posts.length, 1, reason: reason);
      }
    });

    test('503 TX_LIMIT_UNVERIFIED fails closed with the server message (100+ random bodies)', () async {
      final rng = Random(0);
      for (var i = 0; i < 120; i++) {
        final message = 'Could not verify your limit ${_word(rng, 1 + rng.nextInt(16))}.';
        final body = {
          'status': 503,
          'error': 'LIMIT_UNVERIFIED',
          'code': 'TX_LIMIT_UNVERIFIED',
          'message': message,
        };
        final run = await _execute((_) => http.Response(jsonEncode(body), 503));
        final reason = 'iteration $i result=${run.result}';

        expect(run.result['success'], false, reason: reason);
        expect(run.result['failureReason'], message, reason: reason);
        expect(run.balanceAfter, run.balanceBefore, reason: reason);
        expect(run.posts.length, 1, reason: reason);
      }
    });

    test('503 without the limit code keeps the old behaviour (offline fallback)', () async {
      final run = await _execute((_) => http.Response(jsonEncode({'message': 'down'}), 503));
      expect(run.result['success'], true, reason: '${run.result}');
      expect(run.balanceAfter, run.balanceBefore - 1000);
      // Every candidate endpoint was tried before falling back.
      expect(run.posts.length, greaterThan(1));
    });
  });

  group('Req 8.6: limits refresh after a real success', () {
    test('2xx COMMITTED is followed by GET /api/v1/accounts/me/limits', () async {
      final run = await _execute((_) => http.Response(
            jsonEncode({
              'status': 'COMMITTED',
              'transactionId': 'TX-1',
              'balance_after': 4000.0,
            }),
            200,
          ));
      expect(run.result['success'], true, reason: '${run.result}');
      expect(run.posts.length, 1);
      expect(run.limitGets.length, 1);
    });
  });
}
