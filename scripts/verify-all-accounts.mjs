// scripts/verify-all-accounts.mjs
// Automated verification for all demo and team accounts across the Aura Console stack

const GATEWAY = process.env.GATEWAY_URL || 'http://localhost:8080';

const DEMO_ACCOUNTS = [
  { email: 'wax@bank.com', role: 'ADMIN', name: 'Wax' },
  { email: 'hans@bank.com', role: 'ADMIN', name: 'Hans' },
  { email: 'jm@bank.com', role: 'TELLER', name: 'JM' },
  { email: 'zel@bank.com', role: 'ADMIN', name: 'Zel' },
  { email: 'jessy@bank.com', role: 'TELLER', name: 'Jessy' },
  { email: 'maye@bank.com', role: 'ADMIN', name: 'Maye' },
  { email: 'angel@bank.com', role: 'TELLER', name: 'Angel' },
  { email: 'diana.admin@bank.com', role: 'ADMIN', name: 'Diana Vance' },
  { email: 'carlos.mendoza@bank.com', role: 'TELLER', name: 'Carlos Mendoza' },
  { email: 'beatriz.ocampo@bank.com', role: 'TELLER', name: 'Beatriz Ocampo' },
];

function decodeJwtPayload(token) {
  try {
    const parts = token.split('.');
    if (parts.length < 2) return {};
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const json = Buffer.from(base64, 'base64').toString('utf8');
    return JSON.parse(json);
  } catch {
    return {};
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function request(path, options = {}) {
  await sleep(120);
  const url = `${GATEWAY}${path}`;
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
  return { status: res.status, headers: res.headers, body };
}

async function testStaffAccount(account) {
  console.log(`\n======================================================`);
  console.log(`Testing Staff Account: ${account.name} (${account.email})`);
  console.log(`Expected Role: ${account.role}`);
  console.log(`======================================================`);

  // 1. Authenticate via Gateway
  const loginRes = await request('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({
      email: account.email,
      password: 'password123',
      deviceType: 'WEB',
      deviceName: 'Aura Console',
    }),
  });

  if (loginRes.status !== 200) {
    throw new Error(`Login failed for ${account.email}: HTTP ${loginRes.status} -> ${JSON.stringify(loginRes.body)}`);
  }

  const { access_token, user_id, role: rawRole, status: authStatus } = loginRes.body;
  if (!access_token) {
    throw new Error(`Login response missing access_token for ${account.email}: ${JSON.stringify(loginRes.body)}`);
  }

  const jwtClaims = decodeJwtPayload(access_token);
  const jwtRole = (jwtClaims.role || '').replace('ROLE_', '');
  console.log(`  [PASS] Login 200 OK | Status: ${authStatus} | UserID: ${user_id}`);
  console.log(`  [PASS] JWT Role: ${jwtRole} (Expected: ${account.role})`);

  if (jwtRole !== account.role) {
    throw new Error(`Role mismatch for ${account.email}: JWT has ${jwtRole}, expected ${account.role}`);
  }

  const authHeaders = { Authorization: `Bearer ${access_token}` };

  // 2. Test KYC Reviews endpoint (Maker-Checker queue)
  const kycRes = await request('/api/v1/kyc/reviews', { headers: authHeaders });
  if (kycRes.status !== 200) {
    throw new Error(`KYC Reviews check failed with HTTP ${kycRes.status}: ${JSON.stringify(kycRes.body)}`);
  }
  const reviewCount = Array.isArray(kycRes.body) ? kycRes.body.length : 0;
  console.log(`  [PASS] GET /api/v1/kyc/reviews -> HTTP 200 OK (${reviewCount} pending reviews)`);

  // 3. Test Accounts endpoint (used by Geo simulator and Customer overview)
  const accountsRes = await request('/api/v1/accounts', { headers: authHeaders });
  if (accountsRes.status !== 200) {
    throw new Error(`Accounts list failed with HTTP ${accountsRes.status}: ${JSON.stringify(accountsRes.body)}`);
  }
  const accounts = Array.isArray(accountsRes.body) ? accountsRes.body : [];
  const customerAccounts = accounts.filter((a) => a.owner_role === 'CUSTOMER');
  console.log(`  [PASS] GET /api/v1/accounts -> HTTP 200 OK (${accounts.length} total, ${customerAccounts.length} customer accounts)`);

  if (customerAccounts.length === 0) {
    throw new Error(`No customer accounts returned with owner_role === 'CUSTOMER' for ${account.email}!`);
  }

  // 4. Test Pending Transfers
  const transfersRes = await request('/api/v1/transfers/pending', { headers: authHeaders });
  if (transfersRes.status !== 200) {
    throw new Error(`Pending transfers failed with HTTP ${transfersRes.status}: ${JSON.stringify(transfersRes.body)}`);
  }
  const pendingCount = Array.isArray(transfersRes.body) ? transfersRes.body.length : 0;
  console.log(`  [PASS] GET /api/v1/transfers/pending -> HTTP 200 OK (${pendingCount} pending transfers)`);

  // 5. Test Pending Reversals
  const reversalsRes = await request('/api/v1/reversals?status=PENDING', { headers: authHeaders });
  if (reversalsRes.status !== 200) {
    throw new Error(`Reversals endpoint failed with HTTP ${reversalsRes.status}: ${JSON.stringify(reversalsRes.body)}`);
  }
  const pendingReversals = Array.isArray(reversalsRes.body) ? reversalsRes.body.length : 0;
  console.log(`  [PASS] GET /api/v1/reversals?status=PENDING -> HTTP 200 OK (${pendingReversals} pending reversals)`);

  // 6. Test SAR Reports (Risk Service)
  const sarRes = await request('/api/v1/risk/sar', { headers: authHeaders });
  if (sarRes.status !== 200) {
    throw new Error(`SAR endpoint failed with HTTP ${sarRes.status}: ${JSON.stringify(sarRes.body)}`);
  }
  const sarCount = Array.isArray(sarRes.body) ? sarRes.body.length : 0;
  console.log(`  [PASS] GET /api/v1/risk/sar -> HTTP 200 OK (${sarCount} SAR drafts)`);

  // 7. Test Audit Vault (Ledger Audit)
  const auditRes = await request('/api/v1/ledger/audit', { headers: authHeaders });
  if (auditRes.status !== 200) {
    throw new Error(`Audit endpoint failed with HTTP ${auditRes.status}: ${JSON.stringify(auditRes.body)}`);
  }
  const auditCount = Array.isArray(auditRes.body) ? auditRes.body.length : 0;
  console.log(`  [PASS] GET /api/v1/ledger/audit -> HTTP 200 OK (${auditCount} immutable journal entries)`);

  // 8. Test Location query for a customer (e.g. U1001)
  const locRes = await request('/api/v1/users/U1001/location', { headers: authHeaders });
  if (locRes.status !== 200) {
    throw new Error(`Location inquiry failed with HTTP ${locRes.status}: ${JSON.stringify(locRes.body)}`);
  }
  console.log(`  [PASS] GET /api/v1/users/U1001/location -> HTTP 200 OK (Location: ${locRes.body.location_name || 'Manila'})`);

  console.log(`  -> ALL CHECKS PASSED for ${account.name}!`);
}

