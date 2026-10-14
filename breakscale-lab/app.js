/* UI: runs the model in sim-time, draws topology, charts, KPIs and findings. */
(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
  const C = { ok: css('--ok'), rate_limited: css('--limit'), overloaded: css('--hot'), timed_out: css('--late'), held: css('--held'),
    warn: css('--warn'), hot: css('--hot'), accent: css('--accent'), line: css('--line'), muted: css('--muted'), faint: css('--faint') };
  const LABEL = { ok: 'settled', rate_limited: '429 rate limited', overloaded: '503 overloaded', timed_out: 'app timed out', held: 'held / review' };
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const KNOBS = ['rps', 'readRps', 'hotMerchantPct', 'riskExtraMs'];
  const FIXES = ['fixRateKeyByUser', 'fixRiskOutsideTx', 'fixRiskReplicas', 'fixSqlS3'];
  const fmtKnob = { rps: (v) => v + '/s', readRps: (v) => v + '/s', hotMerchantPct: (v) => v + '%', riskExtraMs: (v) => (v ? '+' + v + ' ms' : 'off') };

  const state = { arch: 'actual', cfg: {}, running: true, speed: 2, scenario: 1, model: null, snap: null };

  function rebuild() {
    state.model = AuraSim.createModel({ ...state.cfg, arch: state.arch });
    state.prevStats = { ...state.model.stats };
    state.statHist = [];
    drawTopology();
  }
  function applyScenario(i) {
    state.scenario = i;
    const sc = ARCH[state.arch].scenarios[i];
    state.cfg = { ...AuraSim.DEFAULTS, ...sc.cfg };
    syncControls();
    rebuild();
  }
  function syncControls() {
    for (const k of KNOBS) { $(k).value = state.cfg[k]; $(k + 'Out').textContent = fmtKnob[k](state.cfg[k]); }
    document.querySelectorAll('[data-fix]').forEach((el) => { el.checked = !!state.cfg[el.dataset.fix]; });
    $('fixes').classList.toggle('off', state.arch !== 'actual');
    document.querySelectorAll('#scenarios button').forEach((b, i) => b.setAttribute('aria-pressed', String(i === state.scenario)));
  }
  function renderScenarios() {
    $('scenarios').innerHTML = ARCH[state.arch].scenarios
      .map((s, i) => `<button type="button" data-i="${i}"><b>${s.name}</b><span>${s.note}</span></button>`).join('');
    $('scenarios').querySelectorAll('button').forEach((b) => b.addEventListener('click', () => applyScenario(+b.dataset.i)));
    $('archTitle').textContent = ARCH[state.arch].title;
    $('archSub').textContent = ARCH[state.arch].sub;
    $('rps').max = state.arch === 'ideal' ? 1500 : 60;
    $('readRps').max = state.arch === 'ideal' ? 1500 : 60;
  }

  /* ---------- topology ---------- */
  const NS = 'http://www.w3.org/2000/svg';
  const el = (tag, attrs, parent) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); if (parent) parent.appendChild(e); return e; };
  const NODE_H = 66;
  let topo = null;
  function drawTopology() {
    const svg = $('topo'), A = ARCH[state.arch];
    svg.innerHTML = '';
    for (const z of A.zones) {
      el('rect', { class: 'zone', x: z.x, y: z.y, width: z.w, height: z.h, rx: 12 }, svg);
      el('text', { class: 'zone-label', x: z.x + 12, y: z.y + 18 }, svg).textContent = z.label;
    }
    const byId = Object.fromEntries(A.nodes.map((n) => [n.id, n]));
    const gE = el('g', {}, svg), gD = el('g', {}, svg), gN = el('g', {}, svg);
    const edges = A.edges.map(([a, b, kind]) => {
      const n1 = byId[a], n2 = byId[b];
      const x1 = n1.x + n1.w, y1 = n1.y + NODE_H / 2, x2 = n2.x, y2 = n2.y + NODE_H / 2;
      let d;
      if (x2 > x1 + 10) { const mx = (x1 + x2) / 2; d = `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`; }
      else { // same column: route vertically between card edges
        const up = n2.y < n1.y, sx = n1.x + n1.w / 2, sy = up ? n1.y : n1.y + NODE_H, ex = n2.x + n2.w / 2, ey = up ? n2.y + NODE_H : n2.y;
        d = `M${sx},${sy} C${sx},${(sy + ey) / 2} ${ex},${(sy + ey) / 2} ${ex},${ey}`;
      }
      const path = el('path', { class: 'edge' + (kind === 'async' ? ' async' : ''), d }, gE);
      return { kind, path, len: path.getTotalLength(), dots: [], acc: 0 };
    });
    const nodes = A.nodes.map((n) => {
      const g = el('g', { class: 'node', transform: `translate(${n.x},${n.y})` }, gN);
      el('rect', { class: 'halo', x: -3, y: -3, width: n.w + 6, height: NODE_H + 6, rx: 14, fill: 'none', stroke: C.hot, 'stroke-opacity': 0.35 }, g);
      el('rect', { class: 'card', width: n.w, height: NODE_H, rx: 11 }, g);
      el('text', { class: 't', x: 12, y: 20 }, g).textContent = n.title;
      el('text', { class: 's', x: 12, y: 35 }, g).textContent = n.sub;
      const metric = el('text', { class: 'm', x: n.w - 10, y: 20, 'text-anchor': 'end' }, g);
      el('rect', { class: 'bar-bg', x: 12, y: 47, width: n.w - 24, height: 5, rx: 2.5 }, g);
      const bar = el('rect', { class: 'bar', x: 12, y: 47, width: 0, height: 5, rx: 2.5, fill: C.ok }, g);
      const foot = el('text', { class: 'm', x: 12, y: 61 }, g);
      el('title', {}, g).textContent = `${n.title}: ${n.sub}`;
      return { n, g, metric, bar, foot };
    });
    topo = { edges, nodes, gD };
  }

  function edgeRate(kind, b) {
    const tx = b.offered, rd = Object.values(b.rout).reduce((a, x) => a + x, 0);
    const passed = tx - b.out.rate_limited;
    return { in: tx + rd, all: passed + rd, tx: passed, read: rd, ok: b.out.ok, async: b.out.ok }[kind] || 0;
  }
  function pickOutcomeColor(b) {
    const tot = Object.values(b.out).reduce((a, x) => a + x, 0);
    let r = Math.random() * tot;
    for (const k of AuraSim.OUTCOMES) { r -= b.out[k]; if (r <= 0) return C[k]; }
    return C.ok;
  }
  function updateTopology(b, dt) {
    if (!topo || !b) return;
    for (const v of topo.nodes) {
      const { n } = v;
      const us = (n.pools || []).map((p) => b.util[p] ?? 0);
      const u = us.length ? Math.max(...us) : 0;
      const q = (n.pools || []).reduce((s, p) => s + (b.q[p] || 0), 0);
      const cls = u > 0.95 || q > 50 ? 'hot' : u > 0.7 ? 'warn' : '';
      v.g.setAttribute('class', 'node ' + cls);
      v.bar.setAttribute('width', ((n.w - 24) * Math.min(1, u)).toFixed(1));
      v.bar.setAttribute('fill', cls === 'hot' ? C.hot : cls === 'warn' ? C.warn : C.ok);
      v.metric.textContent = n.pools && n.pools.length ? Math.round(u * 100) + '%' : '';
      let foot = q ? `${q} queued` : '';
      if (n.tier && state.model.tiers) { const t = state.model.tiers[n.tier]; foot = `${t.replicas} pod${t.replicas > 1 ? 's' : ''}${t.pending ? ' +' + t.pending + ' starting' : ''}${q ? ' Â· ' + q + ' queued' : ''}`; }
      if (n.metric === 'outbox') foot = `${b.outbox} events pending`;
      if (n.metric === 'lag') foot = `lag ${(b.notifLag / 1000).toFixed(1)}s` + (foot ? ' Â· ' + foot : '');
      if (n.id === 'client') foot = `${Math.round(b.offered)} tx/s offered`;
      v.foot.textContent = foot;
    }
    if (reduceMotion) return;
    for (const e of topo.edges) {
      const rate = edgeRate(e.kind, b);
      const visual = rate <= 0 ? 0 : Math.min(26, 2 + Math.log2(1 + rate) * 3.2); // compress so 1000/s stays legible
      e.acc += visual * dt;
      while (e.acc >= 1) {
        e.acc -= 1;
        const color = e.kind === 'in' ? pickOutcomeColor(b) : e.kind === 'async' ? C.held : C.accent;
        e.dots.push({ t: 0, node: el('circle', { class: 'dot', r: 2.6, fill: color }, topo.gD) });
      }
      const speed = e.kind === 'async' ? 0.35 : 0.9;
      e.dots = e.dots.filter((d) => {
        d.t += dt * speed;
        if (d.t >= 1) { d.node.remove(); return false; }
        const p = e.path.getPointAtLength(d.t * e.len);
        d.node.setAttribute('cx', p.x.toFixed(1)); d.node.setAttribute('cy', p.y.toFixed(1));
        return true;
      });
    }
  }

  /* ---------- charts (canvas, no dependencies) ---------- */
  function ctx2d(canvas) {
    const r = canvas.getBoundingClientRect(), dpr = devicePixelRatio || 1;
    if (canvas.width !== Math.round(r.width * dpr) || canvas.height !== Math.round(r.height * dpr)) {
      canvas.width = Math.round(r.width * dpr); canvas.height = Math.round(r.height * dpr);
    }
    const g = canvas.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, r.width, r.height);
    g.font = '10.5px "JetBrains Mono", monospace';
    return { g, w: r.width, h: r.height, L: 38, R: 8, T: 18, B: 16 };
  }
  function axes(k, ymax, fmt, log) {
    const { g, w, h, L, R, T, B } = k;
    g.strokeStyle = C.line; g.fillStyle = C.faint; g.lineWidth = 1;
    const ticks = log ? [10, 100, 1000, 10000].filter((v) => v <= ymax * 1.01) : [0, ymax / 2, ymax];
    for (const v of ticks) {
      const y = yOf(k, v, ymax, log);
      g.beginPath(); g.moveTo(L, y + 0.5); g.lineTo(w - R, y + 0.5); g.stroke();
      g.fillText(fmt(v), 2, y + 3.5);
    }
    g.fillText('-' + WINDOW + 's', L, h - 3); g.fillText('now', w - R - 22, h - 3);
    void T; void B;
  }
  function yOf(k, v, ymax, log) {
    const { h, T, B } = k, H = h - T - B;
    if (log) { const lo = 1, f = (Math.log10(Math.max(lo, v)) - 0) / Math.log10(ymax); return T + H * (1 - Math.min(1, f)); }
    return T + H * (1 - Math.min(1, v / ymax));
  }
  const xOf = (k, i, n) => k.L + (k.w - k.L - k.R) * ((WINDOW - n + i) / (WINDOW - 1)); // newest at the right
  const WINDOW = 120;
  const niceMax = (v) => { const p = Math.pow(10, Math.floor(Math.log10(Math.max(1, v)))); return Math.ceil(v / p) * p; };
  const q = (a, p) => { if (!a.length) return NaN; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(s.length * p))]; };

  function drawMix(win) {
    const k = ctx2d($('cMix')), n = win.length;
    const ymax = niceMax(Math.max(5, ...win.map((b) => Math.max(b.offered, Object.values(b.out).reduce((a, x) => a + x, 0)))));
    axes(k, ymax, (v) => String(Math.round(v)));
    const { g } = k, base = new Array(n).fill(0);
    for (const o of AuraSim.OUTCOMES) {
      g.beginPath();
      win.forEach((b, i) => { const y = yOf(k, base[i] + b.out[o], ymax); i ? g.lineTo(xOf(k, i, n), y) : g.moveTo(xOf(k, i, n), y); });
      for (let i = n - 1; i >= 0; i--) g.lineTo(xOf(k, i, n), yOf(k, base[i], ymax));
      g.closePath(); g.fillStyle = C[o]; g.globalAlpha = 0.8; g.fill(); g.globalAlpha = 1;
      win.forEach((b, i) => { base[i] += b.out[o]; });
    }
    g.strokeStyle = '#e7edf4'; g.setLineDash([3, 3]); g.lineWidth = 1.2; g.beginPath();
    win.forEach((b, i) => { const y = yOf(k, b.offered, ymax); i ? g.lineTo(xOf(k, i, n), y) : g.moveTo(xOf(k, i, n), y); });
    g.stroke(); g.setLineDash([]);
  }
  function drawLines(id, win, series, ymax, fmt, log) {
    const k = ctx2d($(id)), n = win.length, { g } = k;
    axes(k, ymax, fmt, log);
    for (const s of series) {
      g.strokeStyle = s.color; g.lineWidth = 1.6; g.beginPath();
      let started = false;
      win.forEach((b, i) => {
        const v = s.get(b);
        if (!Number.isFinite(v)) { started = false; return; }
        const x = xOf(k, i, n), y = yOf(k, v, ymax, log);
        started ? g.lineTo(x, y) : g.moveTo(x, y); started = true;
      });
      g.stroke();
    }
    let lx = k.L + 4;
    for (const s of series) { g.fillStyle = s.color; g.fillRect(lx, 4, 8, 2); g.fillStyle = C.muted; g.fillText(s.label, lx + 11, 9); lx += g.measureText(s.label).width + 24; }
  }
  const SAT_COLORS = ['#4cc2ff', '#f5b84a', '#ff5d5d', '#b18cff'];
  function drawCharts() {
    const win = state.model.series.slice(-WINDOW);
    if (!win.length) return;
    drawMix(win);
    const lats = win.map((b) => ({ p50: q(b.lat, 0.5), p99: q(b.lat, 0.99) }));
    const lmax = Math.max(100, ...lats.map((l) => l.p99 || 0));
    const wl = win.map((b, i) => Object.assign({}, b, lats[i]));
    drawLines('cLat', wl, [{ label: 'p50', color: C.accent, get: (b) => b.p50 }, { label: 'p99', color: C.warn, get: (b) => b.p99 }],
      Math.pow(10, Math.ceil(Math.log10(lmax))), (v) => (v >= 1000 ? v / 1000 + 's' : String(v)), true);
    drawLines('cSat', win, ARCH[state.arch].sat.map(([p, label], i) => ({ label, color: SAT_COLORS[i], get: (b) => 100 * (b.util[p] ?? NaN) })),
      100, (v) => v + '%');
  }

  /* ---------- KPIs and findings (last 20 simulated seconds) ---------- */
  function deltas() {
    const h = state.statHist, now = h[h.length - 1] || state.model.stats, then = h[Math.max(0, h.length - 21)] || now;
    return { cfg: state.model.cfg, total: state.model.stats,
      unscreened: now.unscreened - then.unscreened, orphan: now.orphanCommits - then.orphanCommits, sqlErr: now.sqlLimitErrors - then.sqlLimitErrors };
  }
  function kpi(label, value, unit, tone) { return `<div class="kpi ${tone || ''}"><span>${label}</span><b>${value}<em>${unit || ''}</em></b></div>`; }
  function renderInsights() {
    const m = state.model;
    if (m.series.length < 3) return;
    const s = AuraSim.summarize(m.series, 20), d = deltas();
    const succTone = s.successPct > 98 ? 'good' : s.successPct > 85 ? 'meh' : 'bad';
    $('kpis').innerHTML = [
      kpi('Offered', s.offeredRps.toFixed(1), 'tx/s'),
      kpi('Settled', s.goodputRps.toFixed(1), 'tx/s', succTone),
      kpi('Transfer success', s.successPct.toFixed(1), '%', succTone),
      kpi('p99 latency', s.p99 >= 1000 ? (s.p99 / 1000).toFixed(1) : Math.round(s.p99), s.p99 >= 1000 ? 's' : 'ms', s.p99 > 2000 ? 'bad' : s.p99 > 500 ? 'meh' : 'good'),
      kpi('Dashboard reads OK', s.readOkPct.toFixed(1), '%', s.readOkPct > 99 ? 'good' : 'bad'),
      kpi('Notification delay', (s.notifLag / 1000).toFixed(1), 's', s.notifLag > 5000 ? 'bad' : s.notifLag > 1500 ? 'meh' : 'good'),
      kpi('Approved unscreened', m.stats.unscreened, '', m.stats.unscreened ? 'bad' : 'good'),
      kpi('Debited after app gave up', m.stats.orphanCommits, '', m.stats.orphanCommits ? 'bad' : 'good'),
    ].join('');
    const order = { active: 0, warm: 1, config: 2, idle: 3 };
    const rows = ARCH[state.arch].findings.map((f) => ({ f, r: f.test(s, d) })).sort((a, b) => order[a.r.state] - order[b.r.state]);
    const label = { active: 'breaking now', warm: 'under strain', config: 'config defect', idle: 'holding' };
    $('findings').innerHTML = rows.map(({ f, r }, i) => `
      <li class="${r.state === 'active' ? 'active' : r.state === 'warm' || r.state === 'config' ? 'warm' : ''}">
        <div class="row"><span class="rank">${String(i + 1).padStart(2, '0')}</span><h3>${f.title}</h3><span class="state">${label[r.state]}</span></div>
        <p class="evidence">${r.ev}</p>
        <p>${f.why}</p>
        <p class="fix"><b>Fix:</b> ${f.fix}${f.fixKey && state.arch === 'actual' ? ` <button class="btn tiny" data-try="${f.fixKey}">${state.cfg[f.fixKey] ? 'Undo' : 'Try it'}</button>` : ''}</p>
        <p><code>${f.code}</code></p>
      </li>`).join('');
    $('findings').querySelectorAll('[data-try]').forEach((b) => b.addEventListener('click', () => toggleFix(b.dataset.try)));
  }
  function toggleFix(key) { state.cfg[key] = !state.cfg[key]; syncControls(); rebuild(); }

  /* ---------- loop ---------- */
  let last = performance.now(), uiAcc = 0, lastBucketCount = 0;
  function frame(t) {
    const dt = Math.min(0.1, (t - last) / 1000); last = t;
    const m = state.model;
    if (state.running) {
      m.advance(dt * 1000 * state.speed);
      if (m.series.length !== lastBucketCount) { lastBucketCount = m.series.length; state.statHist.push({ ...m.stats }); if (state.statHist.length > 60) state.statHist.shift(); }
    }
    const b = m.series[m.series.length - 1];
    updateTopology(b, state.running ? dt : 0);
    uiAcc += dt;
    if (uiAcc > 0.5) { uiAcc = 0; drawCharts(); renderInsights(); }
    requestAnimationFrame(frame);
  }

  /* ---------- controls ---------- */
  function wire() {
    document.querySelectorAll('.seg button').forEach((b) => b.addEventListener('click', () => {
      state.arch = b.dataset.arch;
      document.querySelectorAll('.seg button').forEach((x) => x.setAttribute('aria-selected', String(x === b)));
      renderScenarios(); applyScenario(1); $('sweepBox').hidden = true;
    }));
    for (const k of KNOBS) $(k).addEventListener('input', (e) => {
      state.cfg[k] = +e.target.value; $(k + 'Out').textContent = fmtKnob[k](state.cfg[k]);
      state.model.set({ [k]: state.cfg[k] }); // live: load and injected faults change without a restart
    });
    document.querySelectorAll('[data-fix]').forEach((x) => x.addEventListener('change', () => toggleFix(x.dataset.fix)));
    $('play').addEventListener('click', () => {
      state.running = !state.running;
      $('play').setAttribute('aria-label', state.running ? 'Pause simulation' : 'Resume simulation');
      $('play').innerHTML = state.running ? '<svg viewBox="0 0 24 24"><path d="M8 5v14M16 5v14"/></svg>' : '<svg viewBox="0 0 24 24"><path d="M7 5l12 7-12 7z"/></svg>';
    });
    $('speed').addEventListener('change', (e) => { state.speed = +e.target.value; });
    $('reset').addEventListener('click', rebuild);
    $('sweep').addEventListener('click', runSweep);
  }

  /* ---------- capacity sweep: both designs, same method, fresh run per level ---------- */
  function runSweep() {
    const btn = $('sweep'), jobs = [];
    for (const series of ['actual', 'actualKey', 'ideal']) for (const rps of ARCH[series === 'ideal' ? 'ideal' : 'actual'].sweep) jobs.push({ series, arch: series === 'ideal' ? 'ideal' : 'actual', rps });
    const res = { actual: [], actualKey: [], ideal: [] };
    btn.disabled = true;
    const keep = { hotMerchantPct: state.cfg.hotMerchantPct, riskExtraMs: state.cfg.riskExtraMs };
    const fixes = state.arch === 'actual' ? Object.fromEntries(FIXES.map((f) => [f, !!state.cfg[f]])) : {};
    (function next(i) {
      if (i === jobs.length) { btn.disabled = false; btn.textContent = 'Run capacity sweep'; drawSweep(res, keep, fixes); return; }
      btn.textContent = `Sweeping ${i + 1}/${jobs.length}â€¦`;
      setTimeout(() => {
        const j = jobs[i];
        const cfg = { ...AuraSim.DEFAULTS, ...keep, ...(j.arch === 'actual' ? fixes : {}), arch: j.arch, rps: j.rps, readRps: Math.min(j.rps, 400) };
        if (j.series === 'actualKey') cfg.fixRateKeyByUser = true;
        const r = AuraSim.runScenario(cfg, 30, j.arch === 'ideal' ? 180 : 20); // ideal: let HPA finish scaling
        res[j.series].push({ rps: j.rps, good: r.goodputRps, p99: r.p99, success: r.successPct, held: r.out.held / 30 });
        next(i + 1);
      }, 0);
    })(0);
  }
  // knee: first level where settled + deliberately held (maker-checker, review) drops below 90% of offered
  function knee(rows) { const k = rows.find((r) => r.good + r.held < 0.9 * r.rps); return k ? k.rps : null; }
  function drawSweep(res, keep, fixes) {
    $('sweepBox').hidden = false;
    const k = ctx2d($('cSweep')), { g, w, h } = k, L = 46, R = 16, T = 14, B = 26;
    const X = (v) => L + (w - L - R) * (Math.log10(v) / Math.log10(1500));
    const Y = (v) => T + (h - T - B) * (1 - Math.log10(Math.max(1, v)) / Math.log10(1500));
    g.strokeStyle = C.line; g.fillStyle = C.faint;
    for (const v of [1, 10, 100, 1000]) {
      g.beginPath(); g.moveTo(L, Y(v) + 0.5); g.lineTo(w - R, Y(v) + 0.5); g.stroke(); g.fillText(v + '/s', 4, Y(v) + 4);
      g.beginPath(); g.moveTo(X(v) + 0.5, T); g.lineTo(X(v) + 0.5, h - B); g.stroke(); g.fillText(v + '/s', X(v) + 3, h - 8);
    }
    g.fillText('â†‘ settled/s   â†’ offered/s', w - R - 180, T + 10);
    g.setLineDash([4, 4]); g.strokeStyle = C.faint; g.beginPath(); g.moveTo(X(1), Y(1)); g.lineTo(X(1500), Y(1500)); g.stroke(); g.setLineDash([]);
    const plot = (rows, color, name, dy) => {
      g.strokeStyle = color; g.lineWidth = 2.2; g.beginPath();
      rows.forEach((r, i) => (i ? g.lineTo(X(r.rps), Y(r.good)) : g.moveTo(X(r.rps), Y(r.good))));
      g.stroke(); g.lineWidth = 1;
      rows.forEach((r) => { g.fillStyle = color; g.beginPath(); g.arc(X(r.rps), Y(r.good), 3, 0, 7); g.fill(); });
      const kn = knee(rows);
      if (kn) { const r = rows.find((x) => x.rps === kn); g.fillStyle = color; g.font = '600 11px "Inter Tight", sans-serif'; g.fillText(`${name} bends at ${kn}/s`, X(r.rps) + 8, Y(r.good) + dy); }
      return kn;
    };
    const ka = plot(res.actual, C.hot, 'Actual', -10), kk = plot(res.actualKey, C.warn, 'Actual, per-user key', 22), ki = plot(res.ideal, C.ok, 'Ideal', 16);
    const peak = (rows) => Math.max(...rows.map((r) => r.good)).toFixed(1);
    const fall = (rows) => rows[rows.length - 1].good.toFixed(1);
    const extra = [keep.hotMerchantPct ? `${keep.hotMerchantPct}% to one merchant` : '', keep.riskExtraMs ? `risk +${keep.riskExtraMs} ms` : '',
      ...Object.entries(fixes).filter(([, v]) => v).map(([f]) => f)].filter(Boolean).join(', ');
    $('sweepNote').textContent = `Each point is a fresh measured run (log-log; dashed line = everything settles). Actual is capped by the shared 429 bucket at ${peak(res.actual)}/s${ka ? ` (bends at ${ka}/s)` : ''}. With per-user keys it peaks at ${peak(res.actualKey)}/s${kk ? `, bends at ${kk}/s` : ''} and falls to ${fall(res.actualKey)}/s at ${res.actualKey[res.actualKey.length - 1].rps}/s as risk-in-transaction pins every connection. Ideal, after 3 min of autoscaling, peaks at ${peak(res.ideal)}/s${ki ? `, bends at ${ki}/s` : ''}.${extra ? ' Conditions: ' + extra + '.' : ''}`;
    $('sweepBox').scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'nearest' });
  }

  wire(); renderScenarios(); applyScenario(1);
  requestAnimationFrame(frame);
})();
