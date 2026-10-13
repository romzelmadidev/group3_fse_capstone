// Property 13 for the KYC tiered transfer limits feature: syncKycStatus reloads
// the transfer limits only when the KYC status changes.
//
// Seeded randomized loops over plain flutter_test (no PBT library), driven by a
// counting MockClient behind http.runWithClient.
//
// **Validates: Requirements 9.1, 9.2**
import 'dart:convert';
import 'dart:math';

import 'package:aurabank_core/models/bank_models.dart';
import 'package:aurabank_core/services/auth_api_service.dart';
import 'package:aurabank_core/services/bank_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

const _statuses = ['PENDING', 'PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'APPROVED'];

/// A distinct instance per call so identity tells "retained" from "reloaded".
TransferLimits _sentinel() => TransferLimits(
      tier: 'BASIC',
      restricted: false,
      kycStatus: 'PENDING',
      currency: 'PHP',
      perTransferCap: 5000,
      dailyWindow: const LimitWindow(cap: 10000, used: 0, remaining: 10000),
      monthlyWindow: const LimitWindow(cap: 50000, used: 0, remaining: 50000),
    );

Map<String, dynamic> _limitsBody(String kycStatus) => {
      'tier': kycStatus == 'VERIFIED' || kycStatus == 'APPROVED' ? 'FULLY_VERIFIED' : 'BASIC',
      'restricted': false,
      'kyc_status': kycStatus,
      'currency': 'PHP',
      'per_transfer_cap': 5000.0,
      'daily': {'cap': 10000.0, 'used': 0.0, 'remaining': 10000.0},
      'monthly': {'cap': 50000.0, 'used': 0.0, 'remaining': 50000.0},
    };

/// Runs one syncKycStatus call against a client that answers GET /api/v1/kyc/{id}
/// with [serverStatus] and counts GET /api/v1/accounts/me/limits requests.
Future<int> _sync(BankService bank, String serverStatus) async {
  var limitsRequests = 0;
  await http.runWithClient(
    () => bank.syncKycStatus('u1'),
    () => MockClient((req) async {
      if (req.method == 'GET' && req.url.path == '/api/v1/kyc/u1') {
        return http.Response(jsonEncode({'kyc_status': serverStatus}), 200);
      }
      if (req.method == 'GET' && req.url.path == '/api/v1/accounts/me/limits') {
        limitsRequests++;
        return http.Response(jsonEncode(_limitsBody(serverStatus)), 200);
      }
      return http.Response('unexpected ${req.method} ${req.url}', 500);
    }),
  );
  return limitsRequests;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final bank = BankService();

  setUp(() {
    AuthApiService().currentAccessToken = 'test-token';
  });

  tearDown(() {
    AuthApiService().currentAccessToken = null;
  });

  // Feature: kyc-tiered-transfer-limits, Property 13: Limits refresh only when KYC status changes
  group('Property 13: Limits refresh only when KYC status changes', () {
    test('limits requests equal the number of status changes; limits retained when unchanged', () async {
      final rng = Random(0);
      var totalChanges = 0;
      var totalUnchanged = 0;

      for (var seq = 0; seq < 100; seq++) {
        var previous = _statuses[rng.nextInt(_statuses.length)];
        bank.user.kycStatus = previous;
        bank.limits = _sentinel();

        final length = 1 + rng.nextInt(12);
        var expectedRequests = 0;
        var actualRequests = 0;

        for (var step = 0; step < length; step++) {
          // Bias toward repeats so the unchanged branch is exercised often.
          final next = rng.nextInt(3) == 0 ? previous : _statuses[rng.nextInt(_statuses.length)];
          final before = bank.limits;

          final requests = await _sync(bank, next);
          actualRequests += requests;

          final reason = 'seq $seq step $step: $previous -> $next';
          expect(bank.user.kycStatus, next, reason: reason);
          if (next != previous) {
            expectedRequests++;
            totalChanges++;
            expect(requests, 1, reason: 'one limits request on change, $reason');
            expect(identical(bank.limits, before), isFalse, reason: 'limits reloaded on change, $reason');
            expect(bank.limits!.kycStatus, next, reason: 'reloaded limits are the new ones, $reason');
          } else {
            totalUnchanged++;
            expect(requests, 0, reason: 'no limits request when unchanged, $reason');
            expect(identical(bank.limits, before), isTrue, reason: 'limits retained when unchanged, $reason');
          }
          previous = next;
        }

        expect(actualRequests, expectedRequests, reason: 'seq $seq total limits requests');
      }

      // Guard against a degenerate generator.
      expect(totalChanges, greaterThan(100));
      expect(totalUnchanged, greaterThan(100));
    });

    test('first sync with the same status as user.kycStatus does not request limits', () async {
      for (final status in _statuses) {
        bank.user.kycStatus = status;
        final loaded = _sentinel();
        bank.limits = loaded;
        expect(await _sync(bank, status), 0, reason: status);
        expect(identical(bank.limits, loaded), isTrue, reason: status);
      }
    });
  });
}
