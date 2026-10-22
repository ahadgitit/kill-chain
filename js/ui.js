// Browser front-end: game loop, input (mouse + touch), HUD panels and menus.
(function (KC) {
  const $ = id => document.getElementById(id);
  const { WEAPONS } = KC;
  const { dist, ENEMY } = KC.util;

  const canvas = $('map');
  const R = new KC.Renderer(canvas);
  const ui = { side: 'IN', sel: null, marks: [], speed: 5 };
  let sim = null;
  let evSeen = 0;
  let autoPause = true;
  let lastAutoPause = -1e9;
  try { autoPause = localStorage.getItem('kc-autopause') !== '0'; } catch (e) { /* storage unavailable */ }
  let last = performance.now();
  let panelTimer = 0;
  let debriefShown = false;

  const NET = { IN: 'IAF data link (ODL/IACCS)', PK: 'Link-17 network' };
  const SIDE_NAME = { IN: 'India', PK: 'Pakistan' };

  function resize() { R.resize(); }
  window.addEventListener('resize', resize);
  resize();
  R.fit(28.4, 70.6, 35.0, 77.6, 16);

  // ---------- game lifecycle ----------
  function start(side, scenario, opts) {
    opts = opts || {};
    ui.side = side;
    ui.sel = null;
    ui.training = !!opts.training;
    document.body.classList.toggle('training', ui.training);
    sim = new KC.Sim(scenario || KC.SCENARIO, side);
    evSeen = 0;
    debriefShown = false;
    $('ticker').innerHTML = '';
    $('log-body').innerHTML = '';
    $('menu').classList.add('hidden');
    R.fit(...(opts.fit || [28.4, 70.6, 35.0, 77.6]), 16);
    setSpeed(0);
    if (!ui.training) briefing(side);
    return sim;
  }

  function briefing(side) {
    const IN = `
      <div class="h">BRIEFING: INDIA</div>
      <p>It is just before 01:00 IST on 7 May 2025. Your strike package is airborne and flying pre-planned routes:</p>
      <ul class="controls">
        <li><b>VAJRA-1</b> (Rafale, SCALP-EG): Muridke, then Muzaffarabad.</li>
        <li><b>VAJRA-2</b> (Rafale, SCALP-EG): Bahawalpur.</li>
        <li><b>TRISHUL-1</b> (Mirage 2000, SPICE-2000): Kotli, then Sialkot. Short-range weapons, so it has to fly close to the border.</li>
        <li>Escorts: <b>RUDRA</b> Rafales with Meteor, <b>SHAKTI</b> Su-30MKIs, <b>BAAZ</b> MiG-29s. <b>EYE-1</b> Netra and <b>EYE-2</b> Phalcon provide the air picture. The S-400 <b>SUDARSHAN</b> covers Punjab.</li>
      </ul>
      <p>The PAF is waiting with J-10CEs carrying PL-15Es, flying radar-silent on Erieye and ZDK-03 data. You can launch every weapon from Indian airspace. Crossing the border is allowed but dangerous.</p>
      <p><b>Objective:</b> hit as many of the 5 target sites as you can and lose as few aircraft as possible.</p>`;
    const PK = `
      <div class="h">BRIEFING: PAKISTAN</div>
      <p>It is just before 00:30 PKT on 7 May 2025. Indian strike aircraft are expected over Punjab and Kashmir tonight.</p>
      <ul class="controls">
        <li><b>SKYEYE-1</b> (Erieye) and <b>SKYEYE-2</b> (ZDK-03) build the picture and share it on Link-17.</li>
        <li><b>COBRA</b> J-10CEs and <b>THUNDER-1</b> JF-17 Block IIIs carry PL-15Es. They can fire with radars <b>off</b> on AEW&amp;C tracks and the missile stays guided by the data link.</li>
        <li><b>FALCON</b> F-16s are not on Link-17 in this sim, so they must use their own radar, which warns the target.</li>
        <li>HQ-9/P and LY-80 SAMs protect Lahore, Islamabad and key sites.</li>
      </ul>
      <p>Indian aircraft can fire SCALP-EG cruise missiles from up to ~250 km inside India. They fly low and are hard to see.</p>
      <p><b>Objective:</b> shoot down attackers (Rafales especially), intercept cruise missiles and keep target sites intact.</p>`;
    $('debrief-body').innerHTML = (side === 'IN' ? IN : PK) +
      `<button class="primary" id="go">BEGIN</button>`;
    $('debrief').classList.remove('hidden');
    $('go').onclick = () => { $('debrief').classList.add('hidden'); setSpeed(5); hint('Tap one of your flights to select it'); };
  }

  function showDebrief() {
    debriefShown = true;
    const s = sim.summary();
    const sitesHit = s.IN.sitesHit;
    const ptsIN = sitesHit * 8 + s.IN.killValue;
    const ptsPK = (5 - sitesHit) * 8 + s.PK.intercepts * 4 + s.PK.killValue;
    const me = ui.side, them = ENEMY[me];
    const mine = me === 'IN' ? ptsIN : ptsPK, theirs = me === 'IN' ? ptsPK : ptsIN;
    const verdict = mine > theirs ? 'VICTORY ON POINTS' : mine === theirs ? 'STALEMATE' : 'DEFEAT ON POINTS';
    const row = (k, a, b) => `<tr><td>${k}</td><td>${a}</td><td>${b}</td></tr>`;
    $('debrief-body').innerHTML = `
      <div class="h">DEBRIEF: ${sim.endReason || ''}</div>
      <div class="verdict">${verdict}</div>
      <table class="res">
        <tr><th></th><th class="IN">INDIA</th><th class="PK">PAKISTAN</th></tr>
        ${row('Aircraft lost', s.IN.lost, s.PK.lost)}
        ${row('Air-to-air / SAM shots', s.IN.shots, s.PK.shots)}
        ${row('Kills', s.IN.kills, s.PK.kills)}
        ${row('Weapons on target', s.IN.hits, '-')}
        ${row('Cruise missiles intercepted', '-', s.PK.intercepts)}
        ${row('Target sites hit', `${sitesHit} / 5`, '-')}
        ${row('Points', ptsIN, ptsPK)}
      </table>
      <p class="fine">Points: India gets 8 per site hit; Pakistan gets 8 per site untouched and 4 per cruise missile intercepted; each side adds the value of the aircraft it shot down (AEW&amp;C 30, Rafale 15, J-10CE/F-16/Su-30 12, ...).</p>
      <div class="sides" style="margin-top:14px">
        <button class="side ${me}" id="again"><b>PLAY AGAIN</b><span>Same side: ${SIDE_NAME[me]}</span></button>
        <button class="side ${them}" id="swap"><b>SWITCH SIDES</b><span>Play as ${SIDE_NAME[them]}</span></button>
      </div>
      <button class="link" id="view">VIEW THE MAP</button>`;
    $('debrief').classList.remove('hidden');
    $('again').onclick = () => { $('debrief').classList.add('hidden'); start(me); };
    $('swap').onclick = () => { $('debrief').classList.add('hidden'); start(them); };
    $('view').onclick = () => $('debrief').classList.add('hidden');
  }

  // ---------- speed / top bar ----------
  function setSpeed(v) {
    if (v > 0) ui.lastSpeed = v;
    ui.speed = v;
    for (const b of document.querySelectorAll('#speed button')) b.classList.toggle('on', +b.dataset.speed === v);
  }
  for (const b of document.querySelectorAll('#speed button')) b.onclick = () => setSpeed(+b.dataset.speed);
  $('btn-autopause').classList.toggle('on', autoPause);
  $('btn-autopause').onclick = () => {
    autoPause = !autoPause;
    $('btn-autopause').classList.toggle('on', autoPause);
    try { localStorage.setItem('kc-autopause', autoPause ? '1' : '0'); } catch (e) { /* storage unavailable */ }
    hint(autoPause ? 'Auto-pause ON: the game pauses when one of your jets is warned' : 'Auto-pause OFF');
  };
  $('btn-sensors').onclick = () => { R.showSensors = !R.showSensors; $('btn-sensors').classList.toggle('on', R.showSensors); };
  $('btn-log').onclick = () => { $('log').classList.toggle('hidden'); $('btn-log').classList.toggle('on'); };
  $('log-close').onclick = () => { $('log').classList.add('hidden'); $('btn-log').classList.remove('on'); };
  let helpResume = null;
  $('btn-help').onclick = () => { helpResume = ui.speed; setSpeed(0); $('help').classList.remove('hidden'); };
  $('menu-help').onclick = () => { helpResume = null; $('help').classList.remove('hidden'); };
  $('help-close').onclick = () => { $('help').classList.add('hidden'); if (helpResume) setSpeed(helpResume); };
  for (const b of document.querySelectorAll('#menu .side')) b.onclick = () => start(b.dataset.side);

  let hintTimer = null;
  function hint(text) {
    const h = $('hint');
    h.textContent = text;
    // Sit just above the unit panel when it is open, so it never covers the event ticker.
    const pn = $('panel');
    h.style.bottom = pn.classList.contains('hidden') ? '' : `${pn.getBoundingClientRect().height + 22}px`;
    h.classList.add('show');
    clearTimeout(hintTimer);
    hintTimer = setTimeout(() => h.classList.remove('show'), 3200);
  }

  function pumpEvents() {
    const tk = $('ticker'), lg = $('log-body');
    for (const e of sim.events) {
      if (e.n <= evSeen) continue;
      evSeen = e.n;
      if (e.side !== ui.side && e.side !== 'ALL') continue;
      if (e.alert) onAlert(e);
      const html = `<span class="ev-t">${sim.clock(e.t).slice(0, 4)}</span>${escapeHtml(e.text)}`;
      const d = document.createElement('div');
      d.className = 'ev-' + e.cls;
      d.innerHTML = html;
      lg.prepend(d);
      if (e.cls !== 'info' || /New track|PITBULL/.test(e.text) === false) {
        const t = d.cloneNode(true);
        tk.appendChild(t);
        setTimeout(() => t.remove(), 8000);
        while (tk.children.length > 6) tk.firstChild.remove();
      }
    }
    while (lg.children.length > 300) lg.lastChild.remove();
  }
  // Missile warning on one of our jets: optionally pause and focus it so the player can react.
  function onAlert(e) {
    if (!autoPause || ui.training || ui.speed === 0) return;
    const now = performance.now();
    if (now - lastAutoPause < 4000) return;
    lastAutoPause = now;
    setSpeed(0);
    if (e.ref && e.ref.state === 'air') select(e.ref);
    hint('Paused: missile warning. Choose BREAK or NOTCH, or press play to let the jet defend itself');
  }
  const escapeHtml = s => s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));

  function updateTop() {
    $('clock').textContent = sim.clock() + (ui.speed === 0 ? ' ⏸' : '');
    const s = sim.score, me = ui.side, them = ENEMY[me];
    if (ui.training) { $('score').innerHTML = '<span class="in">TRAINING</span>'; return; }
    const rem = Math.max(0, sim.duration - sim.t);
    const mm = Math.floor(rem / 60), ss = String(Math.floor(rem % 60)).padStart(2, '0');
    const obj = me === 'IN'
      ? `SITES HIT <b>${sim.sites.filter(x => x.hits).length}/5</b>`
      : `INTERCEPTS <b>${s.PK.intercepts}</b> · SITES HIT <b>${sim.sites.filter(x => x.hits).length}/5</b>`;
    $('score').innerHTML = `<span class="${me.toLowerCase()}">KILLS <b>${s[me].kills}</b> · LOST <b>${s[me].lost}</b></span> · ${obj} · T-${mm}:${ss}`;
    void them;
  }

  // ---------- selection & panel ----------
  function select(ref) {
    ui.sel = ref ? { ref } : null;
    renderPanel(true);
  }
  let lastBtns = '';
  function renderPanel(force) {
    const p = $('panel');
    const ref = ui.sel && ui.sel.ref;
    if (!ref || (ref.kind === 'air' && ref.state === 'dead') || (ref.kind === 'cruise' && ref.dead) ||
        (ref.side !== ui.side && ref.kind !== 'site' && !sim.trackFor(ui.side, ref))) {
      if (ui.sel) ui.sel = null;
      p.classList.add('hidden');
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
  }

  function head(name, cls) {
    return `<div class="ph"><span class="name ${cls || ''}">${name}</span><button class="x" data-act="close">✕</button></div>`;
  }
  function wpnChips(u) {
    return Object.entries(u.wpn).map(([k, n]) => `<span class="chip">${WEAPONS[k].name} ${n}</span>`).join('');
  }
  function status(u) {
    if (u.state === 'ground') return u.readyAt ? `RE-ARMING (${fmtT(u.readyAt - sim.t)})` : 'READY';
    if (u.defending) return '<span class="warn">DEFENDING: missile inbound</span>';
    const o = u.order;
    const tgt = u.engage || (o && o.type === 'attack' ? o.target : null);
    if (tgt) { const tr = sim.trackFor(u.side, tgt); return `ENGAGING ${tr ? 'T' + tr.num : 'target'}`; }
    if (!o) return 'ORBITING';
    if (o.type === 'orbit') return u.def.role === 'aew' ? 'ON STATION' : 'COMBAT AIR PATROL';
    if (o.type === 'strike') return o.list.length ? `STRIKE → ${o.list[0].site}` : 'EGRESS';
    if (o.type === 'rtb') return `RETURNING TO ${u.base}`;
    return o.type.toUpperCase();
  }
  const fmtT = s => `${Math.floor(Math.max(0, s) / 60)}:${String(Math.floor(Math.max(0, s) % 60)).padStart(2, '0')}`;

  function describe(ref) {
    const own = ref.side === ui.side;
    if (ref.kind === 'base') {
      const flights = sim.units.filter(u => u.kind === 'air' && u.base === ref.id && u.state === 'ground' && u.count > 0);
      let info = head(`${ref.id} AIR BASE`, ref.side);
      if (!own) return { info: info + `<div class="note">Enemy airbase.</div>`, btns: '' };
      if (!flights.length) info += `<div class="note">No aircraft on the ground.</div>`;
      let btns = '';
      for (const u of flights) {
        btns += `<div class="rsv" style="width:100%"><span>${u.callsign} · ${u.def.name} ×${u.count}<br><span class="k">${u.readyAt ? 're-arming ' + fmtT(u.readyAt - sim.t) : Object.entries(u.wpn).map(([k, n]) => WEAPONS[k].name + ' ' + n).join(', ')}</span></span>` +
          (u.readyAt ? '' : `<button data-act="scramble" data-id="${u.id}">SCRAMBLE</button>`) + `</div>`;
      }
      return { info, btns };
    }
    if (ref.kind === 'site') {
      let info = head(`TARGET: ${ref.id}`, '') +
        `<div class="row"><span class="k">Weapons on target:</span> ${ref.hits}</div>`;
      info += ui.side === 'IN'
        ? `<div class="note">Select a strike flight (VAJRA or TRISHUL), then tap this site to order a strike. The flight launches once it is in weapon range.</div>`
        : `<div class="note">Protect this site. Cruise missiles fly low, so they need AEW&amp;C coverage to be seen in time for an intercept.</div>`;
      return { info, btns: '' };
    }
    if (!own) return describeTrack(ref);
    if (ref.kind === 'sam') {
      const w = WEAPONS[ref.def.weapon];
      return {
        info: head(`${ref.callsign} · ${ref.def.name}`, ref.side) +
          `<div class="row"><span class="k">Missiles:</span> ${ref.ammo} × ${w.name} · <span class="k">reach</span> ~${Math.round(w.range * 0.7)} km</div>` +
          `<div class="note">Surface-to-air missile site. Fires automatically at enemies in reach. Its radar emits all the time, so the enemy knows where it is.</div>`,
        btns: '',
      };
    }
    if (ref.kind === 'radar') {
      return { info: head(`${ref.callsign} · GROUND RADAR`, ref.side) + `<div class="note">Feeds the ${NET[ref.side]}. Range ~${ref.range} km against fighters at altitude, much less against low-flying cruise missiles.</div>`, btns: '' };
    }
    // Own flight
    const u = ref, def = u.def;
    const threats = u.threats.filter(m => !m.dead).length;
    const near = u.threats.filter(m => !m.dead).sort((a, b) => dist(a, u) - dist(b, u))[0];
    let threatRow = '';
    if (near) {
      const th = sim.missileThreat(near, false), thd = sim.missileThreat(near, true);
      threatRow = `<div class="row warn">⚠ ${threats} missile${threats > 1 ? 's' : ''} inbound · nearest ${near.w.name} M${th.mach.toFixed(1)}, ${th.d.toFixed(0)} km</div>` +
        `<div class="row"><span class="k">If you BREAK now:</span> ${thd.lethal ? '<span class="warn">it still catches you, so NOTCH</span>' : 'it runs out of energy'}</div>`;
    }
    const defRow = u.defending ? `<div class="row warn">DEFENDING: ${u.defMode === 'notch' ? 'NOTCH (beaming the missile)' : 'BREAK/DRAG (running away)'}${u.manualDef ? ' · manual' : ' · auto'}</div>` : '';
    let info = head(`${u.callsign} · ${def.name}${u.count > 1 ? ' ×' + u.count : ''}`, u.side) +
      `<div class="row">${status(u)}</div>` +
      (Object.keys(u.wpn).length ? `<div class="row">${wpnChips(u)}</div>` : '') +
      `<div class="row"><span class="k">Radar</span> ${def.role === 'aew' ? 'ON (AEW&C)' : u.radarOn ? 'ON, emitting' : 'OFF, silent'}` +
      ` · <span class="k">Link</span> ${def.linked ? 'on net' : '<span class="warn">none</span>'}` +
      (u.state === 'air' ? ` · <span class="k">Fuel</span><span class="bar"><i style="width:${Math.round(100 * Math.min(1, u.fuel / (def.role === 'aew' ? 20000 : 9000)))}%"></i></span>` : '') + `</div>` +
      threatRow + defRow +
      `<div class="note">${def.note}</div>`;
    if (u.state !== 'air') return { info, btns: '' };
    let btns = '';
    if (def.role === 'fighter') {
      btns += `<button data-act="radar" class="${u.radarOn ? 'on' : ''}">RADAR ${u.radarOn ? 'ON' : 'OFF'}</button>`;
      btns += `<button data-act="roe" class="${u.roe === 'free' ? 'on' : ''}">ROE ${u.roe === 'free' ? 'FREE' : 'HOLD'}</button>`;
    }
    const md = u.manualDef && u.manualDef.mode;
    btns += `<button data-act="break" class="${md === 'drag' ? 'on' : ''}">BREAK</button>`;
    if (def.role === 'fighter') btns += `<button data-act="notch" class="${md === 'notch' ? 'on' : ''}">NOTCH</button>`;
    btns += `<button data-act="rtb" class="danger">RTB</button>`;
    return { info, btns };
  }

  function describeTrack(e) {
    const tr = sim.trackFor(ui.side, e);
    if (!tr) return { info: '', btns: '' };
    if (e.kind === 'sam' || e.kind === 'radar') {
      return {
        info: head(`${e.kind === 'sam' ? 'SAM SITE' : 'RADAR'}: ${e.callsign}`, e.side) +
          `<div class="note">Located by its radar emissions. ${e.kind === 'sam' ? `Estimated reach ~${Math.round(WEAPONS[e.def.weapon].range * 0.7)} km. Stay outside the red ring.` : ''}</div>`,
        btns: '',
      };
    }
    if (e.kind === 'cruise') {
      return { info: head(`T${tr.num} · CRUISE MISSILE`, e.side) + `<div class="note">Low-flying ${e.w.name}. Select a fighter and tap it to attempt an intercept.</div>`, btns: '' };
    }
    const seen = e.seen[ui.side] || { by: [] };
    const sources = seen.by.map(id => sim.unit(id)).filter(Boolean).map(u => u.callsign).slice(0, 4).join(', ');
    const hdg = Math.round(((e.hdg * 180 / Math.PI) + 360) % 360);
    const age = sim.t - tr.t;
    let info = head(`T${tr.num} · ${tr.q === 'esm' ? e.def.name + '?' : e.def.name + (e.count > 1 ? ' ×' + e.count : '')}`, e.side) +
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
    const ref = ui.sel && ui.sel.ref;
    const act = b.dataset.act;
    if (act === 'close') { select(null); return; }
    if (act === 'scramble') {
      const u = sim.unit(+b.dataset.id);
      if (u && u.state === 'ground' && !u.readyAt) {
        sim.scramble(u, null);
        select(u);
        hint(`${u.callsign} airborne. Tap the map to send it on patrol.`);
      }
      return;
    }
    if (!ref || ref.kind !== 'air') return;
    if (act === 'radar') {
      ref.radarOn = !ref.radarOn;
      hint(ref.radarOn ? `${ref.callsign} radar ON: it can see and guide missiles, but enemy RWR/ESM can detect it`
        : `${ref.callsign} radar OFF: silent, relying on the data link`);
    } else if (act === 'roe') {
      ref.roe = ref.roe === 'free' ? 'hold' : 'free';
      hint(ref.roe === 'free' ? 'Weapons free: fires on its own at good targets' : 'Weapons hold: fires only on your attack order');
    } else if (act === 'break' || act === 'notch') {
      defend(ref, act === 'break' ? 'drag' : 'notch');
      return;
    } else if (act === 'rtb') {
      sim.cmdRTB(ref);
      hint(`${ref.callsign} returning to ${ref.base}`);
    }
    renderPanel(true);
  });

  function defend(u, mode) {
    const cur = u.manualDef && u.manualDef.mode;
    if (cur === mode) {
      sim.cmdDefend(u, null);
      hint(`${u.callsign}: defence back on automatic`);
    } else {
      sim.cmdDefend(u, mode);
      hint(mode === 'drag' ? `${u.callsign} BREAK: turning away at full speed, making the missile chase`
        : `${u.callsign} NOTCH: flying at 90° to the missile to hide in the radar clutter`);
    }
    if (ui.speed === 0 && !ui.training) setSpeed(ui.lastSpeed || 5);
    renderPanel(true);
  }

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
      add(e, p.x, p.y, 'enemy', e.kind === 'air' ? 6 : 0);
    }
    for (const s of sim.sites) add(s, s.x, s.y, 'site', 2);
    for (const b of sim.bases) if (b.side === ui.side) add(b, b.x, b.y, 'base', -2);
    cands.sort((a, b) => a.d - b.d);
    return cands[0] || null;
  }

  function onTap(sx, sy) {
    if (!sim) return;
    const hit = pick(sx, sy);
    const s = ui.sel && ui.sel.ref;
    const ownAir = s && s.kind === 'air' && s.side === ui.side && s.state === 'air';
    if (ownAir && hit && s.def.role === 'fighter') {
      if (hit.kind === 'enemy' && (hit.ref.kind === 'air' || hit.ref.kind === 'cruise')) {
        const tr = sim.trackFor(ui.side, hit.ref);
        if (!sim.hasAAM(s)) { hint(`${s.callsign} has no air-to-air missiles left`); return; }
        sim.cmdAttack(s, hit.ref);
        const why = s.def.linked ? '' : ' (no data link: it will use its own radar)';
        hint(`${s.callsign} → attack T${tr ? tr.num : ''}${why}`);
        renderPanel(true);
        return;
      }
      if (hit.kind === 'site' && hit.ref.side !== ui.side) {
        const wk = sim.strikeWeapon(s);
        if (!wk) { hint(`${s.callsign} carries no strike weapons`); return; }
        sim.cmdStrike(s, hit.ref);
        hint(`${s.callsign} → strike ${hit.ref.id} with ${WEAPONS[wk].name} (launches from ~${Math.round(WEAPONS[wk].range * 0.9)} km)`);
        renderPanel(true);
        return;
      }
    }
    if (hit) {
      if (s === hit.ref) select(null); else select(hit.ref);
      return;
    }
    if (ownAir) {
      const w = R.toWorld(sx, sy);
      sim.cmdMove(s, w.x, w.y);
      ui.marks.push({ x: w.x, y: w.y, t: performance.now() / 1000 });
      if (ui.marks.length > 10) ui.marks.shift();
      const crossing = KC.geo.sideOf(w.x, w.y) !== ui.side;
      hint(`${s.callsign} → moving${crossing ? ' (into ENEMY airspace!)' : ''}`);
      renderPanel(true);
      return;
    }
    select(null);
  }

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
    const p = ptrs.get(e.pointerId);
    if (!p) return;
    const dx = e.clientX - p.x, dy = e.clientY - p.y;
    p.x = e.clientX; p.y = e.clientY;
    if (ptrs.size === 1) {
      if (Math.hypot(p.x - p.x0, p.y - p.y0) > 8) dragMoved = true;
      if (dragMoved) { R.view.cx -= dx / R.view.scale; R.view.cy -= dy / R.view.scale; }
    } else if (ptrs.size === 2 && pinch) {
      const [a, b] = [...ptrs.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      const cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
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
  canvas.addEventListener('pointercancel', up);
  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const r = canvas.getBoundingClientRect();
    R.zoomAt(e.clientX - r.left, e.clientY - r.top, Math.exp(-e.deltaY * 0.0015));
  }, { passive: false });

  window.addEventListener('keydown', e => {
    if (!sim) return;
    if (e.code === 'Space') { e.preventDefault(); setSpeed(ui.speed ? 0 : ui.lastSpeed || 5); }
    const map = { Digit1: 0, Digit2: 1, Digit3: 5, Digit4: 15, Digit5: 40 };
    if (e.code in map) setSpeed(map[e.code]);
    if (e.code === 'Escape') select(null);
    const ref = ui.sel && ui.sel.ref;
    if ((e.code === 'KeyB' || e.code === 'KeyN') && ref && ref.kind === 'air' && ref.side === ui.side && ref.state === 'air') {
      if (e.code === 'KeyB' || ref.def.role === 'fighter') defend(ref, e.code === 'KeyB' ? 'drag' : 'notch');
    }
    if (e.code === 'KeyR' && ref && ref.kind === 'air' && ref.side === ui.side && ref.def.role === 'fighter') {
      ref.radarOn = !ref.radarOn; renderPanel(true);
    }
  });

  // ---------- main loop ----------
  function frame(now) {
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    if (sim && !sim.over) sim.update(dt * ui.speed);
    R.draw(sim, ui);
    if (sim) {
      pumpEvents();
      panelTimer -= dt;
      if (panelTimer <= 0) { renderPanel(false); updateTop(); panelTimer = 0.25; }
      if (KC.training && ui.training) KC.training.tick();
      if (sim.over && !debriefShown && !ui.training) { renderPanel(false); updateTop(); showDebrief(); }
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  KC.app = { ui, R, get sim() { return sim; }, start, setSpeed, select, hint, renderPanel, showMenu: () => { ui.training = false; document.body.classList.remove('training'); sim = null; $('panel').classList.add('hidden'); $('menu').classList.remove('hidden'); } };
})(globalThis.KC);
