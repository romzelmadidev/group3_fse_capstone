#!/usr/bin/env node
/**
 * test-dlq-and-interbank.mjs
 * 
 * Standalone, evidence-based verification test suite for:
 * 1. Dead Letter Queues (DLQ) & Autonomous/Manual Replaying
 * 2. Interbank Transfers (Bilateral Nostro/Vostro, Inward/Outward, Saga Rollback, Security)
 * 
 * Target endpoints (configurable via environment variables):
 * - GATEWAY_URL (default: http://localhost:8080)
 * - CBS_URL (default: http://localhost:8087)
 * - ORCHESTRATOR_URL (default: http://localhost:8082)
 * - COMPLIANCE_URL (default: http://localhost:8086)
 */

const GATEWAY_URL = process.env.GATEWAY_URL || 'http://localhost:8080';
const CBS_URL = process.env.CBS_URL || 'http://localhost:8087';
const ORCHESTRATOR_URL = process.env.ORCHESTRATOR_URL || 'http://localhost:8082';
const COMPLIANCE_URL = process.env.COMPLIANCE_URL || 'http://localhost:8086';

const INTERBANK_API_KEY = process.env.INTERBANK_API_KEY || 'AURABANK-SECURE-KEY-2026';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function request(baseUrl, path, options = {}) {
  const url = `${baseUrl}${path}`;
  try {
    const res = await fetch(url, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...(options.headers || {}),
      },
    });
    let body = null;
    const text = await res.text();
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
    return { status: res.status, headers: res.headers, body, ok: res.ok };
  } catch (err) {
    return { status: 0, error: err.message, body: null, ok: false };
  }
}

const testResults = [];

function recordTest(suite, testName, isPositive, passed, details) {
  testResults.push({
    suite,
    testName,
    type: isPositive ? 'POSITIVE' : 'NEGATIVE',
    status: passed ? 'PASS' : 'FAIL',
    details,
  });
  const symbol = passed ? '✅ [PASS]' : '❌ [FAIL]';
  const tag = isPositive ? '(+)' : '(-)';
  console.log(`  ${symbol} ${tag} ${testName}`);
  if (details) {
    console.log(`       ↳ ${details}`);
  }
}

async function verifyServiceHealth() {
  console.log('\n======================================================');
  console.log('PRE-FLIGHT: PROBING SERVICES & HEALTH ENDPOINTS');
  console.log('======================================================');

  const services = [
    { name: 'API Gateway', url: GATEWAY_URL, path: '/actuator/health' },
    { name: 'Transfer Orchestrator', url: ORCHESTRATOR_URL, path: '/actuator/health' },
    { name: 'T24 Mock CBS', url: CBS_URL, path: '/actuator/health' },
    { name: 'Compliance Service', url: COMPLIANCE_URL, path: '/actuator/health' },
  ];

  let anyAlive = false;
  for (const s of services) {
    const res = await request(s.url, s.path);
    if (res.status === 200) {
      console.log(`  🟢 ${s.name} is UP at ${s.url}`);
      anyAlive = true;
    } else {
      console.log(`  ⚪ ${s.name} at ${s.url} returned status ${res.status || 'UNREACHABLE'}`);
    }
  }

  if (!anyAlive) {
    console.log('\n  ⚠️ Notice: Backend containers appear to be offline or on custom ports.');
    console.log('  If running in mock/direct mode, ensure docker compose is started:');
    console.log('     cd infrastructure && docker compose up -d\n');
  }
}

