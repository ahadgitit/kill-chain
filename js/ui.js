// Browser front-end: game loop, input (mouse + touch), HUD, base and flight panels,
// EW controls, decision cards, menus, campaign flow and the after-action replay.
(function (KC) {
  const $ = id => document.getElementById(id);
  const { WEAPONS, AIRCRAFT, PRESETS, PRESET_INFO, NIGHTS, AIR_CAP, EW } = KC;
  const { dist, ENEMY } = KC.util;

  const canvas = $('map');
  const R = new KC.Renderer(canvas);
  const ui = { side: 'IN', sel: null, marks: [], speed: 5, deployN: {} };
  let sim = null;
  let evSeen = 0;
  let lastAutoPause = -1e9;
  let last = performance.now();
  let panelTimer = 0;
  let debriefShown = false;
  let campaign = null;      // { side, night, carry, results: [] } while a campaign is running
  let current = null;       // { nightIdx, side, campaign }
  let replay = null;        // after-action replay state

  const NET = { IN: 'IAF data link (ODL/IACCS)', PK: 'Link-17 network' };
  const SIDE_NAME = { IN: 'India', PK: 'Pakistan' };
  const SHORT = { IN: 'IND', PK: 'PAK' };

  function resize() { R.resize(); }
  window.addEventListener('resize', resize);
  resize();
  const THEATRE = [28.4, 70.6, 35.0, 77.6];
  const SUBCONTINENT = [5.0, 60.0, 38.5, 97.0];
  R.fit(...SUBCONTINENT, 10);

  const nightCfg = (idx, carry) => {
    const n = NIGHTS[idx];
    return { name: `NIGHT ${n.n}: ${n.title}`, start: n.start, duration: n.duration, night: n, carry };
  };

  // ---------- attract mode: an AI-vs-AI battle runs behind the main menu ----------
  let attract = null, attractCam = 0;
  const ATTRACT_SHOTS = [
    [29.5, 71.5, 34.8, 77.2], [31.0, 72.5, 34.6, 76.0], [5.0, 60.0, 38.5, 97.0], [28.6, 70.0, 32.6, 75.5], [27.5, 67.5, 36.0, 79.5],
  ];
  function startAttract() {
    attract = new KC.Sim(nightCfg(Math.floor(Math.random() * NIGHTS.length)), null);
    attract.update(60 * 9);
  }
  function stepAttract(dt) {
    if (!attract || attract.over || attract.t > 40 * 60) startAttract();
    attract.update(dt * 6);
    attractCam -= dt;
    if (attractCam <= 0) {
      const shot = ATTRACT_SHOTS[Math.floor(Math.random() * ATTRACT_SHOTS.length)];
      R.flyTo(R.viewFor(...shot, 10), 6000);
      attractCam = 11;
    }
  }
  function bootText() {
    const lines = [
      ['SENSOR NETWORK', 'ONLINE'], ['AIR BASES', '25 ON ALERT'], ['TACTICAL DATA LINK', 'SYNCED'], ['THEATRE', 'PUNJAB / KASHMIR'],
    ];
    const el = $('boot');
    let i = 0;
    const tick = () => {
      if (i >= lines.length) return;
      const [k, v] = lines[i++];
      el.innerHTML += `&gt; ${k.padEnd(20, '.')} <span class="ok">${v}</span>\n`;
      setTimeout(tick, 260);
    };
    el.innerHTML = '';
    tick();
  }
  bootText();

  // ---------- menus ----------
  function loadCampaign() {
    try { return JSON.parse(localStorage.getItem('kc-campaign') || 'null'); } catch (e) { return null; }
  }
  function saveCampaign() {
    try { localStorage.setItem('kc-campaign', JSON.stringify(campaign)); } catch (e) { /* storage unavailable */ }
  }
  function menuHome() {
    const saved = loadCampaign();
    $('menu-modes').innerHTML = `
      <button class="side CAMP" data-go="campaign"><b>CAMPAIGN · 4 NIGHTS</b><span>6–10 May 2025, night by night. Losses, weapon stocks and damaged bases carry over. Highest total wins.</span></button>
      ${saved && saved.night < NIGHTS.length ? `<button class="side CAMP" data-go="continue"><b>CONTINUE CAMPAIGN</b><span>${SIDE_NAME[saved.side]} · next: night ${saved.night + 1}, ${NIGHTS[saved.night].title}. Total so far IND ${saved.totals.IN} · PAK ${saved.totals.PK}.</span></button>` : ''}
      <button class="side NIGHT" data-go="nights"><b>SINGLE NIGHT</b><span>Play any one night: opening strikes, drone night, retaliation or the airbase strikes.</span></button>
      <button class="side TR" data-go="training"><b>TRAINING: MISSILE EVASION</b><span>Four short lessons on long, short and silent shots, and taking your own. Start here.</span></button>`;
    for (const b of document.querySelectorAll('#menu-modes [data-go]')) {
      b.onclick = () => {
        const g = b.dataset.go;
        if (g === 'campaign') menuSide(0, true);
        else if (g === 'continue') { campaign = loadCampaign(); startNight(campaign.night, campaign.side, true); }
        else if (g === 'nights') menuNights();
        else if (g === 'training') KC.training.begin();
      };
    }
  }
  function menuNights() {
    $('menu-modes').innerHTML = NIGHTS.map((n, i) => `
      <button class="side NIGHT" data-i="${i}"><b>NIGHT ${n.n} · ${n.title}</b><span>${n.date}. ${n.summary}</span></button>`).join('') +
      `<button class="link" id="menu-back">BACK</button>`;
    for (const b of document.querySelectorAll('#menu-modes [data-i]')) b.onclick = () => menuSide(+b.dataset.i, false);
    $('menu-back').onclick = menuHome;
  }
  function menuSide(idx, camp) {
    const n = NIGHTS[idx];
    const goal = s => n.targets[s].length ? `Attack ${n.targets[s].length} priority targets` : 'Defend';
    $('menu-modes').innerHTML = `
      <div class="pick-h">${camp ? 'CAMPAIGN' : `NIGHT ${n.n} · ${n.title}`}: CHOOSE YOUR SIDE</div>
      <div class="sides">
        <button class="side IN" data-side="IN"><b>COMMAND INDIA</b><span>${camp ? 'Strike first on night 1, then fight a war of air bases.' : goal('IN') + '.'} Rafale, Su-30MKI, MiG-29, Mirage 2000, Netra, Phalcon, S-400, Harop.</span></button>
        <button class="side PK" data-side="PK"><b>COMMAND PAKISTAN</b><span>${camp ? 'Defend on night 1, then strike back.' : goal('PK') + '.'} J-10CE, JF-17, F-16, Erieye, ZDK-03, HQ-9/P, drone swarms.</span></button>
      </div>
      <button class="link" id="menu-back">BACK</button>`;
    for (const b of document.querySelectorAll('#menu-modes [data-side]')) {
      b.onclick = () => {
        if (camp) { campaign = { side: b.dataset.side, night: 0, carry: null, totals: { IN: 0, PK: 0 }, results: [] }; saveCampaign(); }
        else campaign = null;
        startNight(idx, b.dataset.side, camp);
      };
    }
    $('menu-back').onclick = camp ? menuHome : menuNights;
  }
  menuHome();

  // ---------- game lifecycle ----------
  function start(side, cfg, opts) {
    opts = opts || {};
    ui.side = side;
    ui.sel = null;
    ui.plan = null;
    ui.training = !!opts.training;
    exitReplay();
    document.body.classList.remove('in-menu');
    document.body.classList.toggle('training', ui.training);
    sim = new KC.Sim(cfg, side);
    evSeen = 0;
    debriefShown = false;
    $('ticker').innerHTML = '';
    $('log-body').innerHTML = '';
    $('menu').classList.add('hidden');
    $('card').classList.add('hidden');
    // Fly from wherever the camera is (usually the menu's live battle) down into the theatre.
    if (!opts.fit) R.fit(...SUBCONTINENT, 10);
    R.flyTo(R.viewFor(...(opts.fit || THEATRE), 16), opts.fit ? 1800 : 2600);
    attract = null;
    setSpeed(0);
    return sim;
  }
  function startNight(idx, side, camp) {
    current = { nightIdx: idx, side, campaign: !!camp };
    start(side, nightCfg(idx, camp && campaign ? campaign.carry : null));
    briefing(idx, side);
  }

  function targetLabel(o) {
    const kind = { site: 'site', base: 'air base', sam: 'SAM site', radar: 'radar' }[o.kind];
    return `<b>${o.id}</b> <span class="k">(${kind})</span>`;
  }
  function briefing(idx, side) {
    const n = NIGHTS[idx], them = ENEMY[side];
    const mine = n.targets[side], theirs = n.targets[them];
    const bases = sim.bases.filter(b => b.side === side && Object.values(b.pools).some(p => p.ready));
    const forces = bases.map(b => Object.entries(b.pools).filter(([, p]) => p.ready).map(([ty, p]) => `${b.id}: ${p.ready} ${AIRCRAFT[ty].name}`).join(', ')).join(' · ');
    const tip = {
      1: { IN: 'Tap AMBALA, choose GROUND ATTACK and tap MURIDKE, with 1 or 2 escorts. PATHANKOT\'s Mirages carry short-range SPICE for KOTLI and SIALKOT (MJ). Escorts keep strikers pressing in close, where they hit more often.', PK: 'Watch for ⚠ STRIKER INBOUND warnings and send a CHASE from MINHAS or RAFIQUI to catch strike jets before they reach firing range. Then send a drone GROUND ATTACK from SIALKOT on UDHAMPUR.' },
      2: { IN: 'Send Harop drones from AMRITSAR on GROUND ATTACK against Lahore\'s radar and SAMs. Your fighters scramble on their own against drone swarms near your bases.', PK: 'Send drone GROUND ATTACKs from SIALKOT in several waves: they are slow and cheap. Turn SAM radars OFF if Harops are hunting them.' },
      3: { IN: 'CM-400s have about 100 km of reach, so Pakistani strike jets must come close: CHASE them as soon as the ⚠ STRIKER warning appears. Counter-strike SIALKOT and MURID with Rafales.', PK: 'Send GROUND ATTACKs from RAFIQUI and PESHAWAR with 2 escorts each. CM-400 reaches about 100 km, so expect Indian interceptors.' },
      4: { IN: 'Send Su-30s from HALWARA and JODHPUR on GROUND ATTACK with escorts. BrahMos-A reaches about 170 km. A ceasefire comes at 45 min.', PK: 'CHASE the Su-30s on the ⚠ STRIKER warning, before they fire: BrahMos is fast and hard to intercept. Counter-strike ADAMPUR and PATHANKOT.' },
    }[n.n][side];
    $('debrief-body').innerHTML = `
      <div class="h">NIGHT ${n.n} · ${n.title} <span class="k">${n.date}</span></div>
      <p>${n.summary} You command <b class="${side}">${SIDE_NAME[side].toUpperCase()}</b>. Every aircraft starts on the ground.</p>
      <div class="brief-grid">
        <div><div class="h2">YOUR PRIORITY TARGETS</div>${mine.length ? '<ul class="tlist">' + mine.map(o => `<li>${targetLabel(o)}</li>`).join('') + '</ul><p class="k">10 points each.</p>' : '<p class="k">None tonight: your job is to defend.</p>'}</div>
        <div><div class="h2">ENEMY TARGETS TO DEFEND</div>${theirs.length ? '<ul class="tlist">' + theirs.map(o => `<li>${targetLabel(o)}</li>`).join('') + '</ul><p class="k">8 points for each one still untouched at the end.</p>' : '<p class="k">The enemy is defending tonight.</p>'}</div>
      </div>
      <p class="k"><b>How targets work:</b> every target needs <b>3 hits</b> to be destroyed, and you score for each hit (a priority target is worth 10 when destroyed). Aircraft go down after <b>2 missile hits</b>. <b>Also scores:</b> enemy aircraft shot down (their value), other enemy bases or air defences (up to 6), aircraft destroyed on the ground (half value), strike weapons intercepted (3), drones (1). Escalation at 100% means <b>both sides lose</b>.</p>
      <p class="k"><b>Your forces:</b> ${forces || 'none'}. Up to ${AIR_CAP[side]} aircraft can be airborne at once.</p>
      <p class="tip">💡 ${tip}</p>
      <div class="sides">
        <button class="side ${side}" id="go-begin"><b>BEGIN</b><span>Your AEW&amp;C aircraft take off on their own, and fighters scramble to defend their bases. You choose who to CHASE, ESCORT and send on GROUND ATTACK.</span></button>
      </div>`;
    $('debrief').classList.remove('hidden');
    $('go-begin').onclick = () => {
      $('debrief').classList.add('hidden');
      autoDeploy(NIGHTS[idx].ai[side], side);
      setSpeed(5);
      hint('Tap one of your air bases (□ with a number) and choose a mission');
    };
  }

  // The standard AEW&C orbits and patrols for this night, as player missions.
  // AEW&C orbits fly themselves (with relief flights) for both sides.
  function autoDeploy(plan, side) {
    const P = (la, lo) => KC.geo.project(la, lo);
    for (const [base, la, lo] of plan.aew || []) {
      const b = sim.base(base);
      sim.createMission({ side, kind: 'aew', base, type: b.home, n: 1, preset: 'AEW', at: P(la, lo) });
    }
    renderMissions();
  }

  function showDebrief() {
    debriefShown = true;
    setSpeed(0);
    closeCard();
    const s = sim.summary();
    const me = ui.side, them = ENEMY[me];
    const n = sim.night;
    let verdict;
    if (s.crisis) verdict = 'CRISIS: BOTH SIDES LOSE';
    else verdict = s[me].points > s[them].points ? 'VICTORY ON POINTS' : s[me].points === s[them].points ? 'STALEMATE' : 'DEFEAT ON POINTS';
    if (current && current.campaign && campaign) {
      campaign.results.push({ n: n.n, IN: s.crisis ? 0 : s.IN.points, PK: s.crisis ? 0 : s.PK.points });
      campaign.totals.IN += s.crisis ? 0 : s.IN.points;
      campaign.totals.PK += s.crisis ? 0 : s.PK.points;
      campaign.carry = sim.carryOut();
      campaign.night = current.nightIdx + 1;
      saveCampaign();
    }
    const row = (k, a, b) => `<tr><td>${k}</td><td>${a}</td><td>${b}</td></tr>`;
    const objRows = side => sim.objectives[side].map(o => { const h = Math.min(3, (o.ref && o.ref.hits) || 0); return `<li class="${h ? 'hit' : ''}">${h >= 3 ? '✓' : h ? '◐' : '✗'} ${o.id} ${h}/3</li>`; }).join('');
    const campDone = campaign && current.campaign && campaign.night >= NIGHTS.length;
    $('debrief-body').innerHTML = `
      <div class="h">DEBRIEF · NIGHT ${n.n}: ${sim.endReason || ''}</div>
      <div class="verdict">${verdict}</div>
      <table class="res">
        <tr><th></th><th class="IN">INDIA</th><th class="PK">PAKISTAN</th></tr>
        ${row('Points', `<b>${s.IN.points}</b>`, `<b>${s.PK.points}</b>`)}
        ${row('Priority targets destroyed / damaged', `${s.IN.objDestroyed} / ${s.IN.objDamaged}`, `${s.PK.objDestroyed} / ${s.PK.objDamaged}`)}
        ${row('Other bases / air defences hit', s.IN.otherHits, s.PK.otherHits)}
        ${row('Kills <span class="k">claimed → confirmed</span>', `${s.IN.claimed} → ${s.IN.kills}`, `${s.PK.claimed} → ${s.PK.kills}`)}
        ${row('Aircraft lost in the air', s.IN.lost, s.PK.lost)}
        ${row('Aircraft lost on the ground', s.IN.groundLost, s.PK.groundLost)}
        ${row('Strike weapons intercepted', s.IN.intercepts, s.PK.intercepts)}
        ${row('Drones shot down', s.IN.droneKills, s.PK.droneKills)}
        ${row('Missiles fired', s.IN.shots, s.PK.shots)}
      </table>
      <p class="fine">Claims were what each side's pilots reported during the night. Some were wrong: one was a decoy, some were misses. The confirmed numbers are the truth.</p>
      ${sim.objectives.IN.length ? `<div class="h2">INDIA'S TARGETS</div><ul class="olist">${objRows('IN')}</ul>` : ''}
      ${sim.objectives.PK.length ? `<div class="h2">PAKISTAN'S TARGETS</div><ul class="olist">${objRows('PK')}</ul>` : ''}
      ${campaign && current.campaign ? `<div class="h2">CAMPAIGN TOTAL</div><p class="verdict small">IND ${campaign.totals.IN} · PAK ${campaign.totals.PK}${campDone ? ` · ${campaign.totals[me] > campaign.totals[them] ? 'YOU WIN THE CAMPAIGN' : campaign.totals[me] === campaign.totals[them] ? 'CAMPAIGN DRAWN' : 'YOU LOSE THE CAMPAIGN'}` : ''}</p>` : ''}
      <div class="sides" style="margin-top:14px">
        <button class="side NIGHT" id="watch"><b>WATCH REPLAY</b><span>The whole night from both sides, with nothing hidden.</span></button>
        ${current.campaign && !campDone
          ? `<button class="side ${me}" id="next"><b>NEXT: NIGHT ${n.n + 1}</b><span>${NIGHTS[n.n].title}. Your losses and stocks carry over.</span></button>`
          : `<button class="side ${me}" id="again"><b>PLAY AGAIN</b><span>Same night, same side.</span></button>`}
      </div>
      <button class="link" id="swap">SWITCH SIDES</button>
      <button class="link" id="view">VIEW THE MAP</button>
      <button class="link" id="to-menu">MAIN MENU</button>`;
    $('debrief').classList.remove('hidden');
    $('watch').onclick = () => { $('debrief').classList.add('hidden'); enterReplay(); };
    if ($('next')) $('next').onclick = () => { $('debrief').classList.add('hidden'); startNight(campaign.night, me, true); };
    if ($('again')) $('again').onclick = () => { $('debrief').classList.add('hidden'); startNight(current.nightIdx, me, false); };
    $('swap').onclick = () => { $('debrief').classList.add('hidden'); campaign = null; startNight(current.nightIdx, them, false); };
    $('view').onclick = () => $('debrief').classList.add('hidden');
    $('to-menu').onclick = showMenu;
  }

  // ---------- speed / top bar ----------
  function setSpeed(v) {
    if (v > 0) ui.lastSpeed = v;
    ui.speed = v;
    for (const b of document.querySelectorAll('#speed button')) b.classList.toggle('on', +b.dataset.speed === v);
  }
  for (const b of document.querySelectorAll('#speed button')) b.onclick = () => { setSpeed(+b.dataset.speed); if (replay) replay.speed = +b.dataset.speed; };
  $('btn-sensors').onclick = () => { R.showSensors = !R.showSensors; $('btn-sensors').classList.toggle('on', R.showSensors); };
  $('btn-log').onclick = () => { $('log').classList.toggle('hidden'); $('btn-log').classList.toggle('on'); };
  $('log-close').onclick = () => { $('log').classList.add('hidden'); $('btn-log').classList.remove('on'); };
  let helpResume = null;
  $('btn-help').onclick = () => { helpResume = ui.speed; setSpeed(0); $('help-quit').classList.toggle('hidden', !sim); $('help').classList.remove('hidden'); };
  $('menu-help').onclick = () => { helpResume = null; $('help-quit').classList.add('hidden'); $('help').classList.remove('hidden'); };
  $('help-quit').onclick = () => { $('help').classList.add('hidden'); if (KC.training) KC.training.stop(); showMenu(); };
  $('help-close').onclick = () => { $('help').classList.add('hidden'); if (helpResume) setSpeed(helpResume); };

  let hintTimer = null;
  function hint(text) {
    const h = $('hint');
    h.textContent = text;
    // Sit just above the unit panel when it is open, so it never covers the event ticker.
    const pn = $('panel');
    h.style.bottom = pn.classList.contains('hidden') ? '' : `${pn.getBoundingClientRect().height + 22}px`;
    h.classList.add('show');
    clearTimeout(hintTimer);
    hintTimer = setTimeout(() => h.classList.remove('show'), 3600);
  }

  function pumpEvents() {
    const tk = $('ticker'), lg = $('log-body');
    for (const e of sim.events) {
      if (e.n <= evSeen) continue;
      evSeen = e.n;
      if (e.side !== ui.side && e.side !== 'ALL') continue;
      soundFor(e);
      const html = `<span class="ev-t">${sim.clock(e.t).slice(0, 4)}</span>${escapeHtml(e.text)}`;
      const d = document.createElement('div');
      d.className = 'ev-' + e.cls;
      d.innerHTML = html;
      lg.prepend(d);
      if (e.cls !== 'info' || /New track|PITBULL|refuelled/.test(e.text) === false) {
        const t = d.cloneNode(true);
        tk.appendChild(t);
        setTimeout(() => t.remove(), 8000);
        while (tk.children.length > 6) tk.firstChild.remove();
      }
    }
    while (lg.children.length > 300) lg.lastChild.remove();
  }
  // Sounds for what our side hears about: launches, warnings, locks, hits, take-offs, alarms.
  function soundFor(e) {
    const A = KC.Audio, t = e.text;
    if (e.cls === 'intel') A.play('intel');
    else if (/STRIKER (INBOUND|CLOSING)/.test(t)) A.play('alarm');
    else if (/LAUNCH WARNING/.test(t)) A.play('rwr');
    else if (/MISSILE WARNING|MISSILE ACTIVE/.test(t)) A.play('active');
    else if (/airborne from|scrambles/.test(t)) A.play('takeoff', 0.8);
    else if (/PITBULL/.test(t)) A.play('lock');
    else if (e.cls === 'impact') A.play('impact', e.side === 'ALL' ? 0.9 : 1);
    else if (/ LOST,/.test(t)) A.play('loss');
    else if (e.cls === 'kill' || /DAMAGED|DESTROYED/.test(t)) A.play('hit', 0.8);
    else if (e.cls === 'shot' && /FOX|BIRD AWAY|launched/.test(t)) A.play('launch', /BIRD AWAY/.test(t) ? 0.7 : 1);
  }
  // Browsers only allow sound after the first tap or key press.
  const unlockAudio = () => KC.Audio.unlock();
  window.addEventListener('pointerdown', unlockAudio);
  window.addEventListener('keydown', unlockAudio);
  function syncSound() {
    for (const id of ['btn-sound', 'menu-sound']) {
      const b = $(id);
      if (!b) continue;
      b.textContent = KC.Audio.muted ? (id === 'menu-sound' ? '🔇 SOUND OFF' : '🔇') : (id === 'menu-sound' ? '🔊 SOUND ON' : '🔊');
      b.classList.toggle('on', !KC.Audio.muted);
    }
  }
  for (const id of ['btn-sound', 'menu-sound']) $(id).onclick = () => { KC.Audio.unlock(); KC.Audio.setMuted(!KC.Audio.muted); syncSound(); };
  syncSound();
  const escapeHtml = s => s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));

  function updateTop() {
    $('clock').textContent = sim.clock() + (ui.speed === 0 ? ' ⏸' : '');
    if (ui.training) { $('score').innerHTML = '<span class="in">TRAINING</span>'; return; }
    const me = ui.side;
    const rem = Math.max(0, (sim.night && sim.night.ceasefireAt || sim.duration) - sim.t);
    const mm = Math.floor(rem / 60), ss = String(Math.floor(rem % 60)).padStart(2, '0');
    const esc = Math.round(sim.esc);
    const escCls = esc >= 80 ? 'hot' : esc >= 50 ? 'warm' : '';
    const jammed = sim.ew[ENEMY[me]].jam.until > sim.t;
    $('score').innerHTML =
      `<span class="pts"><span class="in">${SHORT.IN} <b>${sim.points('IN')}</b></span> · <span class="pk"><b>${sim.points('PK')}</b> ${SHORT.PK}</span></span>` +
      `<span class="esc ${escCls}" title="Escalation: at 100% both sides lose">ESC <i><u style="width:${esc}%"></u></i> ${esc}%</span>` +
      `<span>AIR <b>${sim.airborne(me)}/${AIR_CAP[me]}</b></span>` +
      `<span>CLAIMED <b>${sim.score[me].claimed}</b> · LOST <b>${sim.score[me].lost + sim.score[me].groundLost}</b></span>` +
      `<span>${sim.night && sim.night.ceasefireAt ? 'CEASEFIRE' : 'END'} ${mm}:${ss}</span>` +
      (jammed ? '<span class="jam">⚡ DATA LINK JAMMED</span>' : '');
  }

  // ---------- decision cards ----------
  let card = null;
  function showCard(p) {
    card = { p, t0: performance.now(), ttl: 10000, prevSpeed: ui.speed };
    if (ui.speed > 1) setSpeed(1);
    const c = $('card');
    c.innerHTML = `<div class="ct">DECISION <span id="card-t"></span></div><p>${escapeHtml(p.text)}</p><div class="cb">` +
      p.options.map(([k, label, sub]) => `<button data-k="${k}" class="${k === p.def ? 'def' : ''}"><b>${label}</b><span>${sub}</span></button>`).join('') +
      `</div><div class="timer"><i id="card-bar"></i></div>`;
    c.classList.remove('hidden');
    c.querySelectorAll('button').forEach(b => { b.onclick = () => answerCard(b.dataset.k); });
    if (p.ref && (p.ref.kind === 'air' || p.ref.kind === 'base')) {
      if (p.ref.kind === 'air' && p.ref.side === ui.side) select(p.ref);
      R.flyTo({ cx: p.ref.x, cy: p.ref.y, scale: Math.max(R.view.scale, 0.9) }, 700);
    }
  }
  function answerCard(k) {
    if (!card) return;
    sim.answer(card.p, k);
    const prev = card.prevSpeed;
    closeCard();
    if (prev > 1 && ui.speed === 1) setSpeed(prev);
    renderPanel(true);
  }
  function closeCard() {
    card = null;
    $('card').classList.add('hidden');
  }
  function tickCard() {
    if (!sim || ui.training || sim.over || debriefShown) { if (card) closeCard(); return; }
    if (!card) {
      const p = sim.prompts.find(x => !x.done);
      if (p) showCard(p);
      return;
    }
    if (card.p.done) { closeCard(); return; }
    if (ui.speed === 0) { card.t0 += 16; return; }
    const left = card.ttl - (performance.now() - card.t0);
    const bar = $('card-bar');
    if (bar) bar.style.width = `${Math.max(0, left / card.ttl * 100)}%`;
    const tl = $('card-t');
    if (tl) tl.textContent = `${Math.ceil(Math.max(0, left) / 1000)}s`;
    if (left <= 0) answerCard(card.p.def);
  }

  // ---------- selection & panel ----------
  function select(ref) {
    ui.sel = ref ? { ref } : null;
    renderPanel(true);
  }
  let lastBtns = '';
  function renderPanel(force) {
    const p = $('panel');
    if (sim && ui.plan) {
      p.classList.remove('hidden');
      const { info, btns } = describePlan();
      p.innerHTML = `<div class="info">${info}</div><div class="btns">${btns}</div>`;
      lastBtns = '';
      return;
    }
    const ref = ui.sel && ui.sel.ref;
    const gone = !ref || (ref.kind === 'air' && ref.state !== 'air') || (ref.kind === 'cruise' && ref.dead) ||
      (ref.side !== ui.side && ref.kind !== 'site' && ref.kind !== 'base' && !sim.trackFor(ui.side, ref));
    if (!sim || gone) {
      if (ui.sel) ui.sel = null;
      p.classList.add('hidden');
      ui.hudInset = 0;
      lastBtns = '';
      return;
    }
    p.classList.remove('hidden');
    const { info, btns } = describe(ref);
    let infoEl = p.querySelector('.info'), btnEl = p.querySelector('.btns');
    if (!infoEl) {
      p.innerHTML = '<div class="info"></div><div class="btns"></div>';
      infoEl = p.querySelector('.info'); btnEl = p.querySelector('.btns');
    }
    infoEl.innerHTML = info;
    if (force || btns !== lastBtns) { btnEl.innerHTML = btns; lastBtns = btns; }
    btnEl.style.display = btns ? '' : 'none';
    const pr = p.getBoundingClientRect();
    ui.hudInset = pr.right > window.innerWidth - 170 ? pr.height + 12 : 0;
  }

  function head(name, cls, tag) {
    return `<div class="ph"><span class="name ${cls || ''}">${name}</span>${tag ? `<span class="tag">${tag}</span>` : ''}<button class="x" data-act="close">✕</button></div>`;
  }
  const isAG = k => WEAPONS[k].kind === 'cruise';
  function wpnRows(wpn) {
    const aa = Object.entries(wpn).filter(([k, n]) => n && !isAG(k)), ag = Object.entries(wpn).filter(([k, n]) => n && isAG(k));
    const chips = list => list.map(([k, n]) => `<span class="chip">${WEAPONS[k].name} ×${n}</span>`).join('') || '<span class="k">none</span>';
    return `<div class="row"><span class="k">AIR-TO-AIR</span> ${chips(aa)}</div><div class="row"><span class="k">AIR-TO-SURFACE</span> ${chips(ag)}</div>`;
  }
  function status(u) {
    if (u.defending) return '<span class="warn">DEFENDING: missile inbound</span>';
    const o = u.order;
    const tgt = u.engage || (o && o.type === 'attack' ? o.target : null);
    if (tgt) { const tr = sim.trackFor(u.side, tgt); return `ENGAGING ${tr ? 'T' + tr.num : 'target'}`; }
    if (!o) return 'ORBITING';
    if (o.type === 'orbit') return u.def.role === 'aew' ? 'ON STATION' : sim.isDrone(u) ? 'LOITERING' : 'COMBAT AIR PATROL';
    if (o.type === 'sead') return 'HUNTING RADARS';
    if (u.holding) return '<span class="warm">HOLDING: waiting for escort</span>';
    if (o.type === 'strike' || o.type === 'drone') return o.list.length ? `${u.firing ? 'FIRING' : 'INBOUND'} → ${o.list[0].id}` : 'EGRESS';
    if (o.type === 'rtb') return `RETURNING TO ${o.base || u.base}`;
    return o.type.toUpperCase();
  }
  // Ground targets take 3 hits to destroy.
  function hitsRow(ref) {
    const h = Math.min(3, ref.hits || 0);
    const pips = [0, 1, 2].map(i => `<i class="pip ${i < h ? 'on' : ''}"></i>`).join('');
    return `<div class="row"><span class="k">Hits</span> ${pips} ${h >= 3 ? '<span class="warn">DESTROYED</span>' : h ? `<span class="warm">DAMAGED (${h}/3)</span>` : 'intact (0/3)'}</div>`;
  }
  const fmtT = s => `${Math.floor(Math.max(0, s) / 60)}:${String(Math.floor(Math.max(0, s) % 60)).padStart(2, '0')}`;
  const objFor = ref => {
    const mine = sim.objectives[ui.side].find(o => o.ref === ref);
    const theirs = sim.objectives[ENEMY[ui.side]].find(o => o.ref === ref);
    return mine ? `<span class="tag hot">YOUR TARGET${mine.hit ? ' · HIT' : ''}</span>` : theirs ? `<span class="tag def">DEFEND${theirs.hit ? ' · HIT' : ''}</span>` : '';
  };

  // The three missions: CHASE an enemy, ESCORT a strike, GROUND ATTACK a target.
  const MISSION_TXT = {
    chase: ['CHASE', 'Tap an enemy aircraft. Air-to-air missiles.'],
    escort: ['ESCORT', 'Tap one of your ground-attack flights. Air-to-air missiles.'],
    strike: ['GROUND ATTACK', 'Tap a site, radar, SAM or air base.'],
    attack: ['GROUND ATTACK', 'Tap a site, radar, SAM or air base. One-way drones.'],
  };
  const mbtn = (attrs, kind, extra) => `<button class="mbtn" data-act="mission" data-kind="${kind}" ${attrs}><b>${MISSION_TXT[kind][0]}</b><span>${extra || MISSION_TXT[kind][1]}</span></button>`;
  function missionButtons(b, ty) {
    const def = AIRCRAFT[ty];
    const attrs = `data-base="${b.id}" data-type="${ty}"`;
    if (def.role === 'aew') return `<div class="k">AEW&amp;C aircraft fly automatically: they take off, orbit and are relieved on their own.</div>`;
    if (def.role === 'drone') return mbtn(attrs, 'attack');
    let out = mbtn(attrs, 'chase') + mbtn(attrs, 'escort');
    const wk = sim.missionWeapon(ty, 'STRIKE');
    if (wk) out += mbtn(attrs, 'strike', `${WEAPONS[wk].name}, launches from ~${Math.round(WEAPONS[wk].range * 0.85)} km. No air-to-air missiles.`);
    return out;
  }
  function keyStocks() {
    const st = sim.stocks[ui.side];
    return Object.entries(st).map(([k, n]) => `${WEAPONS[k].name} <b>${n}</b>`).join(' · ');
  }

  function describeBase(b) {
    const own = b.side === ui.side;
    const st = !sim.baseOpen(b) ? `<span class="warn">RUNWAY CLOSED · repairs ~${fmtT((b.damage - 59) / 5 * 60)}</span>`
      : b.damage > 0 ? `<span class="warm">DAMAGED ${Math.round(b.damage)}%</span>` : 'OPERATIONAL';
    let info = head(`${b.id} AIR BASE`, b.side) + `<div class="row">${st} ${objFor(b)}</div>` + hitsRow(b);
    if (!own) {
      const types = Object.keys(b.pools).map(ty => AIRCRAFT[ty].name).join(', ');
      info += `<div class="note">${types ? 'Intelligence: ' + types + ' based here.' : 'Divert airfield.'} Send a GROUND ATTACK here from one of your bases. Hits close the runway at 60% damage and can destroy aircraft on the ground.</div>`;
      return { info, btns: '' };
    }
    info += `<div class="row"><span class="k">Airborne</span> ${sim.airborne(ui.side)} / ${AIR_CAP[ui.side]} · <span class="k">Key stocks</span> ${keyStocks()}</div>`;
    let btns = '';
    const types = Object.keys(b.pools);
    if (!types.length) info += `<div class="note">Divert airfield: no aircraft based here, but any of your jets can land, refuel and re-arm.</div>`;
    else info += `<div class="note">Ready fighters here scramble on their own if enemies come close.</div>`;
    for (const ty of types) {
      const p = b.pools[ty], def = AIRCRAFT[ty];
      const up = sim.units.filter(u => u.kind === 'air' && u.state === 'air' && u.base === b.id && u.type === ty && !u.ghost).reduce((a, u) => a + u.count, 0);
      const next = p.rearm.length ? Math.min(...p.rearm) - sim.t : 0;
      const key = b.id + ty;
      const nDeploy = Math.max(1, Math.min(ui.deployN[key] || (def.role === 'drone' ? 4 : 2), Math.max(1, p.ready)));
      ui.deployN[key] = nDeploy;
      btns += `<div class="pool">
        <div class="pool-h"><b>${def.name}</b><span>${p.ready} ready · ${p.rearm.length} re-arming${p.rearm.length ? ` (next ${fmtT(next)})` : ''} · ${up} airborne</span></div>
        ${def.role !== 'aew' ? `<div class="deploy"><span class="k">Send</span>
          <button data-act="n-" data-key="${key}">−</button><span class="n">${nDeploy}</span><button data-act="n+" data-key="${key}" data-max="${p.ready}">+</button><span class="k">aircraft on:</span></div>` : ''}
        <div class="mgrid">${missionButtons(b, ty)}</div></div>`;
    }
    return { info, btns };
  }

  // A mission waiting for its target tap (or, for ground attack, a launch confirmation).
  function describePlan() {
    const pl = ui.plan;
    const who = pl.unit ? `${pl.unit.callsign} (${pl.unit.def.name} ×${pl.unit.count})` : `${pl.n} ${AIRCRAFT[pl.type].name} from <b>${pl.base}</b>`;
    let info = head(MISSION_TXT[pl.kind][0], ui.side) + `<div class="row">${who}</div>`;
    let btns = '';
    if (pl.kind === 'strike' && pl.target) {
      const room = escortRoom();
      const esc = Math.min(pl.escortN || 0, room);
      info += `<div class="row">Target: <b>${pl.target.id}</b> · 3 hits destroy it</div>` +
        `<div class="row k">The striker presses in as close as it can and fires all 3 weapons. Closer shots hit more often and spend less time over enemy air defences. If enemy fighters close in, it fires early from further out.</div>` +
        `<div class="row">Escort from <b>${pl.base}</b>: ${room ? '' : '<span class="k">no spare fighters here. Send one later from another base with ESCORT; the striker waits for it in safe airspace.</span>'}</div>`;
      if (room) btns += `<div class="seg2 wide">` + [0, 1, 2].filter(n => n <= room).map(n => `<button data-act="escn" data-n="${n}" class="${esc === n ? 'on' : ''}">${n ? n + ' ESCORT' + (n > 1 ? 'S' : '') : 'NO ESCORT'}</button>`).join('') + `</div>`;
      btns += `<button data-act="plan-go" class="go">LAUNCH${esc ? ` + ${esc} ESCORT` : ''}</button>`;
    } else {
      info += `<div class="row warm">${{ chase: 'Tap an enemy aircraft (or cruise missile) to chase.', escort: 'Tap one of your ground-attack flights to escort.', strike: 'Tap an enemy target: site, radar, SAM or air base.', attack: 'Tap an enemy target: site, radar, SAM or air base.' }[pl.kind]}</div>`;
    }
    btns += `<button data-act="plan-cancel" class="danger">CANCEL</button>`;
    return { info, btns };
  }
  // Escorts come from the striker's own base: up to 2 fighters left after the strikers.
  function escortRoom() {
    const pl = ui.plan;
    if (pl.unit) return 0;
    const b = sim.base(pl.base), p = b.pools[pl.type];
    if (!p || !PRESETS[pl.type].AIR) return 0;
    return Math.max(0, Math.min(2, p.ready - pl.n));
  }
  function launchPlan() {
    const pl = ui.plan;
    const m = { kind: pl.kind, preset: pl.kind === 'strike' ? 'STRIKE' : pl.kind === 'attack' ? null : 'AIR', target: pl.target, prey: pl.prey, escortUnit: pl.escortUnit };
    const escN = pl.kind === 'strike' ? Math.min(pl.escortN || 0, escortRoom()) : 0;
    const ms = pl.unit ? sim.assignMission(pl.unit, m) : sim.createMission({ ...m, side: ui.side, base: pl.base, type: pl.type, n: pl.n });
    const eb = escN ? { base: pl.base, type: pl.type } : null;
    if (eb) {
      const em = { side: ui.side, kind: 'escort', base: eb.base, type: eb.type, n: escN, preset: 'AIR' };
      if (ms.unit && ms.unit.state === 'air') em.escortUnit = ms.unit; else em.escortOf = ms.id;
      sim.createMission(em);
    }
    ui.plan = null;
    if (ms.unit && ms.unit.state === 'air') select(ms.unit); else select(null);
    const deep = pl.kind === 'chase' && pl.prey && pl.prey.kind === 'air' && KC.geo.sideOf(pl.prey.x, pl.prey.y) !== ui.side;
    const what = pl.kind === 'chase' ? (deep ? 'chasing; the target is over enemy territory, so they will hold near the border and fire when in range' : 'chasing the target') : pl.kind === 'escort' ? 'escorting' : `attacking ${pl.target.id}`;
    hint(ms.status.startsWith('waiting') ? `${ms.name}: ${ms.status}` : `${ms.name}: ${ms.unit ? ms.unit.callsign + ' ' : ''}${what}${eb ? ', escort launching' : ''}. The AI flies it.`);
    renderMissions();
  }
  // Is a picked thing a valid target for the mission being planned?
  function planTarget(pl, hit) {
    if (!hit) return null;
    if (pl.kind === 'chase') return hit.kind === 'enemy' && (hit.ref.kind === 'air' || hit.ref.kind === 'cruise') ? { prey: hit.ref } : null;
    if (pl.kind === 'escort') return hit.kind === 'own' && hit.ref.kind === 'air' && hit.ref !== pl.unit && (sim.strikeWeapon(hit.ref) || (hit.ref.mission && hit.ref.mission.kind === 'strike')) ? { escortUnit: hit.ref } : null;
    const strikeable = hit.ref.side !== ui.side && (hit.kind === 'site' || hit.kind === 'ebase' || (hit.kind === 'enemy' && (hit.ref.kind === 'sam' || hit.ref.kind === 'radar')));
    return strikeable ? { target: { kind: hit.ref.kind, id: sim.targetId(hit.ref) } } : null;
  }

  function describe(ref) {
    const own = ref.side === ui.side;
    if (ref.kind === 'base') return describeBase(ref);
    if (ref.kind === 'site') {
      let info = head(`TARGET: ${ref.id}`, '', objFor(ref)) + hitsRow(ref);
      info += ui.side === 'IN'
        ? `<div class="note">Select a strike flight, then tap this site. The flight launches once it is in weapon range.</div>`
        : `<div class="note">Protect this site. Cruise missiles fly low, so they need AEW&amp;C coverage to be seen in time for an intercept.</div>`;
      return { info, btns: '' };
    }
    if (!own) return describeTrack(ref);
    if (ref.kind === 'sam') {
      const w = WEAPONS[ref.def.weapon];
      return {
        info: head(`${ref.callsign} · ${ref.def.name}`, ref.side, objFor(ref)) +
          (ref.dead ? '<div class="row warn">DESTROYED</div>' : `<div class="row"><span class="k">Missiles:</span> ${ref.ammo} × ${w.name} · <span class="k">reach</span> ~${Math.round(w.range * 0.7)} km · <span class="k">radar</span> ${ref.radarOn ? 'ON' : '<span class="warn">OFF</span>'}</div>`) +
          `<div class="note">Fires automatically at enemies in reach. While its radar is ON it can be found and hunted by anti-radiation drones. Turn it OFF to hide, but then it can't shoot.</div>`,
        btns: ref.dead ? '' : `<button data-act="samradar" class="${ref.radarOn ? 'on' : ''}">RADAR ${ref.radarOn ? 'ON' : 'OFF'}</button>`,
      };
    }
    if (ref.kind === 'radar') {
      return {
        info: head(`${ref.callsign} · GROUND RADAR`, ref.side, objFor(ref)) + (ref.dead ? '<div class="row warn">DESTROYED</div>' : '') +
          `<div class="note">Feeds the ${NET[ref.side]}. Range ~${ref.range} km against fighters at altitude, much less against low-flying cruise missiles and drones.</div>`,
        btns: ref.dead ? '' : `<button data-act="samradar" class="${ref.radarOn ? 'on' : ''}">RADAR ${ref.radarOn ? 'ON' : 'OFF'}</button>`,
      };
    }
    // Own flight
    const u = ref, def = u.def;
    if (u.ghost) return { info: head(`${u.callsign}`, u.side) + `<div class="note">Electronic decoys pretending to be a pair of ${def.name}s. Enemy missiles fired at them are wasted. They vanish in ${fmtT(u.fuel)}.</div>`, btns: '' };
    if (sim.isDrone(u)) {
      return {
        info: head(`${u.callsign} · ${def.name} ×${u.count}`, u.side) + `<div class="row">${status(u)}</div>` +
          `<div class="row"><span class="k">Endurance</span> ${fmtT(u.fuel)}</div><div class="note">${def.note}</div>`,
        btns: mbtn('', 'attack'),
      };
    }
    const threats = u.threats.filter(m => !m.dead).length;
    const near = u.threats.filter(m => !m.dead).sort((a, b) => dist(a, u) - dist(b, u))[0];
    const threatRow = near ? `<div class="row warn">⚠ ${threats} missile${threats > 1 ? 's' : ''} inbound · nearest ${near.w.name}, ${dist(near, u).toFixed(0)} km${u.defending ? ` · evading (${u.defMode === 'notch' ? 'notch' : 'break'})` : ''}</div>` : '';
    const fuelPct = Math.round(100 * Math.min(1, u.fuel / def.fuel));
    const info = head(`${u.callsign} · ${def.name}${u.count > 1 ? ' ×' + u.count : ''}`, u.side) +
      `<div class="row">${status(u)}${u.bingo ? ' · <span class="warn">BINGO</span>' : ''}${u.dmg ? ' · <span class="warn">DAMAGED</span>' : ''}${u.mission ? ` · <span class="k">mission</span> ${u.mission.name}` : ''}</div>` +
      (def.role === 'aew' ? '' : wpnRows(u.wpn)) +
      `<div class="row"><span class="k">Fuel</span><span class="bar"><i style="width:${fuelPct}%;${fuelPct < 30 ? 'background:var(--red)' : ''}"></i></span>` +
      ` · <span class="k">Radar</span> ${def.role === 'aew' ? 'ON (AEW&C)' : u.radarOn ? 'ON' : 'silent'}</div>` +
      threatRow + `<div class="note">${def.note}</div>`;
    let btns = '';
    if (def.role === 'fighter') {
      if (sim.hasAAM(u)) btns += mbtn('', 'chase') + mbtn('', 'escort');
      if (sim.strikeWeapon(u)) btns += mbtn('', 'strike', `${WEAPONS[sim.strikeWeapon(u)].name} ×${u.wpn[sim.strikeWeapon(u)]}`);
    }
    btns += `<button data-act="rtb" class="danger">RETURN TO BASE</button>`;
    return { info, btns };
  }

  function describeTrack(e) {
    const tr = sim.trackFor(ui.side, e);
    if (!tr) return { info: '', btns: '' };
    if (e.kind === 'sam' || e.kind === 'radar') {
      return {
        info: head(`${e.kind === 'sam' ? 'SAM SITE' : 'RADAR'}: ${e.callsign}`, e.side, objFor(e)) + hitsRow(e) +
          `<div class="note">Known from intelligence and its emissions. ${e.kind === 'sam' ? `Estimated reach ~${Math.round(WEAPONS[e.def.weapon].range * 0.7)} km: stay outside the red ring.` : ''} Strike it with a strike flight or send Harop / drones.</div>`,
        btns: '',
      };
    }
    if (e.kind === 'cruise') {
      return { info: head(`T${tr.num} · ${e.w.name.toUpperCase()}`, e.side) + `<div class="note">Inbound to ${sim.targetId(e.tgt)}. Select a fighter and tap it to attempt an intercept.</div>`, btns: '' };
    }
    const seen = e.seen[ui.side] || { by: [] };
    const sources = seen.by.map(id => sim.unit(id)).filter(Boolean).map(u => u.callsign).slice(0, 4).join(', ');
    const hdg = Math.round(((e.hdg * 180 / Math.PI) + 360) % 360);
    const age = sim.t - tr.t;
    const n = tr.count || e.count;
    let info = head(`T${tr.num} · ${tr.q === 'esm' ? e.def.name + '?' : e.def.name + (n > 1 ? ' ×' + n : '')}`, e.side) +
      `<div class="row"><span class="k">Quality</span> ${tr.q === 'esm' ? 'PASSIVE (radar emissions only)' : age > 3 ? `LOST ${Math.round(age)}s ago` : 'RADAR track'}` +
      (tr.q !== 'esm' && age <= 3 ? ` · <span class="k">hdg</span> ${String(hdg).padStart(3, '0')}°` : '') + `</div>`;
    if (sources) info += `<div class="row"><span class="k">Seen by</span> ${sources}</div>`;
    info += `<div class="row"><span class="k">Data-link firing track</span> ${seen.net ? 'YES' : '<span class="warn">NO</span>'}</div>`;
    info += `<div class="note">${e.def.note}<br>Select one of your fighters, then tap this track to attack it.</div>`;
    return { info, btns: '' };
  }

  $('panel').addEventListener('click', ev => {
    const b = ev.target.closest('[data-act]');
    if (!b || !sim) return;
    KC.Audio.play('click');
    const ref = ui.sel && ui.sel.ref;
    const act = b.dataset.act;
    if (act === 'close') { ui.plan = null; select(null); return; }
    if (act === 'mission') {
      const kind = b.dataset.kind;
      if (b.dataset.base) {
        const key = b.dataset.base + b.dataset.type;
        ui.plan = { base: b.dataset.base, type: b.dataset.type, kind, n: ui.deployN[key] || 2 };
      } else if (ref && ref.kind === 'air') ui.plan = { unit: ref, kind };
      else return;
      hint({ chase: 'Tap an enemy aircraft to chase', escort: 'Tap one of your ground-attack flights', strike: 'Tap an enemy target', attack: 'Tap an enemy target' }[kind]);
      renderPanel(true); return;
    }
    if (act === 'plan-cancel') { ui.plan = null; renderPanel(true); return; }
    if (act === 'plan-go') { launchPlan(); return; }
    if (act === 'escn') { ui.plan.escortN = +b.dataset.n; renderPanel(true); return; }
    if (act === 'n-' || act === 'n+') {
      const k = b.dataset.key;
      ui.deployN[k] = Math.max(1, (ui.deployN[k] || 2) + (act === 'n+' ? 1 : -1));
      if (act === 'n+' && b.dataset.max) ui.deployN[k] = Math.min(ui.deployN[k], Math.max(1, +b.dataset.max));
      renderPanel(true); return;
    }
    if (act === 'samradar' && ref) {
      sim.cmdSamRadar(ref, !ref.radarOn);
      hint(ref.radarOn ? `${ref.callsign} radar ON: it can shoot, but drones can home on it` : `${ref.callsign} radar OFF: hidden from anti-radiation drones, but blind`);
      renderPanel(true); return;
    }
    if (!ref || ref.kind !== 'air') return;
    if (act === 'rtb') {
      if (ref.mission) ref.mission.status = 'done';
      sim.cmdRTB(ref);
      hint(`${ref.callsign} returning to ${ref.order.base}: it lands, refuels and re-arms there`);
    }
    renderPanel(true);
  });

  // ---------- picking & commands ----------
  function pick(sx, sy) {
    const cands = [];
    const add = (ref, x, y, kind, bias) => {
      const p = R.toScreen(x, y);
      const d = Math.hypot(p.x - sx, p.y - sy);
      if (d < 26) cands.push({ ref, kind, d: d - bias });
    };
    for (const u of sim.units) {
      if (u.side !== ui.side) continue;
      if (u.kind === 'air' && u.state !== 'air') continue;
      add(u, u.x, u.y, 'own', u.kind === 'air' ? 4 : 0);
    }
    for (const tr of sim.tracks[ui.side].values()) {
      const e = tr.ref;
      if (e.kind === 'cruise' && !R.missileVisible(sim, e, ui.side)) continue;
      const p = tr.static ? tr : (e.kind === 'cruise' ? e : sim.trackPos(tr));
      add(e, p.x, p.y, 'enemy', e.kind === 'air' ? 6 : 1);
    }
    for (const s of sim.sites) add(s, s.x, s.y, 'site', 2);
    for (const b of sim.bases) add(b, b.x, b.y, b.side === ui.side ? 'base' : 'ebase', b.side === ui.side ? -2 : -1);
    cands.sort((a, b) => a.d - b.d);
    return cands[0] || null;
  }

  function onTap(sx, sy) {
    if (!sim || replay) return;
    const w = R.toWorld(sx, sy);
    const hit = pick(sx, sy);
    if (ui.plan) {
      const t = planTarget(ui.plan, hit);
      if (!t) { hint({ chase: 'Tap an enemy aircraft', escort: 'Tap one of your ground-attack flights', strike: 'Tap an enemy site, radar, SAM or air base', attack: 'Tap an enemy site, radar, SAM or air base' }[ui.plan.kind]); return; }
      Object.assign(ui.plan, t);
      if (ui.plan.kind === 'strike' && !ui.plan.unit) {   // confirm, with an optional escort
        if (ui.plan.escortN == null) ui.plan.escortN = Math.min(1, escortRoom());
        renderPanel(true);
        return;
      }
      launchPlan();
      return;
    }
    const s = ui.sel && ui.sel.ref;
    const ownAir = s && s.kind === 'air' && s.side === ui.side && s.state === 'air' && !s.ghost && s.def.role !== 'aew';
    // Shortcut: with a flight selected, tapping a target gives it the matching mission.
    if (ownAir && hit && hit.ref !== s) {
      for (const kind of ['chase', 'strike', 'attack', 'escort']) {
        const can = kind === 'attack' ? sim.isDrone(s) : kind === 'strike' ? !!sim.strikeWeapon(s) : s.def.role === 'fighter' && sim.hasAAM(s);
        if (!can) continue;
        const t = planTarget({ kind, unit: s }, hit);
        if (t) { ui.plan = { unit: s, kind, ...t }; launchPlan(); return; }
      }
    }
    if (hit) {
      if (s === hit.ref) select(null); else select(hit.ref);
      return;
    }
    select(null);
  }

  // ---------- missions list ----------
  function renderMissions() {
    const el = $('missions-body');
    if (!el || !sim) return;
    const list = sim.missions.filter(m => m.side === ui.side && m.status !== 'done');
    $('btn-missions').textContent = `▤ ${list.length}`;
    if ($('missions').classList.contains('hidden')) return;
    el.innerHTML = list.length ? list.map(m => {
      const tgt = m.target ? ` → ${m.target.id}` : m.kind === 'escort' ? ` → ${(sim.missions.find(x => x.id === m.escortOf) || {}).name || ''}` : '';
      const when = m.status === 'scheduled' ? ` (${sim.clock(m.launchAt).slice(0, 4)}Z)` : '';
      return `<div class="mrow" data-id="${m.id}"><span><b>${m.name}</b> ${m.n}× ${AIRCRAFT[m.type].name} · ${m.base}${tgt}<br><span class="k">${m.status}${when}${m.repeat ? ' · relief on' : ''}</span></span>` +
        `${m.kind === 'cap' || m.kind === 'aew' ? `<button data-mact="repeat" class="${m.repeat ? 'on' : ''}" title="Relief flights">⟳</button>` : ''}<button data-mact="cancel" title="Cancel mission">✕</button></div>`;
    }).join('') : '<div class="k">No missions yet. Tap one of your air bases to plan one.</div>';
  }
  $('btn-missions').onclick = () => { $('missions').classList.toggle('hidden'); $('btn-missions').classList.toggle('on'); renderMissions(); };
  $('missions-close').onclick = () => { $('missions').classList.add('hidden'); $('btn-missions').classList.remove('on'); };
  $('missions-body').addEventListener('click', ev => {
    const row = ev.target.closest('.mrow');
    if (!row || !sim) return;
    const ms = sim.missions.find(m => m.id === +row.dataset.id);
    const act = ev.target.closest('[data-mact]');
    if (act && act.dataset.mact === 'cancel') { sim.cancelMission(ms); renderMissions(); return; }
    if (act && act.dataset.mact === 'repeat') { ms.repeat = !ms.repeat; renderMissions(); return; }
    const u = ms.unit && ms.unit.state === 'air' ? ms.unit : null;
    const focus = u || sim.base(ms.base);
    select(focus);
    R.flyTo({ cx: focus.x, cy: focus.y, scale: Math.max(R.view.scale, 0.8) }, 600);
  });

  // ---------- pointer input: tap, drag-pan, pinch-zoom, wheel ----------
  const ptrs = new Map();
  let pinch = null, dragMoved = false, wasMulti = false;
  canvas.addEventListener('pointerdown', e => {
    canvas.setPointerCapture(e.pointerId);
    ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY, x0: e.clientX, y0: e.clientY });
    if (ptrs.size === 1) { dragMoved = false; wasMulti = false; }
    if (ptrs.size === 2) {
      wasMulti = true;
      const [a, b] = [...ptrs.values()];
      pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2 };
    }
  });
  canvas.addEventListener('pointermove', e => {
    if (e.pointerType === 'mouse') {
      const r = canvas.getBoundingClientRect();
      R.cursor = { x: e.clientX - r.left, y: e.clientY - r.top };
    }
    const p = ptrs.get(e.pointerId);
    if (!p) return;
    const dx = e.clientX - p.x, dy = e.clientY - p.y;
    p.x = e.clientX; p.y = e.clientY;
    if (ptrs.size === 1) {
      if (Math.hypot(p.x - p.x0, p.y - p.y0) > 8) dragMoved = true;
      if (dragMoved) { R.flight = null; R.view.cx -= dx / R.view.scale; R.view.cy -= dy / R.view.scale; }
    } else if (ptrs.size === 2 && pinch) {
      const [a, b] = [...ptrs.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      const cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
      R.flight = null;
      R.view.cx -= (cx - pinch.cx) / R.view.scale;
      R.view.cy -= (cy - pinch.cy) / R.view.scale;
      R.zoomAt(cx, cy, d / pinch.d);
      pinch = { d, cx, cy };
    }
  });
  const up = e => {
    if (!ptrs.has(e.pointerId)) return;
    ptrs.delete(e.pointerId);
    if (ptrs.size < 2) pinch = null;
    if (ptrs.size === 0 && !dragMoved && !wasMulti && e.type === 'pointerup') {
      const r = canvas.getBoundingClientRect();
      onTap(e.clientX - r.left, e.clientY - r.top);
    }
  };
  canvas.addEventListener('pointerup', up);
  canvas.addEventListener('pointerleave', () => { R.cursor = null; });
  canvas.addEventListener('pointercancel', up);
  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const r = canvas.getBoundingClientRect();
    R.zoomAt(e.clientX - r.left, e.clientY - r.top, Math.exp(-e.deltaY * 0.0015));
  }, { passive: false });

  window.addEventListener('keydown', e => {
    if (!sim) return;
    if (e.code === 'Space') { e.preventDefault(); if (replay) replay.playing = !replay.playing; else setSpeed(ui.speed ? 0 : ui.lastSpeed || 5); }
    const map = { Digit1: 0, Digit2: 1, Digit3: 5, Digit4: 15, Digit5: 40 };
    if (e.code in map) setSpeed(map[e.code]);
    if (e.code === 'Escape') { ui.plan = null; select(null); }
    const ref = ui.sel && ui.sel.ref;
    if (e.code === 'KeyR' && ref && ref.kind === 'air' && ref.side === ui.side && ref.def.role === 'fighter') {
      ref.radarOn = !ref.radarOn; renderPanel(true);
    }
  });

  // ---------- after-action replay ----------
  function replayState(i) {
    const f = sim.replay[i];
    const units = f.u.map(([id, side, type, x, y, hdg, count, radar, ghost, callsign]) => {
      const orig = sim.unit(id);
      if (AIRCRAFT[type]) return { id, side, kind: 'air', type, def: AIRCRAFT[type], x, y, hdg, spd: AIRCRAFT[type].speed, count, radarOn: !!radar, ghost: !!ghost, callsign, state: 'air', threats: [] };
      return { ...orig, x, y, dead: !count, radarOn: !!radar };
    });
    const missiles = f.m.map(([x, y, side, cruise]) => ({ x, y, side, kind: cruise ? 'cruise' : 'aam', trail: [], seen: {} }));
    const bases = sim.bases.map((b, k) => ({ ...b, damage: f.damage[k], pools: {} }));
    const fx = f.fx.map(([kind, x, y]) => ({ kind, x, y, t: f.t - 1 }));
    return {
      t: f.t, units, missiles, bases, fx, sites: sim.sites, tracks: sim.tracks, isAlive: () => true, isDrone: u => u.def && u.def.role === 'drone',
      baseOpen: b => b.damage < 60,
    };
  }
  function enterReplay() {
    if (!sim || !sim.replay.length) return;
    replay = { i: 0, playing: true, acc: 0, speed: 15 };
    setSpeed(15);
    document.body.classList.add('replaying');
    $('replaybar').classList.remove('hidden');
    const sl = $('rp-slider');
    sl.max = sim.replay.length - 1;
    sl.value = 0;
    select(null);
    R.flyTo(R.viewFor(...THEATRE, 16), 1000);
  }
  function exitReplay() {
    if (!replay) return;
    replay = null;
    document.body.classList.remove('replaying');
    $('replaybar').classList.add('hidden');
  }
  $('rp-play').onclick = () => { if (replay) replay.playing = !replay.playing; };
  $('rp-slider').oninput = e => { if (replay) { replay.i = +e.target.value; replay.acc = 0; } };
  $('rp-exit').onclick = () => { exitReplay(); showDebrief(); };
  function stepReplay(dt) {
    if (replay.playing) {
      replay.acc += dt * (ui.speed || 0);
      while (replay.acc >= 3 && replay.i < sim.replay.length - 1) { replay.acc -= 3; replay.i++; }
      if (replay.i >= sim.replay.length - 1) replay.playing = false;
    }
    $('rp-slider').value = replay.i;
    $('rp-play').textContent = replay.playing ? 'II' : '▶';
    const f = sim.replay[replay.i];
    $('rp-time').textContent = `${sim.clock(f.t)} · ESC ${f.esc}%`;
    R.draw(replayState(replay.i), { side: 'ALL', marks: [] });
  }

  // ---------- main loop ----------
  function frame(now) {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    if (replay && sim) stepReplay(dt);
    else {
      if (sim && !sim.over) sim.update(dt * ui.speed);
      if (!sim) {
        stepAttract(dt);
        R.draw(attract, { side: 'ALL', marks: [] });
      } else R.draw(sim, ui);
      if (sim) {
        pumpEvents();
        tickCard();
        panelTimer -= dt;
        if (panelTimer <= 0) { if (!ui.plan) renderPanel(false); updateTop(); renderMissions(); panelTimer = 0.25; }
        if (KC.training && ui.training) KC.training.tick();
        if (sim.over && !debriefShown && !ui.training) { renderPanel(false); updateTop(); showDebrief(); }
      }
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  function showMenu() {
    ui.training = false;
    exitReplay();
    closeCard();
    document.body.classList.remove('training');
    document.body.classList.add('in-menu');
    sim = null;
    attractCam = 0;
    $('panel').classList.add('hidden');
    $('debrief').classList.add('hidden');
    $('missions').classList.add('hidden');
    $('menu').classList.remove('hidden');
    menuHome();
    bootText();
  }
  KC.app = { ui, R, get sim() { return sim; }, start, startNight, setSpeed, select, hint, renderPanel, showMenu };
})(globalThis.KC);
