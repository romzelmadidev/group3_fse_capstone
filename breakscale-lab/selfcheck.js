// node breakscale-lab/selfcheck.js : asserts each claimed bottleneck appears, and each fix removes it.
const assert = require('assert');
const { runScenario } = require('./engine.js');
const run = (cfg, s = 40, w = 20) => runScenario(cfg, s, w);
const checks = [];
const check = (name, fn) => checks.push([name, fn]);

check('actual: quiet weekday is healthy', () => { const r = run({ rps: 5 }); assert(r.successPct > 95, r.successPct); assert(r.p99 < 1000, r.p99); });
check('actual: one shared 20/s bucket via SNAT', () => { const r = run({ rps: 40, readRps: 0 }); assert(r.out.rate_limited / (40 * 40) > 0.4, r.out.rate_limited); });
check('actual: per-user key lifts the 429 wall but risk-in-tx collapses it', () => {
  const r = run({ rps: 30, fixRateKeyByUser: true });
  assert.strictEqual(r.out.rate_limited, 0); assert(r.goodputRps < 15, r.goodputRps); assert(r.stats.orphanCommits > 0);
});
check('actual: moving risk out of the tx + more risk capacity raises the ceiling', () => {
  const a = run({ rps: 30, fixRateKeyByUser: true });
  const b = run({ rps: 30, fixRateKeyByUser: true, fixRiskOutsideTx: true, fixRiskReplicas: true });
  assert(b.goodputRps > 2 * a.goodputRps, `${a.goodputRps} -> ${b.goodputRps}`);
});
check('actual: slow risk fails open', () => { const r = run({ rps: 8, riskExtraMs: 1600 }); assert(r.stats.unscreened > 100, r.stats.unscreened); });
check('actual: merchant row becomes a queue', () => { const r = run({ rps: 10, hotMerchantPct: 40 }); assert((r.rows['bal:MERCHANT-QR-1'] || 0) > 3, JSON.stringify(r.rows)); });
check('actual: outbox relay backlog grows above ~12 tx/s', () => { const r = run({ rps: 18, fixRateKeyByUser: true, fixRiskOutsideTx: true, fixRiskReplicas: true, fixSqlS3: true }); assert(r.outbox > 200, r.outbox); });
check('ideal: payday peak settles', () => { const r = run({ arch: 'ideal', rps: 400, readRps: 400 }, 40, 90); assert(r.successPct > 90, r.successPct); assert(r.p99 < 500, r.p99); });
check('ideal: slow risk holds, never approves unscreened', () => {
  const r = run({ arch: 'ideal', rps: 100, riskExtraMs: 1600 }, 40, 60);
  assert.strictEqual(r.stats.unscreened, 0); assert(r.out.held > r.out.ok, JSON.stringify(r.out));
});
check('ideal: merchant buckets keep queues short', () => { const r = run({ arch: 'ideal', rps: 300, hotMerchantPct: 40 }, 40, 90); assert(r.successPct > 90, r.successPct); });
check('deterministic: same seed, same numbers', () => { assert.strictEqual(run({ rps: 12 }).goodputRps, run({ rps: 12 }).goodputRps); });

let failed = 0;
for (const [name, fn] of checks) {
  try { fn(); console.log('ok  ', name); } catch (e) { failed++; console.log('FAIL', name, '-', e.message); }
}
console.log(`${checks.length - failed}/${checks.length} passed`);
process.exit(failed ? 1 : 0);
