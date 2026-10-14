(() => {
  const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
  const stage = $('#stage'), slides = $$('.slide'), N = slides.length;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  let cur = -1;

  const fit = () => stage.style.setProperty('--s', Math.min(innerWidth / 1600, innerHeight / 900));
  addEventListener('resize', fit); fit();

  // ---- navigation ----
  const onEnter = { 4: countKmh, 7: () => runSim(simActive) };
  function go(n) {
    n = Math.max(0, Math.min(N - 1, n));
    if (n === cur) return;
    slides.forEach((s, i) => { s.classList.toggle('on', i === n); s.classList.toggle('past', i < n); s.inert = i !== n; });
    cur = n;
    $('#count').textContent = `${n + 1} / ${N}`;
    $('#progress').style.width = `${(n / (N - 1)) * 100}%`;
    history.replaceState(null, '', `#${n + 1}`);
    onEnter[n]?.();
  }
  const typing = () => ['TEXTAREA', 'INPUT'].includes(document.activeElement?.tagName);
  addEventListener('keydown', e => {
    if (typing() && e.key !== 'Escape') return;
    if (['ArrowRight', 'PageDown', ' '].includes(e.key)) { e.preventDefault(); go(cur + 1); }
    else if (['ArrowLeft', 'PageUp'].includes(e.key)) { e.preventDefault(); go(cur - 1); }
    else if (e.key === 'Home') go(0);
    else if (e.key === 'End') go(N - 1);
    else if (e.key === 'f' || e.key === 'F') toggleFs();
    else if (e.key === 'Escape') document.activeElement?.blur();
  });
  const toggleFs = () => document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen?.();
  $('#prev').onclick = () => go(cur - 1);
  $('#next').onclick = () => go(cur + 1);
  $('#fs').onclick = toggleFs;
  let tx = null;
  addEventListener('touchstart', e => { tx = e.touches[0].clientX; }, { passive: true });
  addEventListener('touchend', e => {
    if (tx === null || typing()) return;
    const dx = e.changedTouches[0].clientX - tx; tx = null;
    if (Math.abs(dx) > 60) go(cur + (dx < 0 ? 1 : -1));
  });

  // ---- Gate 0: implied speed counter ----
  function countKmh() {
    const el = $('#kmh'), target = Math.round(6920 / (25 / 60)), t0 = performance.now(), dur = reduce ? 1 : 1600;
    const tick = t => { const k = Math.min(1, (t - t0 - (reduce ? 0 : 700)) / dur), e = 1 - Math.pow(1 - Math.max(0, k), 4);
      el.textContent = `${Math.round(target * e).toLocaleString('en-US')} km/h`; if (k < 1) requestAnimationFrame(tick); };
    requestAnimationFrame(tick);
  }

  // ---- XGBoost dial: same rounding and thresholds as risk-service server.py ----
  const TAU_2FA = 40, TAU_BLOCK = 50;
  const VERDICT = {
    ALLOW: ['v-allow', 'Under the step-up line. Laya still reads the memo and device context before money moves.'],
    REQUIRE_2FA: ['v-2fa', 'Elevated. The customer must pass a second factor before the transfer continues.'],
    BLOCK: ['v-block', 'Critical. The transfer is refused and a SAR draft is queued for compliance.'],
  };
  const setTag = (el, verdict, cls) => { el.className = `v ${cls}`; el.textContent = verdict; };
  function dial() {
    const score = +$('#p').value, verdict = score >= TAU_BLOCK ? 'BLOCK' : score >= TAU_2FA ? 'REQUIRE_2FA' : 'ALLOW';
    $('#pv').textContent = (score / 100).toFixed(2);
    $('#sv').firstChild.nodeValue = score;
    setTag($('#dv'), verdict, VERDICT[verdict][0]);
    $('#wv').textContent = VERDICT[verdict][1];
  }
  $('#p').addEventListener('input', dial); dial();

  // ---- Laya memo demo: keyword lists copied from /risk/memo-check and /risk/score ----
  const TYPOLOGIES = [
    ['Police impersonation scam', 0.94, ['police', 'bail', 'arrest', 'court', 'fbi', 'nbi', 'law enforcement', 'officer', 'impersonation']],
    ['Investment scam', 0.88, ['crypto', 'broker', 'guaranteed', 'urgent investment', 'forex', 'binary', 'high return']],
    ['Scam keyword', null, ['profit', 'task', 'commission', 'prize', 'lottery', 'telegram', 'pampadulas', 'customs', 'emergency']],
  ];
  const THREATS = { remote: 'Remote access malware', call: 'Live call coercion' };
  const chip = t => { const s = document.createElement('span'); s.className = 'chip'; s.textContent = t; return s; };
  function laya() {
    const memo = $('#memo').value.toLowerCase(), out = $('#lf');
    const on = $$('.tg').filter(b => b.getAttribute('aria-pressed') === 'true').map(b => THREATS[b.dataset.t]);
    const hit = TYPOLOGIES.map(([name, p, kws]) => ({ name, p, kws: kws.filter(k => memo.includes(k)) })).find(t => t.kws.length);
    out.replaceChildren();
    if (on.length) out.append(`Device threat: ${on.join(' and ')}. `);
    if (hit) { out.append(`${hit.name}${hit.p ? ` (${hit.p})` : ''} from `); hit.kws.forEach(k => out.append(chip(k))); }
    if (!on.length && !hit) out.append('No scam typology or coercion signal. The model verdict stands.');
    const flagged = on.length || hit;
    setTag($('#lv'), flagged ? 'ADVISORY_WARNING' : 'ALLOW', flagged ? 'v-adv' : 'v-allow');
  }
  $('#memo').addEventListener('input', laya);
  $$('.tg').forEach(b => b.onclick = () => { b.setAttribute('aria-pressed', b.getAttribute('aria-pressed') !== 'true'); laya(); });
  laya();

  // ---- Security layers ----
  function layer(l) {
    $$('.tab').forEach(t => t.setAttribute('aria-selected', t.dataset.l === l));
    $$('.panel').forEach(p => p.classList.toggle('act', p.dataset.l === l));
    $$('.ring').forEach(r => r.classList.toggle('act', r.dataset.l === l));
  }
  $$('.tab').forEach(t => t.onclick = () => layer(t.dataset.l));
  $('[role=tablist]').addEventListener('keydown', e => {
    if (!['ArrowRight', 'ArrowLeft'].includes(e.key)) return;
    e.stopPropagation(); e.preventDefault();
    const tabs = $$('.tab'), i = tabs.indexOf(document.activeElement), j = (i + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    tabs[j].focus(); layer(tabs[j].dataset.l);
  });

  // ---- Simulator. Each stage: [state, status, detail]. States: pass | warn | step | stop | skip ----
  const STAGES = [['KYC limit', 'ph-identification-card'], ['Gate 0', 'ph-shield-check'], ['XGBoost', 'ph-tree-structure'], ['Laya', 'ph-chat-text'], ['Orchestrator', 'ph-fingerprint']];
  const SKIP = ['skip', 'Skipped', 'Not reached.'];
  const SCENARIOS = [
    { name: 'Rent to a saved payee', amt: '₱8,500', tier: 'Basic tier', stages: [
      ['pass', 'Within cap', '₱8,500 of the ₱10,000 Basic cap.'], ['pass', 'Clean', 'Trusted device, normal travel.'],
      ['pass', 'p 0.07, score 7', 'Below the 40 step-up line.'], ['pass', 'No typology', 'Memo "October rent" is ordinary.'],
      ['pass', 'No step-up', 'Under ₱50,000 and score under 60.']],
      result: ['v-allow', 'ALLOW', 'Posted to T24 core banking', 'One pass through every check. The customer sees a receipt.'] },
    { name: 'Payment from a rooted phone', amt: '₱12,000', tier: 'Fully verified', stages: [
      ['pass', 'Within cap', 'Under the ₱50,000 per-transfer cap.'], ['stop', 'BLOCK, 100', 'Rooted device. The environment is compromised.'],
      SKIP, ['pass', 'Writes the reason', 'Cause of suspicion: device tampering.'], SKIP],
      result: ['v-block', 'BLOCK', 'Refused with HTTP 403', 'No money moves. A SAR draft is queued for compliance review.'] },
    { name: '"Bail for my son" memo', amt: '₱25,000', tier: 'Fully verified', stages: [
      ['pass', 'Within cap', 'Under the ₱50,000 per-transfer cap.'], ['pass', 'Clean', 'Trusted device, normal travel.'],
      ['pass', 'p 0.18, score 18', 'The numbers alone look fine.'], ['warn', 'ADVISORY', 'Police impersonation typology, 0.94.'],
      ['warn', 'Waits for customer', 'Cancel, hold 10 min, or proceed.']],
      result: ['v-adv', 'ADVISORY_WARNING', 'Scam advisory shown in the app', 'Nothing moves until the customer acknowledges. A SAR draft is queued.'] },
    { name: 'Tuition payment', amt: '₱50,000', tier: 'Fully verified', stages: [
      ['pass', 'At cap', 'Exactly the ₱50,000 per-transfer cap.'], ['pass', 'Clean', 'Trusted device, normal travel.'],
      ['pass', 'p 0.21, score 21', 'Below the 40 step-up line.'], ['pass', 'No typology', 'Memo "2nd sem tuition" is ordinary.'],
      ['step', 'Biometric', 'Amount reaches ₱50,000. Challenge valid 5 min.']],
      result: ['v-2fa', 'BIOMETRIC', 'Device signs a challenge first', 'The amount is re-checked when the signature returns, then posted.'] },
    { name: 'Basic account, over cap', amt: '₱12,000', tier: 'Basic tier', stages: [
      ['stop', '402', 'Per-transfer cap of ₱10,000 exceeded.'], SKIP, SKIP, SKIP, SKIP],
      result: ['v-adv', 'TX_LIMIT_EXCEEDED', 'Stopped before risk scoring', 'The app says: complete KYC verification to raise your limits.'] },
    { name: 'Risk engine offline', amt: '₱15,000', tier: 'Fully verified', stages: [
      ['pass', 'Within cap', 'Under the ₱50,000 per-transfer cap.'], ['skip', 'No answer', 'Timed out after 2 s.'],
      ['skip', 'No answer', 'Timed out after 2 s.'], ['skip', 'No answer', 'Timed out after 2 s.'],
      ['step', 'Contingency, 65', '₱10,000 or more steps up.']],
      result: ['v-2fa', 'REQUIRE_2FA', 'Fails toward caution', 'Without a score, the orchestrator asks for a second factor instead of approving.'] },
  ];

  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const pipe = $('#pipe'), res = $('#res'), cards = STAGES.map(([title, icon]) => {
    const c = el('div', 'st'), h = el('h5'); h.append(el('i', `ph ${icon}`), title);
    c.append(h, el('div', 'stat'), el('p')); pipe.append(c); return c;
  });
  const buttons = SCENARIOS.map((s, i) => {
    const b = el('button', 'sc'); b.setAttribute('aria-pressed', 'false');
    b.append(el('strong', null, s.name), el('span', 'm', s.amt), el('span', null, s.tier));
    b.onclick = () => runSim(i); $('#scen').append(b); return b;
  });
  let simActive = 0, timers = [];
  function runSim(i) {
    simActive = i; timers.forEach(clearTimeout); timers = [];
    buttons.forEach((b, j) => b.setAttribute('aria-pressed', i === j));
    cards.forEach(c => { c.className = 'st'; });
    res.classList.remove('show');
    const s = SCENARIOS[i], step = reduce ? 0 : 420;
    s.stages.forEach(([state, status, detail], k) => timers.push(setTimeout(() => {
      const c = cards[k]; c.querySelector('.stat').textContent = status; c.querySelector('p').textContent = detail;
      c.className = `st ${state} done`;
    }, 300 + k * step)));
    timers.push(setTimeout(() => {
      const [cls, verdict, title, body] = s.result;
      setTag($('#rv'), verdict, cls); $('#rt').textContent = title; $('#rp').textContent = body;
      res.classList.add('show');
    }, 300 + s.stages.length * step + 120));
  }

  // ---- start ----
  const fromHash = parseInt(location.hash.slice(1), 10);
  go(Number.isFinite(fromHash) ? fromHash - 1 : 0);
})();
