/*
 * AuraBank load model: a Breakscale-style discrete-event simulation.
 * Kept from Breakscale: every number is measured from individual simulated
 * requests; servers are finite slot pools with FIFO queues; gamma service
 * times; a caller that times out walks away while the callee keeps working;
 * seeded RNG so a run replays exactly.
 *
 * v2 models the path that infrastructure/k8s actually deploys:
 *   app -> gateway -> transfer-orchestrator -> { account-service limits,
 *   risk-service /score, t24-mock-cbs funds-transfer } -> Azure SQL,
 * with Kafka fan-out to the CBS audit consumer and notification-service.
 * ledger-mutation-engine has no k8s manifest, so it is not modelled.
 *
 * Every constant is tagged in INPUTS as code (read from this repo), doc
 * (Microsoft documentation) or estimate (unmeasured; calibrate it).
 * Browser: window.AuraSim. Node: require.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.AuraSim = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* ---------- deterministic randomness ---------- */
  function mulberry32(seed) {
    let s = seed >>> 0;
    return function () {
      s = (s + 0x6d2b79f5) >>> 0;
      let t = s;
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  function gamma(rand, shape) { // Marsaglia-Tsang
    if (shape < 1) return gamma(rand, shape + 1) * Math.pow(1 - rand(), 1 / shape);
    const d = shape - 1 / 3, k = 1 / Math.sqrt(9 * d);
    for (;;) {
      let x, v;
      do {
        let u1 = 0;
        while (u1 === 0) u1 = rand();
        x = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * rand());
        v = 1 + k * x;
      } while (v <= 0);
      v = v * v * v;
      const u = 1 - rand();
      if (u < 1 - 0.0331 * x * x * x * x || Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
    }
  }

  /* ---------- event loop (binary min-heap on time, then insertion order) ---------- */
  const before = (a, b) => a.t < b.t || (a.t === b.t && a.s < b.s);
  class Sim {
    constructor(seed) { this.now = 0; this.h = []; this.seq = 0; this.rand = mulberry32(seed); }
    exp(mean) { return -Math.log(1 - this.rand()) * mean; }
    svc(mean, cv = 0.5) { return mean <= 0 ? 0 : gamma(this.rand, 1 / (cv * cv)) * mean * cv * cv; }
    at(t, fn) {
      const h = this.h, e = { t, s: this.seq++, fn };
      let i = h.push(e) - 1;
      while (i > 0) {
        const p = (i - 1) >> 1;
        if (!before(e, h[p])) break;
        h[i] = h[p]; i = p;
      }
      h[i] = e;
    }
    pop() {
      const h = this.h, top = h[0], last = h.pop();
      if (!h.length) return top;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1, r = l + 1;
        let m = last, mi = -1;
        if (l < h.length && before(h[l], m)) { m = h[l]; mi = l; }
        if (r < h.length && before(h[r], m)) { m = h[r]; mi = r; }
        if (mi < 0) break;
        h[i] = m; i = mi;
      }
      h[i] = last;
      return top;
    }
    runUntil(t) {
      while (this.h.length && this.h[0].t <= t) { const e = this.pop(); this.now = e.t; e.fn(); }
      this.now = t;
    }
    /* Processes are generators. Yield: a number to wait (ms); {acq, timeout} for a pool slot;
       {lock, locks} for a row lock; {join} for parallel sub-processes; {call, timeout, orphan}
       for a remote call the caller may abandon. An abandoned call keeps running: that is the point. */
    run(gen, done) { this.step({ gen, done }, undefined); }
    step(p, v) {
      const r = p.gen.next(v);
      if (r.done) { if (p.done) p.done(r.value); return; }
      const c = r.value;
      const resume = (x) => this.at(this.now, () => this.step(p, x));
      if (typeof c === 'number') this.at(this.now + c, () => this.step(p));
      else if (c.acq) c.acq.acquire(resume, c.timeout);
      else if (c.lock) c.locks.lock(c.lock, resume);
      else if (c.join) {
        let n = c.join.length;
        const vals = [];
        c.join.forEach((g, i) => this.run(g, (v) => { vals[i] = v; if (--n === 0) resume(vals); }));
      }
      else if (c.call) {
        let settled = false;
        this.run(c.call, (val) => {
          if (settled) { if (c.orphan) c.orphan(val); return; }
          settled = true; resume({ ok: true, v: val });
        });
        if (c.timeout != null) this.at(this.now + c.timeout, () => { if (!settled) { settled = true; resume({ ok: false }); } });
      }
    }
  }

  /* A finite slot pool with a FIFO wait queue: Tomcat threads, Hikari connections, CPU cores. */
  class Pool {
    constructor(sim, cap, maxQ = Infinity) {
      this.sim = sim; this.cap = cap; this.maxQ = maxQ;
      this.busy = 0; this.q = []; this.area = 0; this.t0 = 0; this.mark = 0;
      this.rejected = 0; this.timedOut = 0;
    }
    acc() { const n = this.sim.now; this.area += this.busy * (n - this.t0); this.t0 = n; }
    acquire(resume, timeout) {
      if (this.busy < this.cap) { this.acc(); this.busy++; resume(true); return; }
      if (this.q.length >= this.maxQ) { this.rejected++; resume(false); return; }
      const w = { resume, dead: false };
      this.q.push(w);
      if (timeout == null) return;
      this.sim.at(this.sim.now + timeout, () => {
        if (w.dead) return;
        w.dead = true; this.q.splice(this.q.indexOf(w), 1); this.timedOut++; resume(false);
      });
    }
    release() { this.acc(); this.busy--; this.admit(); }
    admit() {
      while (this.busy < this.cap && this.q.length) {
        const w = this.q.shift();
        w.dead = true; this.acc(); this.busy++; w.resume(true);
      }
    }
    setCap(n) { this.acc(); this.cap = n; this.admit(); }
  }
  /* Several independent pools reported as one (GL bucket rows). */
  class PoolGroup {
    constructor(pools) { this.p = pools; this.mark = 0; }
    get cap() { return this.p.reduce((s, x) => s + x.cap, 0); }
    get area() { return this.p.reduce((s, x) => s + x.area, 0); }
    get q() { return this.p.flatMap((x) => x.q); }
    acc() { this.p.forEach((x) => x.acc()); }
  }
  /* Row locks (SELECT ... FOR UPDATE). SQL Server waits on a row lock with no timeout by default. */
  class Locks {
    constructor() { this.m = new Map(); this.hot = {}; } // hot: peak waiters per contended row this second
    lock(k, resume) {
      const l = this.m.get(k);
      if (!l) { this.m.set(k, { q: [] }); resume(true); return; }
      l.q.push(resume);
      this.hot[k] = Math.max(this.hot[k] || 0, l.q.length);
    }
    unlock(k) { const l = this.m.get(k), n = l.q.shift(); if (n) n(true); else this.m.delete(k); }
  }

  /* =====================================================================
   * Azure SQL DTU tiers (doc) and statement costs (estimate).
   * ===================================================================== */
  const SQL_TIERS = { // dtu/workers: Microsoft DTU single-database limits. logMs: estimate (S0/S1 sit on HDD page blobs)
    basic: { dtu: 5,   workers: 30,  logMs: 8, label: 'Basic · 5 DTU' },
    s0:    { dtu: 10,  workers: 60,  logMs: 8, label: 'S0 · 10 DTU' },
    s1:    { dtu: 20,  workers: 90,  logMs: 8, label: 'S1 · 20 DTU' },
    s2:    { dtu: 50,  workers: 120, logMs: 3, label: 'S2 · 50 DTU' },
    s3:    { dtu: 100, workers: 200, logMs: 3, label: 'S3 · 100 DTU' },
  };
  const STMT_MS = { sel: 0.9, ins: 1.2, upd: 1.1, lock: 1.0, sum: 2.0 }; // ms of DTU capacity at 100 DTU (estimate)
  const GL_BUCKETS = 16;

  /* Where every number comes from. The UI renders this list so nobody mistakes an estimate for a measurement. */
  const INPUTS = [
    { k: 'Transfer route', v: 'gateway to transfer-orchestrator (ledger-mutation-engine has no k8s manifest)', s: 'code', ref: 'k8s/05_gateway_service.yaml, gateway application.yml' },
    { k: 'Rate limits', v: 'transfers 20/s burst 40; accounts+auth 10/s burst 20; key = X-Forwarded-For or remote IP', s: 'code', ref: 'gateway application.yml, RateLimiterConfig.java' },
    { k: 'Client IP at the gateway', v: 'one node IP (LoadBalancer with default externalTrafficPolicy SNATs)', s: 'code', ref: 'k8s/05_gateway_service.yaml' },
    { k: 'App timeouts', v: 'transfer 4 s; balance poll 3 s, every 5 s per open app', s: 'code', ref: 'aurabank_core/lib/services/bank_service.dart:1584,1121; home_screen.dart:53' },
    { k: 'Orchestrator timeouts', v: 'limits 2 s, risk 2 s, CBS 5 s; risk failure falls back to ALLOW below PHP 10,000', s: 'code', ref: 'LimitClientService, RiskClientService, CbsClientService' },
    { k: 'Circuit breaker', v: 'window 10, 50% failures, 10 s open, 3 half-open probes; only timeouts count', s: 'code', ref: 'transfer-orchestrator application.properties' },
    { k: 'Retry', v: 'configured for 3 attempts, modelled as inert because the CB fallback turns every failure into an ignored 503', s: 'estimate', ref: 'Resilience4j aspect order (Retry outside CB); not verified at runtime' },
    { k: 'Customer limit lock', v: 'Redis SET NX polled every 50 ms for up to 2 s, 30 s TTL, held until the transfer ends', s: 'code', ref: 'account-service LimitService.java' },
    { k: 'Pools', v: 'Tomcat 200 + 100 queued; Hikari 15 on account-service and CBS; Hikari wait 20 s / 30 s', s: 'code', ref: 'application-azure.properties (account-service, t24-mock-cbs)' },
    { k: 'CBS posting', v: '43 SQL statements in one transaction; GL row 20100 read-modify-written on every intra-bank transfer', s: 'code', ref: 'CbsFundsTransferService.java:106-505 (assigned ids make save() a merge: SELECT then INSERT)' },
    { k: 'Events per transfer', v: '6 Kafka events (5 status + 1 executed) on a 3-partition topic', s: 'code', ref: 'CbsFundsTransferService.java:420-470, KafkaConfig.java' },
    { k: 'Consumers', v: 'CBS audit: 1 thread per listener; notification: concurrency 4 on 3 partitions', s: 'code', ref: 'CbsAuditSelfConsumptionService.java, KafkaConsumerConfig.java:55' },
    { k: 'Pod CPU limits', v: 'gateway, orchestrator, account, risk 1000m; CBS 2000m; redis 100m; postgres 200m', s: 'code', ref: 'k8s/*.yaml' },
    { k: 'Cluster', v: 'run_azure.sh: 2x Standard_B2s (scales to 4), SQL S1. deploy_budget_azure.sh: 1x B2s, Basic', s: 'code', ref: 'infrastructure/azure/run_azure.sh, deploy_budget_azure.sh' },
    { k: 'SQL tier limits', v: 'DTU and worker caps per tier; Basic, S0 and S1 sit on HDD page blobs', s: 'doc', ref: 'learn.microsoft.com resource-limits-dtu-single-databases' },
    { k: 'Statement cost', v: 'select 0.9 ms, insert 1.2, update 1.1, SUM 2.0 at 100 DTU, scaling linearly with DTU', s: 'estimate', ref: 'calibrate with the SQL cost multiplier against a real run' },
    { k: 'B2s CPU credits', v: 'baseline 0.4 vCPU per node; 5x slowdown under load once banked credits run out', s: 'estimate', ref: 'Microsoft B-series credit model; exact B2s numbers not verified' },
    { k: 'Background CPU', v: '15% of node CPU taken by system pods, idle JVMs, kafka, redis, postgres', s: 'estimate', ref: 'measure with kubectl top' },
    { k: 'CPU per request', v: 'orchestrator ~3 ms, CBS ~15 ms, risk 1.5 ms, account 0.5 to 1.8 ms, BCrypt(10) 65 ms', s: 'estimate', ref: 'measure with APM' },
    { k: 'Round trips', v: 'AKS to Azure SQL 1.2 ms, in-cluster Redis 0.25 ms', s: 'estimate', ref: 'same-region assumption' },
    { k: 'Balance cache hit', v: '80% (30 s TTL, 5 s poll, evicted on every posting)', s: 'estimate', ref: 'BalanceInquiryService.java' },
    { k: 'User retry', v: '35% tap again once, 2 s after a failure, with a new idempotency key', s: 'estimate', ref: 'bank_service.dart builds a new key per tap' },
    { k: 'Customer mix', v: '97% PHP 500 to 10,000; 3% PHP 60,000 (above the 50,000 per-transfer cap, so limits reject it)', s: 'estimate', ref: 'account-service application.properties limits.*' },
  ];

  /* =====================================================================
   * Shared harness
   * ===================================================================== */
  const DEFAULTS = {
    arch: 'actual',          // 'actual' (Azure as deployed) | 'ideal' (target design with BFF)
    rps: 6,                  // transfer submissions per second
    readRps: 10,             // balance polls per second (each open app polls every 5 s)
    loginRps: 0,             // logins per second (BCrypt cost 10 on account-service)
    users: 50000,
    hotMerchantPct: 0,       // share of transfers paying ONE merchant account (QR pay)
    riskExtraMs: 0,          // injected risk-service slowdown
    clientTimeoutMs: 4000,   // app gives up on a transfer after 4 s
    readTimeoutMs: 3000,     // app gives up on a balance poll after 3 s
    loginTimeoutMs: 8000,    // estimate
    userRetryPct: 35,        // estimate: share of failed transfers the user taps again
    retryDelayMs: 2000,
    balanceHitPct: 80,       // estimate
    // deployment profile (actual)
    nodes: 2,                // run_azure.sh default
    sqlTier: 's1',           // run_azure.sh default
    cpuCreditsMin: 100,      // banked B2s credits per node, in vCPU-minutes (0 = exhausted)
    bgCpuShare: 0.15,        // estimate
    sqlCostScale: 1,         // calibration multiplier on every SQL statement cost
    corpWifiPct: 0,          // % of all traffic that leaves through ONE shared public IP (office or campus NAT, a demo room)
    // what-if fixes on the actual design
    fixPreserveClientIp: false, // externalTrafficPolicy: Local, so the gateway sees real client IPs
    fixRateKeyByUser: false,    // rate-limit by JWT subject
    fixPersistNew: false,       // Persistable.isNew() or @Version: save() inserts without a SELECT
    fixGlBuckets: false,        // post GL control totals to 16 bucket rows
    seed: 7,
  };
  const OUTCOMES = ['ok', 'rate_limited', 'rejected', 'overloaded', 'timed_out', 'held'];
  const READ_OUTCOMES = ['ok', 'rate_limited', 'overloaded'];
  const RISK_MEAN_MS = 36; // ideal design only: ML scoring on one full core

  function createModel(overrides) {
    const c = Object.assign({}, DEFAULTS, overrides);
    const sim = new Sim(c.seed);
    const locks = new Locks();
    const S = { offered: 0, retries: 0, unscreened: 0, unscreenedGeo: 0, orphanCommits: 0, duplicatePayments: 0,
      notifLagMs: 0, auditLagMs: 0, sqlLimitErrors: 0, hikariTimeouts: 0, scaleEvents: 0, settled: 0,
      dlq: 0, cbOpens: 0, glLostUpdates: 0, events: 0,
      why: { tomcatFull: 0, limitUnavailable: 0, limitLockTimeout: 0, riskTimeout: 0, cbsTimeout: 0, breakerOpen: 0 } };
    const series = [];
    const zero = (keys) => Object.fromEntries(keys.map((k) => [k, 0]));
    const newBucket = (t) => ({ t, offered: 0, out: zero(OUTCOMES), rout: zero(READ_OUTCOMES), lout: zero(READ_OUTCOMES),
      lat: [], rlat: [], util: {}, q: {}, rows: {}, replicas: {}, glHold: [] });
    let cur = newBucket(0);
    const buckets = new Map();
    function allow(key, rate, burst) { // Spring Cloud Gateway RedisRateLimiter token bucket
      let b = buckets.get(key);
      if (!b) { b = { tokens: burst, t: sim.now }; buckets.set(key, b); }
      b.tokens = Math.min(burst, b.tokens + ((sim.now - b.t) / 1000) * rate); b.t = sim.now;
      if (b.tokens < 1) return false;
      b.tokens -= 1; return true;
    }

    /* =================================================================
     * ACTUAL: what infrastructure/k8s + run_azure.sh deploy.
     * ================================================================= */
    let A = null;
    function buildActual() {
      const tier = SQL_TIERS[c.sqlTier] || SQL_TIERS.s1;
      const nodes = Math.max(1, c.nodes | 0);
      const glB = Array.from({ length: c.fixGlBuckets ? GL_BUCKETS : 1 }, () => new Pool(sim, 1));
      const P = {
        gw: new Pool(sim, 1),                 // gateway CPU quota 1000m
        gwLoop: new Pool(sim, 4),             // Reactor Netty event-loop threads; blocking Lettuce calls hold one
        orch: new Pool(sim, 200, 100),        // transfer-orchestrator Tomcat threads + accept queue
        orchCpu: new Pool(sim, 1),
        acct: new Pool(sim, 200, 100),        // account-service Tomcat
        acctCpu: new Pool(sim, 1),
        acctHikari: new Pool(sim, 15),        // HikariPool-AccountService maximum-pool-size=15
        risk: new Pool(sim, 1),               // Python GIL: one thread runs bytecode at a time
        cbs: new Pool(sim, 200, 100),         // t24-mock-cbs Tomcat
        cbsCpu: new Pool(sim, 2),             // 2000m limit
        cbsHikari: new Pool(sim, 15),         // master maximum-pool-size=15
        sqlW: new Pool(sim, tier.workers),    // concurrent workers; the next request fails with error 10928
        sqlCpu: new Pool(sim, 1),             // DTU capacity as one server whose speed scales with DTU
        redis: new Pool(sim, 1),              // single-threaded, 100m limit
        pg: new Pool(sim, 1),                 // postgres audit pod, 200m
        audit: new Pool(sim, 1),              // one consumer thread per @KafkaListener
        notif: new Pool(sim, 3),              // concurrency 4, but the topic has 3 partitions
        notifCpu: new Pool(sim, 1),
        node: new Pool(sim, 2 * nodes),       // Standard_B2s: 2 vCPU per node
        gl: glB.length === 1 ? glB[0] : new PoolGroup(glB), // global GL row 20100, or its 16 buckets
      };
      return {
        P, glB, tier, nodes,
        sqlScale: (100 / tier.dtu) * c.sqlCostScale,
        credits: c.cpuCreditsMin * 60 * nodes,      // vCPU-seconds
        maxCredits: 576 * 60 * nodes,               // estimate: B2s cap
        speed: 1 - c.bgCpuShare, nodeMark: 0,
        userLocks: new Map(),                        // Redis limits:lock:<user>
        cb: { st: 'closed', win: [], until: 0, probes: 0, done: 0, fails: 0 },
      };
    }

    /* ---- primitives ---- */
    function* cpu(pod, ms, cv = 0.5) { // a core from the pod's quota AND a core on the shared node
      yield { acq: pod }; yield { acq: A.P.node };
      yield sim.svc(ms / A.speed, cv);
      A.P.node.release(); pod.release();
    }
    function* redis(n = 1) {
      for (let i = 0; i < n; i++) { yield 0.25; yield { acq: A.P.redis }; yield sim.svc(0.25, 0.3); A.P.redis.release(); }
    }
    // One SQL statement. opt.gate: a pool whose slot (a row lock) the statement takes and keeps. opt.lock: a keyed row lock.
    function* stmt(kind, opt) {
      if (!(yield { acq: A.P.sqlW, timeout: 0 })) { S.sqlLimitErrors++; return false; }
      yield 1.2;
      if (opt && opt.gate) {
        const t0 = sim.now;
        yield { acq: opt.gate };
        if (sim.now > t0) S.glLostUpdates++; // total was read before waiting, so the write lands on a stale value
      }
      if (opt && opt.lock) yield { lock: opt.lock, locks };
      yield { acq: A.P.sqlCpu }; yield sim.svc(STMT_MS[kind] * A.sqlScale, 0.6); A.P.sqlCpu.release();
      A.P.sqlW.release();
      return true;
    }
    function* commit() { // log flush
      if (!(yield { acq: A.P.sqlW, timeout: 0 })) { S.sqlLimitErrors++; return false; }
      yield 1.2; yield sim.svc(A.tier.logMs, 0.4);
      A.P.sqlW.release();
      return true;
    }
    function* stmts(kind, n) { for (let i = 0; i < n; i++) if (!(yield* stmt(kind))) return false; return true; }
    function* acctSql(kind, n = 1) { // account-service: each repository call checks a connection out of its 15
      for (let i = 0; i < n; i++) {
        if (!(yield { acq: A.P.acctHikari, timeout: 20000 })) { S.hikariTimeouts++; return false; }
        const ok = yield* stmt(kind);
        A.P.acctHikari.release();
        if (!ok) return false;
      }
      return true;
    }
    function* dlq() { S.dlq++; yield* cpu(A.P.orchCpu, 0.4); } // kafkaTemplate.send to banking.transfers.dlq

    /* ---- circuit breaker (resilience4j cbsService) ---- */
    function cbPermit() {
      const b = A.cb;
      if (b.st === 'open') { if (sim.now < b.until) return false; b.st = 'half'; b.probes = 0; b.done = 0; b.fails = 0; }
      if (b.st === 'half') { if (b.probes >= 3) return false; b.probes++; }
      return true;
    }
    function cbRecord(kind) { // 'ok' | 'fail' | 'ignored' (4xx and 5xx responses are ignored exceptions)
      const b = A.cb;
      const open = () => { b.st = 'open'; b.until = sim.now + 10000; b.win = []; S.cbOpens++; };
      if (b.st === 'half') {
        if (kind === 'ignored') { b.probes--; return; }
        b.done++; if (kind === 'fail') b.fails++;
        if (b.done >= 3) { if (b.fails / 3 >= 0.5) open(); else { b.st = 'closed'; b.win = []; } }
        return;
      }
      if (kind === 'ignored') return;
      b.win.push(kind === 'fail'); if (b.win.length > 10) b.win.shift();
      if (b.win.length >= 10 && b.win.filter(Boolean).length / b.win.length >= 0.5) open();
    }

    /* ---- gateway ---- */
    function rateKey(req) {
      if (c.fixRateKeyByUser) return req.user;
      if (c.fixPreserveClientIp) return req.corp ? 'ip-corp' : 'ip' + req.uidx; // real client IPs; corporate NAT users still share one
      return 'aks-node-snat';
    }
    function* aGateway(req, route) { // route: 't' transfers 20/s burst 40, 'a' accounts+auth 10/s burst 20
      yield* redis(1);                                          // RedisRateLimiter Lua script
      const [rate, burst] = route === 't' ? [20, 40] : [10, 20];
      if (!allow(route + ':' + rateKey(req), rate, burst)) return false;
      yield { acq: A.P.gwLoop };
      yield* cpu(A.P.gw, route === 't' ? 0.7 : 0.5);
      yield* redis(route === 't' ? 3 : 1);                      // blocking Lettuce on the event loop: blacklist (+ idempotency has/set)
      A.P.gwLoop.release();
      return true;
    }

    /* ---- account-service ---- */
    function* aLockUser(user, token, waitMs) { // SET NX PX 30000, polled every 50 ms
      const deadline = sim.now + waitMs;
      for (;;) {
        const l = A.userLocks.get(user);
        if (!l || l.expires <= sim.now) { A.userLocks.set(user, { token, expires: sim.now + 30000 }); yield* redis(1); return true; }
        if (sim.now >= deadline) return false;
        yield* redis(1); yield 50;
      }
    }
    function* aCbsUsage() { // t24-mock-cbs GET /usage/outgoing: two SUM queries on the CBS pool
      if (!(yield { acq: A.P.cbs })) return false;
      yield* cpu(A.P.cbsCpu, 0.8);
      let ok = true;
      for (let i = 0; i < 2 && ok; i++) {
        if (!(yield { acq: A.P.cbsHikari, timeout: 30000 })) { S.hikariTimeouts++; ok = false; break; }
        ok = yield* stmt('sum');
        A.P.cbsHikari.release();
      }
      A.P.cbs.release();
      return ok;
    }
    let tokenSeq = 0;
    function* aLimitEval(req) { // POST /internal/limits/evaluate: customer lock, then 3 reads and the CBS usage call
      if (!(yield { acq: A.P.acct })) { S.why.tomcatFull++; return { state: 'unavailable' }; }
      const token = req.user + ':' + (++tokenSeq);
      let out = { state: 'unavailable' };
      yield* cpu(A.P.acctCpu, 1.8);
      if (!(yield* aLockUser(req.user, token, 2000))) S.why.limitLockTimeout++;
      else {
        let keep = false;
        let ok = yield* acctSql('sel', 3);
        if (ok) { const u = yield { call: aCbsUsage(), timeout: 5000 }; ok = u.ok && u.v === true; }
        if (ok) {
          if (req.amount > 50000) out = { state: 'breach' };    // fully verified per-transfer cap is 50,000
          else { out = { state: 'allowed', token }; keep = true; }
        }
        if (!keep) A.userLocks.delete(req.user);
      }
      A.P.acct.release();
      return out;
    }
    function* aLimitRelease(user, token) { // best effort; the lock expires on its own after 30 s
      if (!(yield { acq: A.P.acct })) return false;
      yield* cpu(A.P.acctCpu, 0.3); yield* redis(1);
      const l = A.userLocks.get(user);
      if (l && l.token === token) A.userLocks.delete(user);
      A.P.acct.release();
      return true;
    }
    function* aGeoLookup() { // GET /internal/accounts/location, @Transactional(readOnly): one connection, two reads
      yield 0.4;
      if (!(yield { acq: A.P.acct })) return false;
      yield* cpu(A.P.acctCpu, 0.9);
      let ok = false;
      if (yield { acq: A.P.acctHikari, timeout: 20000 }) { ok = yield* stmts('sel', 2); A.P.acctHikari.release(); }
      A.P.acct.release();
      return ok;
    }

    /* ---- risk-service (Python ThreadingHTTPServer) ---- */
    function* aRiskScore() { // POST /api/v1/risk/score: rules plus a 0.5 s location lookup that fails open
      yield 0.6;
      yield* cpu(A.P.risk, 1.5);
      const geo = yield { call: aGeoLookup(), timeout: 500 };
      yield c.riskExtraMs;
      return { skipped: !geo.ok || geo.v !== true };
    }

    /* ---- t24-mock-cbs: CbsFundsTransferService.executeTransfer, one @Transactional ---- */
    function* aCbsPost(req) {
      if (!(yield { acq: A.P.cbs })) { S.why.tomcatFull++; return 'refused'; }
      const held = [];
      let out = 'error', gate = null, glAt = 0;
      yield* cpu(A.P.cbsCpu, 4);                                       // OFS parse
      const conn = yield { acq: A.P.cbsHikari, timeout: 30000 };
      if (!conn) { S.hikariTimeouts++; out = 'db_unavailable'; }
      else body: {
        if (!(yield* stmts('sel', 4))) break body;                     // idempotency key, system date, resolve account x2
        for (const r of [req.acct, req.dst].sort()) {                  // PESSIMISTIC_WRITE in ascending id order
          if (!(yield* stmt('lock', { lock: 'bal:' + r }))) break body;
          held.push(r);
        }
        if (!c.fixPersistNew && !(yield* stmts('sel', 2))) break body; // gl_ledger merge SELECTs
        yield* cpu(A.P.cbsCpu, 3);                                     // Hibernate dirty checking
        if (!(yield* stmts('upd', 2)) || !(yield* stmts('ins', 2))) break body; // auto-flush: balances x2, gl_ledger x2
        if (!(yield* stmt('sel'))) break body;                         // gl_balances read #1
        const g = A.glB[Math.floor(sim.rand() * A.glB.length)];        // auto-flush: gl_balances UPDATE takes the row lock
        if (!(yield* stmt('upd', { gate: g }))) break body;
        gate = g; glAt = sim.now;
        if (!(yield* stmt('sel'))) break body;                         // gl_balances read #2
        yield* cpu(A.P.cbsCpu, 5);                                     // build and serialise 6 events
        if (!(yield* stmts('sel', c.fixPersistNew ? 2 : 14))) break body; // tx x2, history x6, outbox x6
        yield* cpu(A.P.cbsCpu, 3);                                     // 6 async Kafka sends + redis eviction
        if (!(yield* stmts('ins', 12)) || !(yield* stmt('upd'))) break body; // commit flush: tx, history x5, outbox x6, GL again
        if (!(yield* commit())) break body;
        out = 'ok';
      }
      if (gate) { gate.release(); cur.glHold.push(sim.now - glAt); }
      for (const r of held) locks.unlock('bal:' + r);
      if (conn) A.P.cbsHikari.release();
      A.P.cbs.release();
      if (out === 'ok') {
        if (++req.fam.commits === 2) S.duplicatePayments++;
        S.events += 6;
        sim.run(aAudit(sim.now)); sim.run(aNotify(sim.now));
      }
      return out;
    }
    function* pgWork(n) { for (let i = 0; i < n; i++) { yield 0.3; yield { acq: A.P.pg }; yield sim.svc(2.5, 0.5); A.P.pg.release(); } }
    function* aAudit(t0) { // cbs-audit-workers: one thread, one audit DB transaction per event
      yield 8;
      yield { acq: A.P.audit };
      for (let i = 0; i < 5; i++) { yield* cpu(A.P.cbsCpu, 0.5); yield* pgWork(1); yield 3; } // status event: insert + commit
      yield* cpu(A.P.cbsCpu, 1.0); yield* pgWork(3); yield 3;                                   // executed event: select + 2 inserts
      A.P.audit.release();
      S.auditLagMs = sim.now - t0;
    }
    function* aOwnerLookup() { // notification-service -> GET /api/v1/accounts/{id}
      yield 0.4;
      if (!(yield { acq: A.P.acct })) return false;
      yield* cpu(A.P.acctCpu, 0.8);
      const ok = yield* acctSql('sel', 2);
      A.P.acct.release();
      return ok;
    }
    function* aNotify(t0) { // notification-service: 5 status events dropped, 1 executed event handled
      yield 8;
      yield { acq: A.P.notif };
      for (let i = 0; i < 5; i++) yield* cpu(A.P.notifCpu, 0.3);
      yield* cpu(A.P.notifCpu, 1.5);
      yield { call: aOwnerLookup(), timeout: 2000 }; yield { call: aOwnerLookup(), timeout: 2000 };
      yield* stmt('sel'); yield* stmt('ins');                          // notificationRepository.save
      A.P.notif.release();
      S.notifLagMs = sim.now - t0;
    }

    /* ---- transfer-orchestrator: TransferOrchestrationService.initiateTransfer ---- */
    function* cbsViaBreaker(req) {
      if (!cbPermit()) { S.why.breakerOpen++; return 'breaker'; }
      const r = yield { call: aCbsPost(req), timeout: 5000 };
      if (!r.ok) { cbRecord('fail'); S.why.cbsTimeout++; return 'timeout'; }
      if (r.v === 'ok') { cbRecord('ok'); return 'ok'; }
      cbRecord(r.v === 'refused' ? 'fail' : 'ignored'); // a refused connection counts; an HTTP 4xx/5xx response is an ignored exception
      return 'error';
    }
    function* aTransfer(req) {
      if (!(yield* aGateway(req, 't'))) return 'rate_limited';
      if (!(yield { acq: A.P.orch })) { S.why.tomcatFull++; return 'overloaded'; }
      let out = 'overloaded', token = null;
      yield* cpu(A.P.orchCpu, 1.2); yield* redis(2);                   // cached response? idempotency lock
      body: {
        const le = yield { call: aLimitEval(req), timeout: 2000 };    // fails closed
        if (!le.ok || le.v.state === 'unavailable') { S.why.limitUnavailable++; yield* dlq(); break body; }
        if (le.v.state === 'breach') { out = 'rejected'; break body; }
        token = le.v.token;
        const rk = yield { call: aRiskScore(), timeout: 2000 };
        if (!rk.ok) { S.unscreened++; S.why.riskTimeout++; }           // contingency scoring: ALLOW below PHP 10,000
        else if (rk.v.skipped) S.unscreenedGeo++;                      // geo-velocity rule fails open
        yield* cpu(A.P.orchCpu, 0.8);
        const r = yield* cbsViaBreaker(req);
        if (r === 'ok') { out = 'ok'; yield* cpu(A.P.orchCpu, 0.5); yield* redis(2); }
        else yield* dlq();
      }
      if (token) yield { call: aLimitRelease(req.user, token), timeout: 2000 };
      yield* redis(1);                                                 // idempotency lock release
      A.P.orch.release();
      return out;
    }
    function* aRead(req) { // GET /api/v1/accounts/{id}/balance: Redis cache-aside, 30 s TTL
      if (!(yield* aGateway(req, 'a'))) return 'rate_limited';
      if (!(yield { acq: A.P.acct })) return 'overloaded';
      yield* cpu(A.P.acctCpu, 0.5); yield* redis(1);
      let ok = true;
      if (sim.rand() * 100 >= c.balanceHitPct) { ok = yield* acctSql('sel', 2); if (ok) yield* redis(1); }
      A.P.acct.release();
      return ok ? 'ok' : 'overloaded';
    }
    function* aLogin(req) { // POST /api/v1/auth/login
      if (!(yield* aGateway(req, 'a'))) return 'rate_limited';
      if (!(yield { acq: A.P.acct })) return 'overloaded';
      yield* cpu(A.P.acctCpu, 65, 0.15);                               // BCryptPasswordEncoder(10)
      yield* redis(3);
      const ok = (yield* acctSql('sel', 2)) && (yield* acctSql('upd'));
      A.P.acct.release();
      return ok ? 'ok' : 'overloaded';
    }
    // B2s CPU credits: income 0.4 vCPU per node; spend = foreground + background cores. An empty bank means 5x slower under load.
    function creditTick() {
      sim.at(sim.now + 1000, () => {
        A.P.node.acc();
        const used = (A.P.node.area - A.nodeMark) / 1000; A.nodeMark = A.P.node.area;
        const demand = used + c.bgCpuShare * 2 * A.nodes;
        A.credits = Math.max(0, Math.min(A.maxCredits, A.credits + 0.4 * A.nodes - demand));
        const throttled = A.credits <= 0 && demand > 0.4 * A.nodes;
        A.speed = (1 - c.bgCpuShare) * (throttled ? 0.2 : 1);
        creditTick();
      });
    }

    /* =================================================================
     * IDEAL: target design (not deployed). Front Door WAF with per-user limits -> Mobile BFF
     * -> transfer command service -> Azure SQL Business Critical 8 vCore.
     * Risk is scored before any lock and fails SAFE (hold for review).
     * Merchant credits land on 16 sub-ledger buckets. CDC -> Event Hubs.
     * Every stateless tier autoscales (HPA, 30 s pod start).
     * ================================================================= */
    let I = null;
    function buildIdeal() {
      const tier = (name, perReplica, min, max, maxQPer) => {
        const pool = new Pool(sim, perReplica * min, maxQPer == null ? Infinity : maxQPer * min);
        return { name, pool, perReplica, min, max, replicas: min, pending: 0, maxQPer };
      };
      const T = {
        bff: tier('bff', 2, 2, 10),
        tx: tier('tx', 2, 2, 12),
        risk: tier('risk', 2, 3, 30, 40),         // 2 gunicorn workers/pod; bulkhead 40 queued per pod
        notif: tier('notif', 4, 2, 16),
      };
      const P = {
        redis: new Pool(sim, 4),
        hikari: new Pool(sim, 80),
        sqlCpu: new Pool(sim, 8),
        sqlW: new Pool(sim, 800),
        replica: new Pool(sim, 8),
        cdc: new Pool(sim, 4),
      };
      return { T, P };
    }
    function* iSql(pool, ms) {
      if (!(yield { acq: I.P.sqlW, timeout: 0 })) { S.sqlLimitErrors++; return false; }
      yield 0.6; yield { acq: pool }; yield sim.svc(ms, 0.5); pool.release(); I.P.sqlW.release();
      return true;
    }
    function* iCpu(t, ms) { yield { acq: I.T[t].pool }; yield sim.svc(ms, 0.5); I.T[t].pool.release(); }
    function* iRedis() { yield { acq: I.P.redis }; yield sim.svc(0.1, 0.3); I.P.redis.release(); yield 0.5; }
    function* iRisk() {
      yield 0.6;
      if (!(yield { acq: I.T.risk.pool })) return false;     // bulkhead full: fail fast
      yield sim.svc(RISK_MEAN_MS, 0.45); yield c.riskExtraMs; I.T.risk.pool.release();
      return true;
    }
    function* iTransfer(req) {
      if (!allow('t:' + req.user, 20, 40)) return 'rate_limited';
      yield 2;
      yield* iCpu('bff', 0.8);
      yield* iCpu('tx', 1.0);
      yield* iRedis();                                               // idempotency SETNX
      const r = yield { call: iRisk(), timeout: 300 };               // scored BEFORE locks, tight deadline
      if (!r.ok || !r.v) return 'held';                              // fail safe: queue for review, no money moves
      if (req.amount > 50000) return 'rejected';                     // same per-transfer cap as the limit service
      if (!(yield { acq: I.P.hikari, timeout: 2000 })) { S.hikariTimeouts++; return 'overloaded'; }
      const dst = req.merchant ? req.dst + '#' + Math.floor(sim.rand() * 16) : req.dst; // sub-ledger buckets
      const rows = [req.acct, dst].sort();
      let ok = true;
      for (const row of rows) {
        yield { lock: 'bal:' + row, locks };
        ok = ok && (yield* iSql(I.P.sqlCpu, 0.3));
      }
      ok = ok && (yield* iSql(I.P.sqlCpu, 0.8));                     // UPDATEs + INSERT tx + outbox row
      if (ok) yield 1;                                               // COMMIT, local SSD + sync replica
      for (const row of rows) locks.unlock('bal:' + row);
      I.P.hikari.release();
      if (!ok) return 'overloaded';
      if (++req.fam.commits === 2) S.duplicatePayments++;
      sim.run(iCdc(sim.now));
      yield* iCpu('bff', 0.3);
      return 'ok';
    }
    function* iRead(req) { // BFF fans out to Redis in parallel; misses go to the readable secondary
      if (!allow('r:' + req.user, 20, 40)) return 'rate_limited';
      yield 2;
      yield* iCpu('bff', 0.6);
      const part = function* () {
        yield* iRedis();
        if (sim.rand() < 0.95) return true;                          // cache hit (evicted on every posting)
        return yield* iSql(I.P.replica, 1.2);
      };
      const res = yield { join: [part(), part()] };
      return res.every(Boolean) ? 'ok' : 'overloaded';
    }
    function* iLogin(req) { yield* iRead(req); return 'ok'; }       // login is not modelled separately in the target design
    function* iCdc(t0) { // change feed -> Event Hubs -> autoscaled consumers
      yield { acq: I.P.cdc }; yield sim.svc(1, 0.5); I.P.cdc.release();
      yield 150;
      yield { acq: I.T.notif.pool }; yield sim.svc(8, 0.8); I.T.notif.pool.release();
      S.notifLagMs = sim.now - t0;
    }
    // HPA: every 15 s, scale a tier toward 60% CPU; a new pod serves after 30 s
    function hpa() {
      sim.at(sim.now + 15000, () => {
        for (const t of Object.values(I.T)) {
          const p = t.pool;
          p.acc();
          const util = (p.area - (t.mark || 0)) / (15000 * p.cap);
          t.mark = p.area;
          if (util > 0.6 && t.replicas + t.pending < t.max) {
            const add = Math.min(t.max - t.replicas - t.pending, Math.max(1, Math.ceil(t.replicas * (util / 0.6 - 1))));
            t.pending += add; S.scaleEvents++;
            sim.at(sim.now + 30000, () => { t.pending -= add; t.replicas += add; resize(t); });
          } else if (util < 0.2 && t.replicas > t.min && !t.pending) { t.replicas--; resize(t); }
        }
        hpa();
      });
    }
    function resize(t) {
      if (t.maxQPer != null) t.pool.maxQ = t.maxQPer * t.replicas;
      t.pool.setCap(t.perReplica * t.replicas);
    }

    if (c.arch === 'ideal') { I = buildIdeal(); hpa(); } else { A = buildActual(); creditTick(); }
    const poolList = c.arch === 'ideal'
      ? Object.assign({}, ...Object.entries(I.T).map(([k, t]) => ({ [k]: t.pool })), I.P)
      : A.P;
    const pickProc = (kind, req) => (c.arch === 'ideal'
      ? (kind === 't' ? iTransfer(req) : kind === 'r' ? iRead(req) : iLogin(req))
      : (kind === 't' ? aTransfer(req) : kind === 'r' ? aRead(req) : aLogin(req)));

    /* ---------- clients: the app abandons a transfer after 4 s, a balance poll after 3 s ---------- */
    function attempt(kind, prev) {
      const uidx = prev ? prev.uidx : Math.floor(sim.rand() * c.users);
      const merchant = prev ? prev.merchant : kind === 't' && sim.rand() * 100 < c.hotMerchantPct;
      const req = prev ? Object.assign({}, prev, { t0: sim.now }) : {
        t0: sim.now, uidx, user: 'u' + uidx, acct: 'A-u' + uidx, merchant, fam: { commits: 0 }, corp: sim.rand() * 100 < c.corpWifiPct,
        dst: merchant ? 'MERCHANT-QR-1' : 'A-u' + Math.floor(sim.rand() * c.users),
        amount: sim.rand() < 0.97 ? 500 + sim.rand() * 9500 : 60000,
      };
      if (kind === 't') { S.offered++; cur.offered++; if (prev) S.retries++; }
      sim.run((function* () {
        const timeout = kind === 't' ? c.clientTimeoutMs : kind === 'r' ? c.readTimeoutMs : c.loginTimeoutMs;
        const r = yield { call: pickProc(kind, req), timeout, orphan: (v) => { if (kind === 't' && v === 'ok') S.orphanCommits++; } };
        const lat = sim.now - req.t0;
        if (kind === 't') {
          const out = r.ok ? r.v : 'timed_out';
          cur.out[out]++;
          if (out === 'ok') { cur.lat.push(lat); S.settled++; }
          else if (!prev && (out === 'timed_out' || out === 'overloaded') && sim.rand() * 100 < c.userRetryPct) {
            sim.at(sim.now + c.retryDelayMs, () => attempt('t', req)); // taps again: same payee, new idempotency key
          }
        } else {
          const out = r.ok ? r.v : 'overloaded';
          (kind === 'r' ? cur.rout : cur.lout)[out]++;
          if (kind === 'r' && out === 'ok') cur.rlat.push(lat);
        }
      })());
    }
    function stream(kind) {
      const rate = kind === 't' ? c.rps : kind === 'r' ? c.readRps : c.loginRps;
      if (rate <= 0) { sim.at(sim.now + 100, () => stream(kind)); return; }
      sim.at(sim.now + sim.exp(1000 / rate), () => { attempt(kind); stream(kind); });
    }
    stream('t'); stream('r'); stream('l');

    (function sample() {
      sim.at(sim.now + 1000, () => {
        for (const [k, p] of Object.entries(poolList)) {
          p.acc();
          cur.util[k] = Math.min(1, (p.area - p.mark) / (1000 * Math.max(1, p.cap)));
          cur.q[k] = p.q.length;
          p.mark = p.area;
        }
        cur.rows = locks.hot; locks.hot = {};
        if (I) for (const [k, t] of Object.entries(I.T)) cur.replicas[k] = t.replicas;
        if (A) {
          for (const [u, l] of A.userLocks) if (l.expires <= sim.now) A.userLocks.delete(u);
          cur.ex = { credits: A.credits / 60 / A.nodes, speed: A.speed, cb: A.cb.st, userLocks: A.userLocks.size };
        }
        cur.notifLag = S.notifLagMs; cur.auditLag = S.auditLagMs;
        series.push(cur);
        if (series.length > 900) series.shift();
        cur = newBucket(sim.now);
        sample();
      });
    })();

    return {
      cfg: c, stats: S, series, pools: poolList, tiers: I ? I.T : null,
      get now() { return sim.now; },
      advance(ms) { sim.runUntil(sim.now + ms); },
      set(patch) { Object.assign(c, patch); }, // load and injection knobs; topology knobs need a rebuild
    };
  }

  function summarize(series, seconds) {
    const win = series.slice(-seconds), n = Math.max(1, win.length);
    const out = Object.fromEntries(OUTCOMES.map((k) => [k, 0]));
    const rout = Object.fromEntries(READ_OUTCOMES.map((k) => [k, 0]));
    const lout = Object.fromEntries(READ_OUTCOMES.map((k) => [k, 0]));
    let offered = 0;
    const lat = [], rlat = [], glHold = [], util = {}, q = {}, rows = {};
    for (const b of win) {
      offered += b.offered;
      for (const k of OUTCOMES) out[k] += b.out[k];
      for (const k of READ_OUTCOMES) { rout[k] += b.rout[k]; lout[k] += b.lout[k]; }
      lat.push(...b.lat); rlat.push(...b.rlat); glHold.push(...b.glHold);
      for (const k in b.util) { util[k] = (util[k] || 0) + b.util[k] / n; q[k] = Math.max(q[k] || 0, b.q[k]); }
      for (const k in b.rows) rows[k] = Math.max(rows[k] || 0, b.rows[k]);
    }
    const pct = (a, p) => { a.sort((x, y) => x - y); return a.length ? a[Math.min(a.length - 1, Math.floor(a.length * p))] : 0; };
    const failed = out.ok + out.rate_limited + out.overloaded + out.timed_out; // rejected (limits) and held (review) are deliberate answers
    const sum = (o) => READ_OUTCOMES.reduce((s, k) => s + o[k], 0);
    const last = win[win.length - 1] || {};
    return {
      offeredRps: offered / n, goodputRps: out.ok / n,
      successPct: failed ? (100 * out.ok) / failed : 100,
      readOkPct: sum(rout) ? (100 * rout.ok) / sum(rout) : 100,
      loginOkPct: sum(lout) ? (100 * lout.ok) / sum(lout) : 100,
      p50: pct(lat, 0.5), p99: pct(lat, 0.99), readP99: pct(rlat, 0.99),
      glHoldP50: pct(glHold, 0.5), glHoldN: glHold.length,
      out, rout, lout, util, q, rows, replicas: last.replicas || {}, ex: last.ex || {},
      notifLag: last.notifLag || 0, auditLag: last.auditLag || 0,
    };
  }

  function runScenario(cfg, seconds = 60, warmup = 20) {
    const m = createModel(cfg);
    m.advance((seconds + warmup) * 1000);
    return Object.assign(summarize(m.series, seconds), { stats: m.stats });
  }

  return { DEFAULTS, OUTCOMES, READ_OUTCOMES, RISK_MEAN_MS, INPUTS, SQL_TIERS, createModel, summarize, runScenario };
});
