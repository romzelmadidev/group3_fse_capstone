/* Topologies, scenarios and findings for both architectures. Code references are repo-relative. */
(function () {
  'use strict';
  const pct = (x) => (100 * x).toFixed(0) + '%';
  const n0 = (x) => Math.round(x).toLocaleString('en-US');

  const ACTUAL = {
    title: 'Actual Azure deployment',
    sub: 'What infrastructure/k8s and run_azure.sh create: 2 x Standard_B2s nodes, every service at replicas: 1, Azure SQL S1. Pick Basic and 1 node to model deploy_budget_azure.sh.',
    zones: [
      { x: 120, y: 18, w: 640, h: 492, label: 'AKS · Standard_B2s nodes · no HPA' },
      { x: 776, y: 18, w: 208, h: 492, label: 'Azure PaaS' },
    ],
    nodes: [
      { id: 'client', x: 14, y: 226, w: 96, title: 'Clients', sub: 'app + web' },
      { id: 'gw', x: 140, y: 226, w: 150, title: 'gateway-service', sub: 'LB SNAT · 20/s tx · 10/s acct', pools: ['gw', 'gwLoop'] },
      { id: 'redis', x: 140, y: 380, w: 150, title: 'redis (pod)', sub: '100m · rate limit + locks', pools: ['redis'] },
      { id: 'orch', x: 330, y: 110, w: 190, title: 'transfer-orchestrator', sub: 'Tomcat 200 · CB 5s on CBS', pools: ['orch', 'orchCpu'] },
      { id: 'acct', x: 330, y: 300, w: 190, title: 'account-service', sub: 'limits · reads · login · Hikari 15', pools: ['acct', 'acctHikari', 'acctCpu'] },
      { id: 'nodes', x: 330, y: 430, w: 190, title: 'AKS node CPU', sub: 'burstable B2s', pools: ['node'], metric: 'cpu' },
      { id: 'risk', x: 560, y: 36, w: 170, title: 'risk-service', sub: '/score · Python GIL', pools: ['risk'] },
      { id: 'cbs', x: 560, y: 140, w: 170, title: 't24-mock-cbs', sub: 'Hikari 15 · GL row 20100', pools: ['cbs', 'cbsHikari', 'gl'] },
      { id: 'audit', x: 560, y: 262, w: 170, title: 'CBS audit consumer', sub: '1 thread · postgres 200m', pools: ['audit', 'pg'], metric: 'audit' },
      { id: 'notif', x: 560, y: 384, w: 170, title: 'notification-service', sub: '3 partitions', pools: ['notif', 'notifCpu'], metric: 'lag' },
      { id: 'sql', x: 794, y: 200, w: 174, title: 'Azure SQL', sub: 'DTU tier set by the picker', pools: ['sqlCpu', 'sqlW'], metric: 'sql' },
    ],
    edges: [
      ['client', 'gw', 'in'], ['gw', 'orch', 'tx'], ['gw', 'acct', 'read'], ['orch', 'acct', 'tx'], ['orch', 'risk', 'tx'],
      ['orch', 'cbs', 'tx'], ['cbs', 'sql', 'tx'], ['acct', 'sql', 'read'], ['cbs', 'audit', 'async'], ['cbs', 'notif', 'async'],
    ],
    sat: [['gl', 'GL row 20100'], ['sqlCpu', 'SQL DTU'], ['cbsHikari', 'CBS Hikari 15'], ['node', 'node CPU']],
    sweep: [1, 2, 3, 4, 6, 8, 10, 14, 20, 30, 45],
  };

  const IDEAL = {
    title: 'Ideal target design',
    sub: 'Not deployed. Front Door WAF with per-user limits, a Mobile BFF, risk scored before any lock and failing safe, HPA on every stateless tier, Azure SQL Business Critical, CDC to Event Hubs.',
    zones: [
      { x: 120, y: 18, w: 210, h: 484, label: 'Edge' },
      { x: 342, y: 18, w: 400, h: 484, label: 'AKS · 3 zones · HPA' },
      { x: 754, y: 18, w: 230, h: 484, label: 'Azure PaaS' },
    ],
    nodes: [
      { id: 'client', x: 14, y: 226, w: 96, title: 'Clients', sub: 'app + web' },
      { id: 'fd', x: 140, y: 226, w: 172, title: 'Front Door + WAF', sub: 'per-user 20 tx/s · TLS at edge', pools: [] },
      { id: 'bff', x: 362, y: 226, w: 160, title: 'Mobile BFF', sub: 'aggregates · HPA 2–10', pools: ['bff'], tier: 'bff' },
      { id: 'tx', x: 560, y: 130, w: 166, title: 'transfer-service', sub: 'commands · HPA 2–12', pools: ['tx', 'hikari'], tier: 'tx' },
      { id: 'risk', x: 560, y: 30, w: 166, title: 'risk-service', sub: '300ms deadline · bulkhead', pools: ['risk'], tier: 'risk' },
      { id: 'notif', x: 560, y: 410, w: 166, title: 'notification', sub: 'HPA on consumer lag', pools: ['notif'], tier: 'notif', metric: 'lag' },
      { id: 'redis', x: 560, y: 300, w: 166, title: 'Cache for Redis', sub: 'Premium · idempotency + reads', pools: ['redis'] },
      { id: 'sql', x: 772, y: 120, w: 196, title: 'Azure SQL', sub: 'Business Critical · 8 vCore', pools: ['sqlCpu', 'sqlW'] },
      { id: 'replica', x: 772, y: 240, w: 196, title: 'Readable secondary', sub: 'serves cache misses', pools: ['replica'] },
      { id: 'eh', x: 772, y: 360, w: 196, title: 'Event Hubs', sub: 'CDC change feed · 32 partitions', pools: ['cdc'] },
    ],
    edges: [
      ['client', 'fd', 'in'], ['fd', 'bff', 'all'], ['bff', 'tx', 'tx'], ['bff', 'redis', 'read'], ['redis', 'replica', 'read'],
      ['tx', 'risk', 'tx'], ['tx', 'sql', 'ok'], ['sql', 'eh', 'async'], ['eh', 'notif', 'async'],
    ],
    sat: [['risk', 'risk-service'], ['bff', 'BFF'], ['sqlCpu', 'Azure SQL CPU'], ['notif', 'consumers']],
    sweep: [50, 100, 200, 400, 600, 800, 1000, 1200],
  };

  const base = { hotMerchantPct: 0, riskExtraMs: 0, loginRps: 0, corpWifiPct: 0 };
  const FIXED = { fixRateKeyByUser: false, fixPreserveClientIp: false, fixPersistNew: false, fixGlBuckets: false };
  const DEP = { nodes: 2, sqlTier: 's1', cpuCreditsMin: 100 }; // run_azure.sh defaults
  ACTUAL.scenarios = [
    { name: 'Quiet weekday', note: '1 transfer/s, about 30 open apps', cfg: { ...base, ...FIXED, ...DEP, rps: 1, readRps: 6 } },
    { name: 'Lunch peak on S1', note: '5 transfers/s: the database is the wall', cfg: { ...base, ...FIXED, ...DEP, rps: 5, readRps: 20 } },
    { name: 'Same peak on S3', note: 'DB upgraded; next wall appears at 10/s', cfg: { ...base, ...FIXED, ...DEP, sqlTier: 's3', rps: 10, readRps: 20 } },
    { name: 'GL row wall', note: '14/s on S3: one row serialises the bank', cfg: { ...base, ...FIXED, ...DEP, sqlTier: 's3', rps: 14, readRps: 20 } },
    { name: 'Open apps polling', note: '200 open apps poll balance every 5 s', cfg: { ...base, ...FIXED, ...DEP, rps: 1, readRps: 40 } },
    { name: '9am login burst', note: '12 logins/s, BCrypt on 1 vCPU', cfg: { ...base, ...FIXED, ...DEP, sqlTier: 's3', rps: 3, readRps: 10, loginRps: 12, fixRateKeyByUser: true } },
    { name: 'QR merchant rush', note: '40% of payments to one shop', cfg: { ...base, ...FIXED, ...DEP, sqlTier: 's3', rps: 8, readRps: 10, hotMerchantPct: 40 } },
    { name: 'Risk service degraded', note: '+2.5 s per score: checks fail open', cfg: { ...base, ...FIXED, ...DEP, sqlTier: 's3', rps: 6, readRps: 10, riskExtraMs: 2500 } },
    { name: 'CPU credits spent', note: 'burstable nodes throttle to baseline', cfg: { ...base, ...FIXED, ...DEP, sqlTier: 's3', rps: 8, readRps: 10, cpuCreditsMin: 0 } },
    { name: 'Budget cluster', note: 'deploy_budget_azure.sh: 1 node, Basic SQL', cfg: { ...base, ...FIXED, ...DEP, nodes: 1, sqlTier: 'basic', rps: 1, readRps: 6 } },
    { name: 'Office Wi-Fi demo', note: 'after the IP fix, 80% of traffic is one NAT', cfg: { ...base, ...FIXED, ...DEP, sqlTier: 's3', rps: 30, readRps: 10, corpWifiPct: 80, fixPreserveClientIp: true, fixPersistNew: true, fixGlBuckets: true } },
  ];
  IDEAL.scenarios = [
    { name: 'Quiet weekday', note: '50 transfers/s', cfg: { ...base, rps: 50, readRps: 80 } },
    { name: 'Payday lunch peak', note: '400 transfers/s, 400 reads/s', cfg: { ...base, rps: 400, readRps: 400 } },
    { name: 'QR merchant rush', note: '300/s, 40% to one shop', cfg: { ...base, rps: 300, readRps: 200, hotMerchantPct: 40 } },
    { name: 'Risk service degraded', note: '100/s, +1.6s per score', cfg: { ...base, rps: 100, readRps: 100, riskExtraMs: 1600 } },
    { name: 'Push to the limit', note: '1,000 transfers/s', cfg: { ...base, rps: 1000, readRps: 600 } },
  ];

  /* Each finding: test(summary, deltas) -> { state: 'active' | 'warm' | 'idle' | 'config', ev }.
     fix.try is a config patch the UI can apply; undo restores the deployed values. */
  const share = (s, k) => { const t = Object.values(s.out).reduce((a, b) => a + b, 0); return t ? s.out[k] / t : 0; };
  const rshare = (s, k) => { const t = Object.values(s.rout).reduce((a, b) => a + b, 0); return t ? s.rout[k] / t : 0; };
  const act = (cond, warm, ev) => ({ state: cond ? 'active' : warm ? 'warm' : 'idle', ev });

  ACTUAL.findings = [
    { id: 'route', node: 'orch', title: 'The deployed transfer path is gateway, orchestrator, CBS. ledger-mutation-engine is not deployed',
      why: 'infrastructure/k8s has no manifest for ledger-mutation-engine, and the gateway routes /api/v1/transfers to transfer-orchestrator, which posts to t24-mock-cbs. Findings about the ledger engine (risk inside its transaction, its outbox relay) do not apply to the cloud deployment, so this model drops them.',
      code: 'infrastructure/k8s/05_gateway_service.yaml · backend/gateway-service/src/main/resources/application.yml:46-57',
      fix: 'Nothing to change. Keep the ledger engine out of any capacity claim about AKS.',
      test: () => ({ state: 'config', ev: 'static check of the manifests' }) },
    { id: 'sql', node: 'sql', title: 'The database is the first wall: one transfer costs about 43 round trips',
      why: 'Azure SQL S1 is 20 DTU with 90 workers and an HDD log. Each transfer runs about 43 SQL round trips in one transaction, so S1 settles only a few transfers per second. Basic (5 DTU, 30 workers) from deploy_budget_azure.sh is four times smaller. A blocked session keeps its worker, so lock waits use workers up and later statements fail with error 10928.',
      code: 'infrastructure/azure/run_azure.sh (SQL_TIER=S1) · deploy_budget_azure.sh (Basic, capacity 5)',
      fix: 'Standard S3 or above, and cut the round trips (next finding).', try: { sqlTier: 's3' },
      test: (s, d) => act((s.util.sqlCpu || 0) > 0.9 || d.s.sqlLimitErrors > 0, (s.util.sqlCpu || 0) > 0.6, `DTU ${pct(s.util.sqlCpu || 0)} busy · ${d.s.sqlLimitErrors} worker-limit errors (20s)`) },
    { id: 'chatty', node: 'cbs', title: 'Spring Data save() on assigned ids runs a SELECT before every INSERT',
      why: 'Transaction, GL ledger, history and outbox rows all carry ids the app assigns, so save() calls merge(): a SELECT, then the INSERT. That is 14 extra reads per transfer, inside the transaction that holds the row locks.',
      code: 'backend/t24-mock-cbs/.../service/CbsFundsTransferService.java:106-505 (saveAll at 417, saveOutbox at 519)',
      fix: 'Implement Persistable.isNew() or add @Version so inserts skip the SELECT.', try: { fixPersistNew: true },
      test: (s, d) => ({ state: d.cfg.fixPersistNew ? 'idle' : (s.util.sqlCpu || 0) > 0.6 ? 'warm' : 'config', ev: d.cfg.fixPersistNew ? 'inserts skip the SELECT: 29 round trips' : '43 round trips per transfer' }) },
    { id: 'gl', node: 'cbs', title: 'Every transfer queues on one global GL row',
      why: 'executeTransfer read-modify-writes the gl_balances row (20100, current period) for both the debit and the credit side. The UPDATE takes an exclusive row lock held until COMMIT, so the whole bank posts one transfer at a time, whatever the hardware. Totals are written as absolute values from an earlier read, so a transfer that waited can overwrite the one before it.',
      code: 'CbsFundsTransferService.java:274-275,487-503 · entity/master/GlBalanceMaster.java (no @Version)',
      fix: 'Post GL control totals to bucket rows, or derive them from gl_ledger in the end-of-day batch.', try: { fixGlBuckets: true },
      test: (s, d) => act((s.util.gl || 0) > 0.7, (s.util.gl || 0) > 0.4, `GL row busy ${pct(s.util.gl || 0)} · held ${n0(s.glHoldP50)} ms per transfer (ceiling ${s.glHoldP50 ? n0(1000 / s.glHoldP50) : '–'}/s) · ${d.s.glLostUpdates} totals read stale (20s)`) },
    { id: 'hikari', node: 'cbs', title: 'Waiting transfers hold CBS connections, so limit checks fail closed',
      why: 'A transfer holds one of CBS’s 15 connections while it waits for the GL row. Once 15 are waiting, the limit service’s usage query cannot get a connection, its 5 s read timeout fires, and the orchestrator answers 503 and queues the transfer on the DLQ. The database is nearly idle while customers are refused.',
      code: 'application-azure.properties (t24-mock-cbs maximum-pool-size=15) · LimitClientService.java · CbsUsageClient.java:44',
      fix: 'Fix the GL row first, then give reads their own pool.', try: { fixGlBuckets: true },
      test: (s, d) => act(d.why.limitUnavailable > 0 && (s.util.cbsHikari || 0) > 0.8, (s.util.cbsHikari || 0) > 0.6, `CBS connections ${pct(s.util.cbsHikari || 0)} busy · ${d.why.limitUnavailable} limit checks failed closed (20s)`) },
    { id: 'lock', node: 'acct', title: 'The customer limit lock outlives the app',
      why: 'LimitService takes a per-customer Redis lock and the orchestrator releases it only when the whole transfer ends. The lock expires after 30 s. If the app gives up at 4 s and the customer taps again, the retry polls every 50 ms for 2 s and is then refused. A release that cannot reach a busy account-service leaves the lock until it expires.',
      code: 'backend/account-service/.../limits/LimitService.java:41-44,160-178 · TransferOrchestrationService.java:104',
      fix: 'Release in a finally with its own deadline, shorten the TTL, and send the original idempotency key on retry.',
      test: (s, d) => act(d.why.limitLockTimeout > 0, (s.ex.userLocks || 0) > 20, `${d.why.limitLockTimeout} retries refused by a held lock (20s) · ${s.ex.userLocks || 0} locks held now`) },
    { id: 'snat', node: 'gw', title: 'One rate-limit bucket for the whole country',
      why: 'The limiter keys on X-Forwarded-For, then the remote address. Behind a LoadBalancer Service with the default externalTrafficPolicy, kube-proxy rewrites every client to the node IP, so all customers share one 20/s transfer bucket and one 10/s account bucket.',
      code: 'backend/gateway-service/.../security/RateLimiterConfig.java · application.yml (replenishRate 20 and 10) · k8s/05_gateway_service.yaml',
      fix: 'Key on the JWT subject. externalTrafficPolicy: Local alone restores real IPs, but offices still share one.', try: { fixRateKeyByUser: true },
      test: (s, d) => { const r = share(s, 'rate_limited'); return { state: d.cfg.fixRateKeyByUser ? 'idle' : r > 0.05 ? 'active' : 'warm', ev: `${pct(r)} of transfers rejected with 429 (bucket 20/s, burst 40)` }; } },
    { id: 'poll', node: 'gw', title: 'Balance polling shares the login bucket, and the app hides the 429',
      why: 'Every open home screen polls the balance every 5 s, on the same route as login and KYC. About 50 open apps use the whole 10/s bucket. refreshBalance() returns silently on any status except 200, so customers see a stale balance, not an error.',
      code: 'aurabank_app/lib/screens/home/home_screen.dart:53 · aurabank_core/lib/services/bank_service.dart:1112-1135 · gateway application.yml (account-service route)',
      fix: 'Key by user, push balances over the existing SSE stream, or poll less often.', try: { fixRateKeyByUser: true },
      test: (s, d) => { const r = rshare(s, 'rate_limited'); return { state: d.cfg.fixRateKeyByUser ? 'idle' : r > 0.05 ? 'active' : d.cfg.readRps > 7 ? 'warm' : 'idle', ev: `${pct(r)} of balance polls rejected · about ${n0(d.cfg.readRps * 5)} open apps` }; } },
    { id: 'corp', node: 'gw', title: 'Office Wi-Fi shares one bucket even after the IP fix',
      why: 'externalTrafficPolicy: Local would show the gateway real client addresses, but staff behind a corporate NAT, a campus or a demo room still arrive from one public IP. They share 20 transfers/s and 10 account calls/s. It is also why a load test from a corporate network says little: it measures the rate limiter, not the system.',
      code: 'RateLimiterConfig.java (first X-Forwarded-For entry, else remote address)',
      fix: 'Key on the JWT subject. Run load tests from inside the cluster or from cloud workers, not from the office network.', try: { fixRateKeyByUser: true },
      test: (s, d) => { const on = d.cfg.fixPreserveClientIp && d.cfg.corpWifiPct > 0 && !d.cfg.fixRateKeyByUser; return { state: on ? (share(s, 'rate_limited') > 0.05 ? 'active' : 'warm') : 'idle', ev: on ? `${d.cfg.corpWifiPct}% of traffic from one IP · ${pct(share(s, 'rate_limited'))} rejected` : 'turn on client IPs and set the office share to see it' }; } },
    { id: 'login', node: 'acct', title: 'BCrypt login bursts starve the pod that also does limits and the geo check',
      why: 'BCryptPasswordEncoder(10) costs tens of milliseconds of one vCPU per login, and account-service is a single 1000m pod. Limit checks, risk lookups and balance reads queue behind it. Risk gives the location lookup 0.5 s and then skips the travel-speed rule, so a login burst quietly switches that fraud rule off.',
      code: 'backend/account-service/.../config/SecurityAndRedisConfig.java:19-21 · backend/risk-service/app/geo_velocity_rule.py (_current_location)',
      fix: 'Rate-limit logins separately, run a second account-service replica, and fail the geo rule closed to review.',
      test: (s, d) => act(d.s.unscreenedGeo > 0, (s.util.acctCpu || 0) > 0.7, `account CPU ${pct(s.util.acctCpu || 0)} · ${d.s.unscreenedGeo} transfers skipped the travel-speed rule (20s) · logins OK ${s.loginOkPct.toFixed(0)}%`) },
    { id: 'failOpen', node: 'risk', title: 'When risk is slow or down, transfers go through with lighter checks',
      why: 'The orchestrator gives risk 2 s. On timeout it scores by amount alone: below PHP 10,000 the answer is ALLOW. Inside risk, the travel-speed and Basic-tier lookups fail open after 0.5 s. An attacker can pick the moment.',
      code: 'transfer-orchestrator/.../service/RiskClientService.java:30-50 · risk-service/app/basic_tier_rule.py',
      fix: 'Fail closed to review for any transfer that was not scored, as the ideal design does.',
      test: (s, d) => act(d.s.unscreened > 0, false, `${d.s.unscreened} transfers approved on contingency scoring (20s) · ${d.total.unscreened} in total`) },
    { id: 'breaker', node: 'orch', title: 'Timeouts open the CBS breaker, and the 3 retries never run',
      why: 'Only a 5 s timeout counts as a failure. Five failures in ten calls open the breaker for 10 s, and every transfer in that window is refused and written to the DLQ while the database may be healthy. Retry sits outside the breaker and the fallback turns every failure into an ignored 503, so by the documented Resilience4j ordering the three configured attempts should never fire. Not verified at runtime.',
      code: 'transfer-orchestrator application.properties (cbsService) · service/CbsClientService.java:176-232',
      fix: 'Count 5xx as failures, retry only idempotent calls inside the breaker, and trip on latency rather than a 5 s timeout.',
      test: (s, d) => act(d.why.breakerOpen > 0 || d.s.cbOpens > 0, d.why.cbsTimeout > 0, `breaker ${s.ex.cb || 'closed'} · opened ${d.total.cbOpens}x · ${d.why.breakerOpen} transfers refused while open · ${d.total.dlq} on the DLQ`) },
    { id: 'orphan', node: 'client', title: 'The app gives up at 4 s, the money still moves, and a retry can pay twice',
      why: 'The app abandons a transfer after 4 s, but the orchestrator and CBS carry on and commit. Tapping again builds a new idempotency key, so nothing stops a second payment except the daily limit.',
      code: 'aurabank_core/lib/services/bank_service.dart:1521,1584 · gateway IdempotencyFilter.java (10 min key)',
      fix: 'Reuse the key on retry, return 202 with status polling, and carry a deadline through to CBS.',
      test: (s, d) => ({ state: d.total.duplicatePayments > 0 ? 'active' : d.s.orphanCommits > 0 ? 'warm' : 'idle', ev: `${d.s.orphanCommits} committed after the app gave up (20s) · ${d.total.duplicatePayments} paid twice in total · ${d.total.retries} taps on retry` }) },
    { id: 'audit', node: 'audit', title: 'The audit consumer is one thread, so the audit trail falls behind',
      why: 'Each transfer publishes 6 events. cbs-audit-workers handles them on a single listener thread, one database transaction per event, against a postgres pod limited to 200m CPU. Past a few transfers a second the backlog grows and the immutable audit vault lags the ledger.',
      code: 'backend/t24-mock-cbs/.../service/CbsAuditSelfConsumptionService.java:52-82 · k8s/00_in_cluster_dependencies.yaml (postgres 200m)',
      fix: 'Raise listener concurrency to the partition count, batch the inserts, give postgres more CPU.',
      test: (s) => act(s.auditLag > 5000, s.auditLag > 1500, `audit lag ${(s.auditLag / 1000).toFixed(1)} s behind the commit`) },