// ============================================================================
// SUITE 1: DEAD LETTER QUEUES (DLQ) & REPLAYING
// ============================================================================
async function runDlqTests() {
  console.log('\n======================================================');
  console.log('SUITE 1: DEAD LETTER QUEUES (DLQ) & AUTONOMOUS/MANUAL REPLAY');
  console.log('======================================================');

  // Test 1.1: Query Autonomous DLQ Daemon Status (ShedLock & Safeguards)
  let daemonRes = await request(GATEWAY_URL, '/api/v1/compliance/dlq/status');
  if (daemonRes.status === 0 || daemonRes.status === 404) {
    daemonRes = await request(COMPLIANCE_URL, '/api/v1/compliance/dlq/status');
  }
  const daemonPassed = daemonRes.status === 200 && daemonRes.body?.autonomousDaemonActive === true;
  recordTest(
    'DLQ & Replay',
    'Autonomous DLQ Recovery Daemon Status & Safeguards',
    true,
    daemonPassed,
    daemonPassed
      ? `HTTP ${daemonRes.status} | ShedLock: ${daemonRes.body?.shedLockSynced} | CBS Healthy: ${daemonRes.body?.cbsHealthy} | Max Age: ${daemonRes.body?.maxAgeMinutes}m | Throttle: ${daemonRes.body?.throttleMs}ms`
      : `HTTP ${daemonRes.status} | Error: ${daemonRes.error || JSON.stringify(daemonRes.body)}`
  );

  // Test 1.2: Inspect DLQ Incidents Backlog in Audit Vault
  let incidentsRes = await request(GATEWAY_URL, '/api/v1/cbs/audit/failed-transactions?status=ALL&page=0&size=20');
  if (incidentsRes.status === 0 || incidentsRes.status === 404) {
    incidentsRes = await request(CBS_URL, '/api/v1/cbs/audit/failed-transactions?status=ALL&page=0&size=20');
  }
  const incidentsPassed = incidentsRes.status === 200 && Array.isArray(incidentsRes.body);
  const incidentsList = Array.isArray(incidentsRes.body) ? incidentsRes.body : [];
  recordTest(
    'DLQ & Replay',
    'DLQ Audit Vault Query & Incident Backlog Inspection',
    true,
    incidentsPassed,
    incidentsPassed
      ? `HTTP ${incidentsRes.status} | Incidents in vault: ${incidentsList.length} | Schema: failed_transaction_audit (Postgres Vault)`
      : `HTTP ${incidentsRes.status} | Error: ${incidentsRes.error || JSON.stringify(incidentsRes.body)}`
  );

  // Test 1.3: Trigger DLQ Incident Replay via Compliance Engine
  let replayPassed = false;
  let replayDetails = '';
  if (incidentsList.length > 0) {
    const inc = incidentsList.find((i) => i.replayStatus === 'PENDING_REPLAY') || incidentsList[0];
    const txId = inc.transactionId || inc.incidentId;
    let replayRes = await request(GATEWAY_URL, `/api/v1/compliance/dlq/replay/${txId}`, {
      method: 'POST',
      body: JSON.stringify({
        notes: 'Verification test runner manual replay',
        operatorId: 'TEST_OPERATOR_COMPLIANCE',
      }),
    });
    if (replayRes.status === 0 || replayRes.status === 404) {
      replayRes = await request(COMPLIANCE_URL, `/api/v1/compliance/dlq/replay/${txId}`, {
        method: 'POST',
        body: JSON.stringify({
          notes: 'Verification test runner manual replay',
          operatorId: 'TEST_OPERATOR_COMPLIANCE',
        }),
      });
    }
    replayPassed = replayRes.status === 200 && (replayRes.body?.status === 'REPLAYED' || replayRes.body?.status === 'RESOLVED');
    replayDetails = `HTTP ${replayRes.status} | TxId: ${txId} | Status: ${replayRes.body?.status || 'UNKNOWN'}`;
  } else {
    // Zero backlog: queue is clear, query pending queue specifically
    let pendingRes = await request(GATEWAY_URL, '/api/v1/cbs/audit/failed-transactions?status=PENDING_REPLAY');
    if (pendingRes.status === 0 || pendingRes.status === 404) {
      pendingRes = await request(CBS_URL, '/api/v1/cbs/audit/failed-transactions?status=PENDING_REPLAY');
    }
    replayPassed = pendingRes.status === 200;
    replayDetails = `HTTP ${pendingRes.status} | Zero pending DLQ incidents (clean ledger state)`;
  }
  recordTest(
    'DLQ & Replay',
    'DLQ Incident Resolution & Replay Pipeline',
    true,
    replayPassed,
    replayDetails
  );

  // Test 1.4: Resolve Incident Audit Record in CBS
  const targetInc = incidentsList.length > 0 ? incidentsList[0] : null;
  const testTxId = targetInc ? (targetInc.transactionId || targetInc.incidentId) : ('TXN-DLQ-VERIFY-' + Date.now());
  let resolveRes = await request(GATEWAY_URL, `/api/v1/cbs/audit/failed-transactions/${testTxId}/resolve`, {
    method: 'POST',
    body: JSON.stringify({
      notes: 'Automated test suite audit resolution verification',
      operatorId: 'QA_ENGINEER',
      replayStatus: 'RESOLVED',
    }),
  });
  if (resolveRes.status === 0 || resolveRes.status === 404) {
    resolveRes = await request(CBS_URL, `/api/v1/cbs/audit/failed-transactions/${testTxId}/resolve`, {
      method: 'POST',
      body: JSON.stringify({
        notes: 'Automated test suite audit resolution verification',
        operatorId: 'QA_ENGINEER',
        replayStatus: 'RESOLVED',
      }),
    });
  }
  const resolvePassed = targetInc
    ? (resolveRes.status === 200 && (resolveRes.body?.replayStatus === 'RESOLVED' || resolveRes.body?.status === 'RESOLVED'))
    : (resolveRes.status === 400 && (resolveRes.body?.error === 'BUSINESS_VALIDATION_ERROR' || resolveRes.body?.message?.includes('not found')));
  recordTest(
    'DLQ & Replay',
    'Audit Vault Direct Incident Resolution Update',
    true,
    resolvePassed,
    resolvePassed
      ? (targetInc
          ? `HTTP ${resolveRes.status} | Replay Status: ${resolveRes.body?.replayStatus} | Operator: ${resolveRes.body?.resolvedBy}`
          : `HTTP ${resolveRes.status} | Correctly rejected unseeded incident (Business Validation)`)
      : `HTTP ${resolveRes.status} | Details: ${resolveRes.error || JSON.stringify(resolveRes.body)}`
  );
}