async function testCustomerAccount() {
  console.log(`\n======================================================`);
  console.log(`Testing Customer Account: Juan Dela Cruz (juan.dc@email.com)`);
  console.log(`======================================================`);

  const loginRes = await request('/api/v1/auth/login', {
    method: 'POST',
    body: JSON.stringify({
      email: 'juan.dc@email.com',
      password: 'password123',
      deviceType: 'MOBILE',
      deviceName: 'Pixel 8 Pro',
    }),
  });

  if (loginRes.status !== 200) {
    throw new Error(`Customer login failed: HTTP ${loginRes.status} -> ${JSON.stringify(loginRes.body)}`);
  }

  const { access_token, user_id, role } = loginRes.body;
  const jwtClaims = decodeJwtPayload(access_token);
  console.log(`  [PASS] Customer Login 200 OK | UserID: ${user_id} | Role: ${jwtClaims.role}`);

  const authHeaders = { Authorization: `Bearer ${access_token}` };

  // Customer should retrieve own accounts
  const accountsRes = await request('/api/v1/accounts', { headers: authHeaders });
  if (accountsRes.status !== 200) {
    throw new Error(`Customer accounts failed: HTTP ${accountsRes.status}`);
  }
  const accounts = Array.isArray(accountsRes.body) ? accountsRes.body : [];
  console.log(`  [PASS] Customer accounts retrieved: ${accounts.length} accounts found.`);

  if (accounts.length > 0) {
    const accId = accounts[0].account_id;
    const balRes = await request(`/api/v1/accounts/${accId}/balance`, { headers: authHeaders });
    if (balRes.status === 200) {
      console.log(`  [PASS] Account ${accId} Balance: PHP ${balRes.body.available_balance}`);
    }
  }

  // Verify that customer is FORBIDDEN from staff KYC reviews queue
  const staffKycRes = await request('/api/v1/kyc/reviews', { headers: authHeaders });
  if (staffKycRes.status === 403) {
    console.log(`  [PASS] Customer correctly blocked with HTTP 403 Forbidden from staff KYC reviews.`);
  } else {
    throw new Error(`Customer was NOT blocked from KYC reviews! Status: ${staffKycRes.status}`);
  }
}

async function main() {
  console.log(`Starting Comprehensive Aura Console Account Verification...`);
  console.log(`Target: ${GATEWAY}`);
  
  let failures = 0;

  for (const acc of DEMO_ACCOUNTS) {
    try {
      await testStaffAccount(acc);
    } catch (err) {
      console.error(`  [FAIL] ${acc.name} (${acc.email}):`, err.message);
      failures++;
    }
  }

  try {
    await testCustomerAccount();
  } catch (err) {
    console.error(`  [FAIL] Customer Account Test:`, err.message);
    failures++;
  }

  console.log(`\n======================================================`);
  if (failures === 0) {
    console.log(`ALL 10 DEMO ACCOUNTS AND CUSTOMER SCENARIO VERIFIED SUCCESSFULLY!`);
    console.log(`Zero errors, 100% test pass rate across all roles and endpoints.`);
    console.log(`======================================================\n`);
    process.exit(0);
  } else {
    console.error(`Verification completed with ${failures} failure(s).`);
    console.log(`======================================================\n`);
    process.exit(1);
  }
}

main().catch((err) => {
  console.error('Fatal execution error:', err);
  process.exit(1);
});
