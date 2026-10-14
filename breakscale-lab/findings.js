/* Topologies, scenarios and findings for both architectures. Code references are repo-relative. */
(function () {
  'use strict';
  const pct = (x) => (100 * x).toFixed(0) + '%';

  const ACTUAL = {
    title: 'Actual Azure deployment',
    sub: 'What infrastructure/k8s and deploy_budget_azure.sh create: one B2s_v2 AKS node, every service at replicas: 1, Azure SQL Basic (5 DTU).',
    zones: [
      { x: 120, y: 18, w: 640, h: 484, label: 'AKS · 1 node · standard_b2s_v2 · no HPA' },
      { x: 776, y: 18, w: 208, h: 484, label: 'Azure PaaS' },
    ],
    nodes: [
      { id: 'client', x: 14, y: 226, w: 96, title: 'Clients', sub: 'app + web' },
      { id: 'gw', x: 140, y: 226, w: 150, title: 'gateway-service', sub: 'LB SNAT · 20 tx/s per IP', pools: ['gw'] },
      { id: 'ledger', x: 330, y: 160, w: 200, title: 'ledger-mutation-engine', sub: '1 vCPU · Tomcat 200 · Hikari 30', pools: ['app', 'hikari'] },
      { id: 'acct', x: 330, y: 330, w: 200, title: 'account-service', sub: '500m · Hikari 15', pools: ['acct', 'acctHikari'] },
      { id: 'risk', x: 570, y: 50, w: 170, title: 'risk-service', sub: '500m · Python GIL', pools: ['risk'] },
      { id: 'pg', x: 570, y: 160, w: 170, title: 'postgres (pod)', sub: 'audit · 200m', pools: ['pg'] },
      { id: 'kafka', x: 570, y: 280, w: 170, title: 'outbox relay → kafka', sub: '50 rows / 2s · 1 broker', pools: [], metric: 'outbox' },
      { id: 'notif', x: 570, y: 400, w: 170, title: 'notification-service', sub: '3 consumers', pools: ['notif'], metric: 'lag' },
      { id: 'sql', x: 794, y: 200, w: 174, title: 'Azure SQL', sub: 'Basic · 5 DTU · 30 workers', pools: ['sqlCpu', 'sqlW'] },
    ],
    edges: [
      ['client', 'gw', 'in'], ['gw', 'ledger', 'tx'], ['gw', 'acct', 'read'], ['ledger', 'risk', 'tx'],
      ['ledger', 'pg', 'ok'], ['ledger', 'sql', 'tx'], ['acct', 'sql', 'read'],
      ['ledger', 'kafka', 'async'], ['kafka', 'notif', 'async'], ['notif', 'acct', 'async'],
    ],
    sat: [['risk', 'risk-service'], ['hikari', 'Hikari 30'], ['sqlCpu', 'Azure SQL CPU'], ['app', 'Tomcat']],
    sweep: [2, 4, 6, 8, 10, 12, 14, 16, 20, 25, 30, 40],
  };

  const IDEAL = {
    title: 'Ideal target design',
    sub: 'Front Door WAF with per-user limits, a Mobile BFF, risk scored before any lock and failing safe, HPA on every stateless tier, Azure SQL Business Critical, CDC to Event Hubs.',
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

  const NOFIX = { fixRateKeyByUser: false, fixRiskOutsideTx: false, fixSqlS3: false, fixRiskReplicas: false };
  const base = { hotMerchantPct: 0, riskExtraMs: 0 };
  ACTUAL.scenarios = [
    { name: 'Quiet weekday', note: '5 transfers/s', cfg: { ...base, ...NOFIX, rps: 5, readRps: 8 } },
    { name: 'Payday lunch peak', note: '18 transfers/s, just under the rate limit', cfg: { ...base, ...NOFIX, rps: 18, readRps: 15 } },
    { name: 'Rate limit lifted', note: '30/s with per-user keys', cfg: { ...base, ...NOFIX, rps: 30, readRps: 15, fixRateKeyByUser: true } },
    { name: 'QR merchant rush', note: '40% of payments to one shop', cfg: { ...base, ...NOFIX, rps: 10, readRps: 8, hotMerchantPct: 40 } },
    { name: 'Risk service degraded', note: '+1.6s per score', cfg: { ...base, ...NOFIX, rps: 8, readRps: 8, riskExtraMs: 1600 } },
  ];
  IDEAL.scenarios = [
    { name: 'Quiet weekday', note: '50 transfers/s', cfg: { ...base, rps: 50, readRps: 80 } },
    { name: 'Payday lunch peak', note: '400 transfers/s, 400 reads/s', cfg: { ...base, rps: 400, readRps: 400 } },
    { name: 'QR merchant rush', note: '300/s, 40% to one shop', cfg: { ...base, rps: 300, readRps: 200, hotMerchantPct: 40 } },
    { name: 'Risk service degraded', note: '100/s, +1.6s per score', cfg: { ...base, rps: 100, readRps: 100, riskExtraMs: 1600 } },
    { name: 'Push to the limit', note: '1,000 transfers/s', cfg: { ...base, rps: 1000, readRps: 600 } },
  ];

  /* Each finding: test(sum, ctx) -> { state: 'active' | 'warm' | 'idle' | 'config', ev } */
  const share = (s, k) => { const t = Object.values(s.out).reduce((a, b) => a + b, 0); return t ? s.out[k] / t : 0; };

  ACTUAL.findings = [
    { id: 'route', node: 'gw', title: 'As deployed, /api/v1/transfers routes to a service that is not running',
      why: 'The gateway reads TRANSFER_ORCHESTRATOR_URI (default http://transfer-orchestrator:8082), but the AKS manifest only sets LEDGER_ENGINE_URI and never deploys transfer-orchestrator. Every transfer fails until the route points at ledger-mutation-engine. This lab assumes that fix so the deeper limits are visible.',
      code: 'backend/gateway-service/src/main/resources/application.yml:46 · infrastructure/k8s/05_gateway_service.yaml',
      fix: 'Set TRANSFER_ORCHESTRATOR_URI to the ledger service in 05_gateway_service.yaml.',
      test: () => ({ state: 'config', ev: 'static config check' }) },
    { id: 'snat', node: 'gw', title: 'One rate-limit bucket for the whole country',
      why: 'The limiter keys on X-Forwarded-For, then the remote address. Behind a type: LoadBalancer Service with the default externalTrafficPolicy, kube-proxy SNATs every client to the node IP, so all customers share one 20/s bucket. X-Forwarded-For is client-supplied, so a caller can also rotate it to dodge the limit.',
      code: 'backend/gateway-service/.../security/RateLimiterConfig.java · application.yml replenishRate 20, burstCapacity 40',
      fix: 'Key on the JWT subject and set externalTrafficPolicy: Local or put Front Door in front.', fixKey: 'fixRateKeyByUser',
      test: (s, d) => { const r = share(s, 'rate_limited'); return { state: d.cfg.fixRateKeyByUser ? 'idle' : r > 0.05 ? 'active' : 'warm', ev: `${pct(r)} of transfers and ${(100 - s.readOkPct).toFixed(0)}% of dashboard reads rejected (reads share one 10/s bucket)` }; } },
    { id: 'riskInTx', node: 'ledger', title: 'Risk scoring runs inside the database transaction',
      why: 'executeTransfer takes PESSIMISTIC_WRITE locks on both balance rows, then calls risk-service over HTTP (1.5s timeout) before committing. Each transfer pins a Hikari connection and two row locks for the whole score. Measured /evaluate cost is 36 ms on a full core; the 500m limit doubles it, so one pod settles about 13 transfers/s.',
      code: 'ledger-mutation-engine/.../service/BalanceMutationService.java:71,96-106,174 · RiskEngineClient.java:145',
      fix: 'Score risk before opening the transaction; keep the transaction to locks, writes and commit.', fixKey: 'fixRiskOutsideTx',
      test: (s) => { const u = s.util.risk || 0, h = s.util.hikari || 0; return { state: u > 0.9 || h > 0.85 ? 'active' : u > 0.6 ? 'warm' : 'idle', ev: `risk busy ${pct(u)} · Hikari busy ${pct(h)} · ${s.q.hikari || 0} waiting for a connection` }; } },
    { id: 'failOpen', node: 'risk', title: 'When risk is slow, transfers go through unscreened',
      why: 'On timeout the client returns ALLOW with flag FALLBACK_STATIC_RULE unless the request carries a device signal. Under load the fraud check silently switches off exactly when an attacker would want it to.',
      code: 'ledger-mutation-engine/.../service/RiskEngineClient.java:198-231',
      fix: 'Fail safe: hold the transfer for review (the ideal design does this).',
      test: (s, d) => ({ state: d.unscreened > 0 ? 'active' : 'idle', ev: `${d.unscreened} unscreened in the last 20s · ${d.total.unscreened} total` }) },
    { id: 'sqlBasic', node: 'sql', title: 'Azure SQL Basic is a sub-core database',
      why: 'Basic is 5 DTU, under one vCore, 30 concurrent workers, on HDD-backed page blobs. account-service, ledger, the relay and notifications share it. A blocked session keeps its worker, so lock waits use up the 30 and new statements fail with error 10928.',
      code: 'infrastructure/azure/deploy_budget_azure.sh (az sql db create --edition Basic --capacity 5)',
      fix: 'Standard S3 or above (Microsoft recommends S3+ for real workloads).', fixKey: 'fixSqlS3',
      test: (s, d) => { const u = s.util.sqlCpu || 0; return { state: d.sqlErr > 0 || u > 0.9 ? 'active' : u > 0.6 ? 'warm' : 'idle', ev: `SQL CPU ${pct(u)} · ${d.sqlErr} worker-limit errors (20s)` }; } },
    { id: 'hotRow', node: 'sql', title: 'Every QR payment queues on the merchant’s balance row',
      why: 'Payments to one merchant all lock the same balance_master row, and the lock is held through the risk call. Merchant throughput becomes one transfer per lock-hold time, whatever the hardware.',
      code: 'ledger-mutation-engine/.../repository/master/BalanceMasterRepository.java (PESSIMISTIC_WRITE)',
      fix: 'Credit merchants through sub-ledger buckets and keep risk out of the lock.',
      test: (s) => { const w = s.rows['bal:MERCHANT-QR-1'] || 0; return { state: w > 3 ? 'active' : w > 1 ? 'warm' : 'idle', ev: `${w} transfers waiting on the merchant row (peak)` }; } },
    { id: 'relay', node: 'kafka', title: 'The outbox relay tops out at 25 events/s',
      why: 'The relay wakes every 2s, reads 50 PENDING rows and sends them one at a time, waiting up to 5s for each Kafka ack. Each transfer writes 2 rows, so above ~12 transfers/s the backlog grows without bound and notifications fall minutes behind.',
      code: 'ledger-mutation-engine/.../outbox/OutboxRelayScheduler.java:35-58',
      fix: 'CDC or a continuous relay with batched async sends.',
      test: (s) => ({ state: s.outbox > 200 ? 'active' : s.outbox > 60 ? 'warm' : 'idle', ev: `${s.outbox} events pending · last notification ${(s.notifLag / 1000).toFixed(1)}s after commit` }) },
    { id: 'orphan', node: 'client', title: 'The app gives up, the money still moves',
      why: 'When a transfer waits past the client timeout the user sees a failure, but the server keeps working and commits the debit. A retry that does not reuse the same transaction id can pay twice.',
      code: 'BalanceMutationController.java:35 (no request deadline propagated)',
      fix: 'Propagate a deadline, return 202 + status polling, and require an idempotency key.',
      test: (s, d) => ({ state: d.orphan > 0 ? 'active' : 'idle', ev: `${d.orphan} committed after the app gave up (20s) · ${d.total.orphanCommits} total` }) },
  ];

  IDEAL.findings = [
    { id: 'edge', node: 'fd', title: 'Limits per customer at the edge',
      why: 'Front Door WAF rate rules key on the JWT subject, so one customer cannot crowd out the rest and no bucket is shared by the whole country.',
      code: 'design: Front Door rate-limit rule + gateway keyed on sub claim',
      fix: 'Replaces the SNAT’d per-IP bucket in the actual design.',
      test: (s) => { const r = share(s, 'rate_limited'); return { state: r > 0.02 ? 'warm' : 'idle', ev: `${pct(r)} rate limited` }; } },
    { id: 'bff', node: 'bff', title: 'The BFF absorbs the read traffic',
      why: 'The dashboard is one BFF call that fans out to Redis in parallel; 95% are cache hits and misses go to the readable secondary, so reads never touch the primary that settles money.',
      code: 'design: Mobile BFF · Redis cache-aside, evicted on posting',
      fix: 'In the actual design every read is two Azure SQL statements on the same 30 workers.',
      test: (s) => ({ state: s.readOkPct < 99 ? 'active' : 'idle', ev: `reads OK ${s.readOkPct.toFixed(1)}% · p99 ${s.readP99.toFixed(0)} ms` }) },
    { id: 'riskSafe', node: 'risk', title: 'Risk fails safe and outside the transaction',
      why: 'Scoring happens before any lock or connection, with a 300 ms deadline and a bulkhead. If risk is slow the transfer is held for review; nothing is approved unscreened and nothing piles onto the database.',
      code: 'design: transfer-service calls risk first; timeout -> HELD',
      fix: 'Held transfers are the visible cost: watch the violet band.',
      test: (s) => { const h = share(s, 'held'); return { state: h > 0.15 ? 'active' : h > 0.05 ? 'warm' : 'idle', ev: `${pct(h)} held for review · risk busy ${pct(s.util.risk || 0)}` }; } },
    { id: 'hpa', node: 'risk', title: 'Next ceiling: risk CPU while autoscaling catches up',
      why: 'At 36 ms per score each risk worker settles about 28/s. HPA adds pods every 15s and they serve 30s later, so a sudden spike is held for review until capacity lands. At 30 pods the risk tier itself becomes the limit.',
      code: 'design: HPA target 60% CPU, max 30 risk pods',
      fix: 'Pre-scale for known peaks (payday), or move scoring to a compiled model.',
      test: (s) => { const r = s.replicas.risk || 0; return { state: (s.q.risk || 0) > 20 ? 'active' : r > 3 ? 'warm' : 'idle', ev: `risk pods ${r} · BFF ${s.replicas.bff || 0} · transfer ${s.replicas.tx || 0}` }; } },
    { id: 'buckets', node: 'sql', title: 'Merchant credits spread over 16 buckets',
      why: 'Each merchant has 16 sub-ledger rows; a credit picks one at random and a nightly job (or a read-time SUM) folds them. The hot-row queue disappears and locks last only for the write.',
      code: 'design: sub-ledger buckets · short transaction (locks, writes, commit)',
      fix: 'Compare the QR merchant rush scenario across both designs.',
      test: (s) => { const w = Math.max(0, ...Object.entries(s.rows).filter(([k]) => k.includes('MERCHANT')).map(([, v]) => v)); return { state: w > 3 ? 'warm' : 'idle', ev: `peak bucket queue ${w}` }; } },
    { id: 'cdc', node: 'eh', title: 'Events leave by change feed, not polling',
      why: 'The outbox row commits with the balance change and CDC streams it to Event Hubs; consumers scale on lag. Notification delay stays near 150 ms instead of growing with load.',
      code: 'design: outbox + CDC (Debezium / SQL change feed) -> Event Hubs',
      fix: 'Replaces the 50-rows-per-2s relay.',
      test: (s) => ({ state: s.notifLag > 2000 ? 'warm' : 'idle', ev: `notification ${(s.notifLag).toFixed(0)} ms after commit` }) },
  ];

  window.ARCH = { actual: ACTUAL, ideal: IDEAL };
})();