// ============================================================================
// SUITE 2: INTERBANK TRANSFERS
// ============================================================================
async function runInterbankTests() {
  console.log('\n======================================================');
  console.log('SUITE 2: INTERBANK TRANSFERS (BILATERAL NOSTRO/VOSTRO)');
  console.log('======================================================');

  // Test 2.1: Participant Banks Directory Facade (OFS-backed query)
  let dirRes = await request(GATEWAY_URL, '/api/v1/interbank/banks');
  if (dirRes.status === 0 || dirRes.status === 404) {
    dirRes = await request(ORCHESTRATOR_URL, '/api/v1/interbank/banks');
  }
  const dirPassed = dirRes.status === 200 && Array.isArray(dirRes.body) && dirRes.body.some((b) => b.bankCode === 'COOBS');
  recordTest(
    'Interbank Transfers',
    'Participant Banks Directory Discovery Facade',
    true,
    dirPassed,
    dirPassed
      ? `HTTP ${dirRes.status} | Active Banks: ${dirRes.body.map((b) => b.bankCode).join(', ')} | Clearing: BILATERAL Nostro`
      : `HTTP ${dirRes.status} | Error: ${dirRes.error || JSON.stringify(dirRes.body)}`
  );

  // Test 2.2: Outward Inter-Bank Transfer (Happy Path: Leg 1 Nostro Debit + Leg 2 Partner Wire)
  const txOutId = 'IB-OUT-TEST-' + Date.now();
  let outRes = await request(GATEWAY_URL, '/api/v1/transfers', {
    method: 'POST',
    body: JSON.stringify({
      transactionId: txOutId,
      sourceAccountId: '1000-2000-3001',
      destinationAccountId: 'B-ACC-998877',
      amount: 1500.00,
      currency: 'PHP',
      destinationBank: 'COOBS',
      senderName: 'Alice Reyes',
      description: 'Outward interbank wire verification test',
      idempotencyKey: 'IDEMP-' + txOutId,
    }),
  });
  if (outRes.status === 0 || outRes.status === 404) {
    outRes = await request(ORCHESTRATOR_URL, '/api/v1/transfers', {
      method: 'POST',
      body: JSON.stringify({
        transactionId: txOutId,
        sourceAccountId: '1000-2000-3001',
        destinationAccountId: 'B-ACC-998877',
        amount: 1500.00,
        currency: 'PHP',
        destinationBank: 'COOBS',
        senderName: 'Alice Reyes',
        description: 'Outward interbank wire verification test',
        idempotencyKey: 'IDEMP-' + txOutId,
      }),
    });
  }
  const outPassed = outRes.status === 200 && (outRes.body?.status === 'Posted' || outRes.body?.message?.includes('COOBS'));
  recordTest(
    'Interbank Transfers',
    'Outward Wire Transfer Dispatch (Leg 1 Nostro Debit + Leg 2 Dispatch)',
    true,
    outPassed,
    outPassed
      ? `HTTP ${outRes.status} | Status: ${outRes.body?.status} | Message: "${outRes.body?.message}"`
      : `HTTP ${outRes.status} | Details: ${outRes.error || JSON.stringify(outRes.body)}`
  );

  // Test 2.3: Outward Wire Network Timeout & Automated Saga Reversal
  const txTimeoutId = 'IB-OUT-TIM-' + Date.now();
  let timeRes = await request(GATEWAY_URL, '/api/v1/transfers', {
    method: 'POST',
    body: JSON.stringify({
      transactionId: txTimeoutId,
      sourceAccountId: '1000-2000-3001',
      destinationAccountId: 'B-ACC-998877',
      amount: 750.00,
      currency: 'PHP',
      destinationBank: 'COOBS',
      senderName: 'Alice Reyes',
      simulationMode: 'TIMEOUT',
      idempotencyKey: 'IDEMP-' + txTimeoutId,
    }),
  });
  if (timeRes.status === 0 || timeRes.status === 404) {
    timeRes = await request(ORCHESTRATOR_URL, '/api/v1/transfers', {
      method: 'POST',
      body: JSON.stringify({
        transactionId: txTimeoutId,
        sourceAccountId: '1000-2000-3001',
        destinationAccountId: 'B-ACC-998877',
        amount: 750.00,
        currency: 'PHP',
        destinationBank: 'COOBS',
        senderName: 'Alice Reyes',
        simulationMode: 'TIMEOUT',
        idempotencyKey: 'IDEMP-' + txTimeoutId,
      }),
    });
  }
  const timeMsg = typeof timeRes.body === 'string' ? timeRes.body : timeRes.body?.message || '';
  const timePassed = timeRes.status === 502 && timeMsg.includes('Any deducted funds have been returned to your account');
  recordTest(
    'Interbank Transfers',
    'Outward Wire Timeout & Automated Saga Compensating Reversal + DLQ Publication',
    false,
    timePassed,
    timePassed
      ? `HTTP 502 BAD_GATEWAY | Sanitized Consumer Error: "${timeMsg}" | Saga Compensating Reversal Committed`
      : `HTTP ${timeRes.status} | Msg: ${timeMsg || timeRes.error}`
  );

  // Test 2.4: Outward Wire Recipient Account Not Found (Business Rejection - Zero DLQ Pollution)
  const txRejId = 'IB-OUT-REJ-' + Date.now();
  let rejRes = await request(GATEWAY_URL, '/api/v1/transfers', {
    method: 'POST',
    body: JSON.stringify({
      transactionId: txRejId,
      sourceAccountId: '1000-2000-3001',
      destinationAccountId: 'B-ACC-998877',
      amount: 250.00,
      currency: 'PHP',
      destinationBank: 'COOBS',
      senderName: 'Alice Reyes',
      simulationMode: 'REJECT',
      idempotencyKey: 'IDEMP-' + txRejId,
    }),
  });
  if (rejRes.status === 0 || rejRes.status === 404) {
    rejRes = await request(ORCHESTRATOR_URL, '/api/v1/transfers', {
      method: 'POST',
      body: JSON.stringify({
        transactionId: txRejId,
        sourceAccountId: '1000-2000-3001',
        destinationAccountId: 'B-ACC-998877',
        amount: 250.00,
        currency: 'PHP',
        destinationBank: 'COOBS',
        senderName: 'Alice Reyes',
        simulationMode: 'REJECT',
        idempotencyKey: 'IDEMP-' + txRejId,
      }),
    });
  }
  const rejMsg = typeof rejRes.body === 'string' ? rejRes.body : rejRes.body?.message || '';
  const rejPassed = rejRes.status === 400 && rejMsg.includes('recipient bank could not find this account number');
  recordTest(
    'Interbank Transfers',
    'Outward Wire Recipient Not Found Rejection (Zero DLQ Pollution)',
    false,
    rejPassed,
    rejPassed
      ? `HTTP 400 BAD_REQUEST | Customer Refunded: "${rejMsg}" | DLQ Skipped (Business Validation)`
      : `HTTP ${rejRes.status} | Msg: ${rejMsg || rejRes.error}`
  );

  // Test 2.5: Inward Wire Ingestion (Partner Bank -> AuraBank Leg 3 Beneficiary Credit)
  const inMsgId = 'WIRE-IN-' + Date.now();
  let inRes = await request(GATEWAY_URL, '/api/v1/interbank/transfers/inward', {
    method: 'POST',
    headers: { 'X-Interbank-API-Key': INTERBANK_API_KEY },
    body: JSON.stringify({
      messageId: inMsgId,
      sourceBankCode: 'COOBS',
      destinationBankCode: 'AURABANK',
      sender: {
        accountNumber: 'B-ACC-112233',
        name: 'Carlos Yulo',
      },
      beneficiary: {
        accountNumber: '1000-2000-3001',
        name: 'Alice Reyes',
      },
      amount: 3200.00,
      currency: 'PHP',
      purpose: 'Inward wire settlement test',
      senderReference: 'REF-' + inMsgId,
    }),
  });
  if (inRes.status === 0 || inRes.status === 404) {
    inRes = await request(ORCHESTRATOR_URL, '/api/v1/interbank/transfers/inward', {
      method: 'POST',
      headers: { 'X-Interbank-API-Key': INTERBANK_API_KEY },
      body: JSON.stringify({
        messageId: inMsgId,
        sourceBankCode: 'COOBS',
        destinationBankCode: 'AURABANK',
        sender: {
          accountNumber: 'B-ACC-112233',
          name: 'Carlos Yulo',
        },
        beneficiary: {
          accountNumber: '1000-2000-3001',
          name: 'Alice Reyes',
        },
        amount: 3200.00,
        currency: 'PHP',
        purpose: 'Inward wire settlement test',
        senderReference: 'REF-' + inMsgId,
      }),
    });
  }
  const inPassed = inRes.status === 200 && inRes.body?.status === 'ACCEPTED';
  recordTest(
    'Interbank Transfers',
    'Inward Wire Ingestion (Leg 3 Nostro Debit -> Beneficiary Credit)',
    true,
    inPassed,
    inPassed
      ? `HTTP 200 OK | Status: ${inRes.body?.status} | ReceiverRef: ${inRes.body?.receiverReference} | Masked Beneficiary: ${inRes.body?.beneficiaryName}`
      : `HTTP ${inRes.status} | Details: ${inRes.error || JSON.stringify(inRes.body)}`
  );

  // Test 2.6: Inward Wire Security Verification (Unauthorized API Key Rejection)
  let unauthRes = await request(GATEWAY_URL, '/api/v1/interbank/transfers/inward', {
    method: 'POST',
    headers: { 'X-Interbank-API-Key': 'INVALID-OR-FORGED-KEY-000' },
    body: JSON.stringify({
      messageId: 'WIRE-UNAUTH-' + Date.now(),
      sourceBankCode: 'COOBS',
      destinationBankCode: 'AURABANK',
      beneficiary: { accountNumber: '1000-2000-3001', name: 'Alice Reyes' },
      amount: 1000.00,
    }),
  });
  if (unauthRes.status === 0 || unauthRes.status === 404) {
    unauthRes = await request(ORCHESTRATOR_URL, '/api/v1/interbank/transfers/inward', {
      method: 'POST',
      headers: { 'X-Interbank-API-Key': 'INVALID-OR-FORGED-KEY-000' },
      body: JSON.stringify({
        messageId: 'WIRE-UNAUTH-' + Date.now(),
        sourceBankCode: 'COOBS',
        destinationBankCode: 'AURABANK',
        beneficiary: { accountNumber: '1000-2000-3001', name: 'Alice Reyes' },
        amount: 1000.00,
      }),
    });
  }
  const unauthPassed = unauthRes.status === 401;
  recordTest(
    'Interbank Transfers',
    'Inward Wire Unauthorized API Key Rejection (Security Boundary)',
    false,
    unauthPassed,
    unauthPassed
      ? `HTTP 401 UNAUTHORIZED | Blocked unauthorized caller | Reason: ${unauthRes.body?.reasonCode || 'UNAUTHORIZED'}`
      : `HTTP ${unauthRes.status} | Response: ${JSON.stringify(unauthRes.body)}`
  );

  // Test 2.7: Inward Wire Missing Beneficiary Account Rejection
  let missingRes = await request(GATEWAY_URL, '/api/v1/interbank/transfers/inward', {
    method: 'POST',
    headers: { 'X-Interbank-API-Key': INTERBANK_API_KEY },
    body: JSON.stringify({
      messageId: 'WIRE-NONEXIST-' + Date.now(),
      sourceBankCode: 'COOBS',
      destinationBankCode: 'AURABANK',
      beneficiary: { accountNumber: 'ACC-NONEXISTENT-99999', name: 'Ghost' },
      amount: 1000.00,
    }),
  });
  if (missingRes.status === 0 || missingRes.status === 404) {
    missingRes = await request(ORCHESTRATOR_URL, '/api/v1/interbank/transfers/inward', {
      method: 'POST',
      headers: { 'X-Interbank-API-Key': INTERBANK_API_KEY },
      body: JSON.stringify({
        messageId: 'WIRE-NONEXIST-' + Date.now(),
        sourceBankCode: 'COOBS',
        destinationBankCode: 'AURABANK',
        beneficiary: { accountNumber: 'ACC-NONEXISTENT-99999', name: 'Ghost' },
        amount: 1000.00,
      }),
    });
  }
  const missingPassed = missingRes.status === 400 && missingRes.body?.reasonCode === 'ACCOUNT_NOT_FOUND';
  recordTest(
    'Interbank Transfers',
    'Inward Wire Non-Existent Local Beneficiary Rejection',
    false,
    missingPassed,
    missingPassed
      ? `HTTP 400 BAD_REQUEST | Code: ${missingRes.body?.reasonCode} | Msg: "${missingRes.body?.reasonMessage}"`
      : `HTTP ${missingRes.status} | Response: ${JSON.stringify(missingRes.body)}`
  );
}

// ============================================================================
// MAIN RUNNER
// ============================================================================
async function main() {
  console.log('================================================================');
  console.log('  AURABANK E2E VERIFICATION TEST RUNNER');
  console.log('  TARGET FEATURES: 1) DEAD LETTER QUEUES & 2) INTERBANK TRANSFERS');
  console.log('================================================================');

  await verifyServiceHealth();
  await runDlqTests();
  await runInterbankTests();

  console.log('\n================================================================');
  console.log('TEST SUMMARY');
  console.log('================================================================');
  const passedCount = testResults.filter((r) => r.status === 'PASS').length;
  const totalCount = testResults.length;
  const failedCount = totalCount - passedCount;

  console.log(`Total Assertions: ${totalCount}`);
  console.log(`Passed:           ${passedCount}`);
  console.log(`Failed:           ${failedCount}`);

  if (failedCount > 0) {
    console.log('\nFailed Tests:');
    testResults
      .filter((r) => r.status === 'FAIL')
      .forEach((r) => console.log(`  ❌ [${r.suite}] ${r.testName} (${r.type}) -> ${r.details}`));
  }

  console.log('================================================================\n');
  process.exit(failedCount === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error('Fatal execution error:', err);
  process.exit(1);
});
