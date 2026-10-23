// Training mission: four scripted lessons on missile evasion and the kill chain.
(function (KC) {
  const $ = id => document.getElementById(id);
  const P = KC.geo.project;
  const { DEG, dist } = KC.util;

  const SCN = {
    name: 'Training',
    start: { h: 20, m: 0 },
    duration: 1e9,
    flights: [
      { side: 'IN', callsign: 'STUDENT', type: 'RAFALE', count: 1, loadout: 'RAFALE_CAP', base: 'ADAMPUR', pos: P(30.6, 75.2), order: { type: 'orbit', ...P(30.6, 75.2), r: 15 } },
      { side: 'PK', callsign: 'BANDIT', type: 'J10C', count: 1, loadout: 'J10C', base: 'RAFIQUI', pos: P(30.6, 74.2), order: { type: 'orbit', ...P(30.6, 74.2), r: 15 } },
      { side: 'PK', callsign: 'SKYEYE', type: 'ERIEYE', count: 1, loadout: 'AEW', base: 'RAFIQUI', pos: P(30.9, 70.5), order: { type: 'orbit', ...P(30.9, 70.5), r: 20 } },
    ],
    emptyBases: true, noStatics: true,
  };

  // Put a unit at lat/lon, flying straight along hdgDeg (0 = north).
  function place(sim, u, lat, lon, hdgDeg) {
    const p = P(lat, lon), h = hdgDeg * DEG;
    Object.assign(u, {
      x: p.x, y: p.y, hdg: h, spd: u.def.speed, state: 'air', count: 1, threats: [], defending: false,
      manualDef: null, engage: null, anchor: null, fuel: 9000, lastShot: -99,
      wpn: { ...KC.LOADOUTS[u.loadoutKey] },
      order: { type: 'orbit', x: p.x + Math.sin(h) * 400, y: p.y - Math.cos(h) * 400, r: 15 },
    });
  }

  const LESSONS = [
    {
      title: 'LESSON 1 OF 4 · THE LONG SHOT',
      intro: `<p>A J-10CE (<b>BANDIT</b>, green) is 100 km west of you with its radar <b>ON</b>, locked on your Rafale. It will fire a <b>PL-15E</b>.</p>
        <p>Because its radar is locked on you, your radar warning receiver will hear the launch. You get a <b>launch warning</b> right away.</p>`,
      setup(sim, s, b, aew) {
        place(sim, s, 30.6, 75.25, 270); place(sim, b, 30.6, 74.21, 90); place(sim, aew, 30.9, 70.5, 0);
        b.radarOn = true; b.radarMode = 'on'; s.radarOn = true; s.radarMode = 'on';
      },
      fireAt: 3,
      warn: `<p class="warn"><b>LAUNCH WARNING</b>, PL-15E, 100 km.</p>
        <p>The motor burns for 12 seconds. After that the missile glides and keeps <b>slowing down</b>.</p>
        <p>Tap <b>BREAK</b>: your jet turns its tail to the missile and runs at full speed. Watch the missile's label. Its Mach number drops, and the line turns <b>grey</b> once it can no longer catch you.</p>`,
      result(m, s, used) {
        if (m.end === 'energy') return `<p class="good"><b>DEFEATED.</b> The PL-15E ran out of energy at Mach ${(m.spd / 0.295).toFixed(1)}, ${dist(m, s).toFixed(0)} km short of you.</p>
          <p>A long-range shot against a jet that turns away usually fails. Pilots know this, so they fire closer, or fire <b>without warning</b> (lesson 3).</p>`;
        if (m.end === 'miss') return `<p class="good"><b>MISSED.</b> It reached you but missed (hit chance ${Math.round(m.pk * 100)}%). ${used.has('drag') ? '' : 'Breaking earlier would have made it run out of energy before reaching you.'}</p>`;
        return `<p class="warn"><b>HIT.</b> The missile still had energy when it reached you${used.size ? '' : ', because the jet only started defending at 40 km'}. Try again and tap <b>BREAK</b> as soon as the warning sounds.</p>`;
      },
    },
    {
      title: 'LESSON 2 OF 4 · THE SHORT SHOT',
      intro: `<p>This time the J-10CE fires from only <b>35 km</b>. At this range the PL-15E reaches you while still flying above Mach 4. <b>You can't outrun it.</b></p>`,
      setup(sim, s, b, aew) {
        place(sim, s, 30.6, 74.95, 270); place(sim, b, 30.6, 74.59, 90); place(sim, aew, 30.9, 70.5, 0);
        b.radarOn = true; b.radarMode = 'on'; s.radarOn = true; s.radarMode = 'on';
      },
      fireAt: 2,
      warn: `<p class="warn"><b>LAUNCH WARNING</b>, PL-15E, 35 km. The line stays <b>red</b>: running won't work.</p>
        <p>Tap <b>NOTCH</b>: fly at 90° to the missile. A pulse-Doppler radar picks targets out of the ground clutter by their <b>closing speed</b>. At 90°, yours is close to zero, so you blend into the clutter. The seeker may lose its lock, and the hit chance drops sharply.</p>`,
      result(m, s, used) {
        if (m.end === 'hit') return `<p class="warn"><b>HIT.</b> ${used.has('notch') ? 'Notching lowers the hit chance to under half, but it does not make you safe.' : 'Running away does not work against a missile this fast.'} At this range there are no good options. The best defence is not being there.</p>`;
        return `<p class="good"><b>SURVIVED.</b> ${used.has('notch') ? 'Beaming the missile spoiled its radar seeker' : 'You got lucky'}${m.pk != null ? ` (final hit chance ${Math.round(m.pk * 100)}%)` : ''}.</p>
          <p>Notching is a gamble: it only works against radar-guided missiles, and the missile keeps closing fast. Against a heat-seeker, flares do the work.</p>`;
      },
    },
    {
      title: 'LESSON 3 OF 4 · THE SILENT SHOT (THE KILL CHAIN)',
      intro: `<p>Now the J-10CE keeps its radar <b>OFF</b> and only switches it on at the moment it fires. You can't see it at all. The <b>Erieye</b> AEW&amp;C (circle with a bar, far west) tracks you instead and shares that track over <b>Link-17</b>.</p>
        <p>The J-10 fires a PL-15E using the Erieye's data, and the missile receives course updates over the data link. Your warning receiver hears nothing. Press START and watch for the warning.</p>`,
      setup(sim, s, b, aew) {
        place(sim, s, 30.6, 75.0, 270); place(sim, b, 30.6, 74.2, 90); place(sim, aew, 30.7, 73.15, 0);
        aew.order = { type: 'orbit', ...P(30.7, 73.15), r: 15 };
        b.radarOn = false; b.radarMode = 'off'; s.radarOn = true; s.radarMode = 'on';
      },
      fireAt: 3,
      warn: `<p class="warn"><b>MISSILE WARNING, no launch warning.</b></p>
        <p>The missile's own radar just switched on, about 25 km away. It has been flying at you for about a minute and you had no idea. It's fast and close. Choose <b>BREAK</b> or <b>NOTCH</b>.</p>`,
      result(m, s, used) {
        const head = m.end === 'hit' ? `<p class="warn"><b>HIT.</b></p>` : `<p class="good"><b>SURVIVED</b>${m.pk != null ? ` (hit chance was ${Math.round(m.pk * 100)}%)` : ''}.</p>`;
        return head + `<p>This is the <b>kill chain</b>: a sensor (Erieye), a silent shooter (J-10CE) and a data-link missile (PL-15E) working together. The target only finds out at the very end.</p>
          <p>Counters: stay out of range, push back or shoot down the AEW&amp;C, or jam the link. Meteor, AIM-120, R-77 and MICA cannot take third-party updates in this sim, so their shooters must keep their own radar on, which warns the target.</p>`;
      },
    },
    {
      title: 'LESSON 4 OF 4 · YOUR SHOT',
      intro: `<p>Your turn. The J-10CE is about 75 km west, heading for you with its radar <b>on</b>, which means it can be locked. (A jet with its radar <b>off</b> can't be locked by radar missiles at all, only by heat-seekers up close.)</p>
        <p>1. Tap <b>STUDENT</b> to select it. 2. Turn <b>RADAR ON</b>. 3. Tap the green <b>J-10 track</b> to attack.</p>
        <p>Your Rafale fires a <b>Meteor</b> and then <b>cranks</b>, turning 50° off so its radar keeps guiding the missile. <b>Don't BREAK</b> while your missile is in the air, or it goes blind. Because your radar is on, the J-10 gets a launch warning and will defend.</p>`,
      setup(sim, s, b, aew) {
        place(sim, s, 30.6, 75.0, 270); place(sim, b, 30.6, 74.22, 90); place(sim, aew, 30.9, 70.5, 0);
        b.radarOn = true; b.radarMode = 'on'; s.radarOn = false; s.radarMode = 'off';
        s.wpn = { METEOR: 2 };
      },
      fireAt: null,
      result(m, s, used, b) {
        if (m.end === 'hit') return `<p class="good"><b>SPLASH ONE J-10CE.</b></p><p>The Meteor's ramjet kept it fast to the end, so the J-10 could not run it out of energy.</p>`;
        if (m.end === 'energy') return `<p class="warn"><b>OUT OF ENERGY.</b> The J-10 ran and your missile could not catch it. Fire from closer, inside the inner dashed ring.</p>`;
        return `<p class="warn"><b>MISSED.</b> The J-10 ${b.defMode === 'notch' ? 'notched' : 'dragged'} and the Meteor missed (hit chance ${Math.round(m.pk * 100)}%). Even good shots miss, which is why real pilots often fire two.</p>`;
      },
    },
  ];

  const T = { idx: 0, phase: 'off' };

  function units(sim) {
    const f = cs => sim.units.find(u => u.callsign === cs);
    return [f('STUDENT'), f('BANDIT'), f('SKYEYE')];
  }

  function coach(title, html, buttons) {
    const c = $('coach');
    c.classList.remove('hidden');
    c.innerHTML = `<div class="ct">${title}</div>${html}<div class="cb">${buttons.map((b, i) =>
      `<button data-i="${i}" class="${b.pri ? 'pri' : ''}">${b.label}</button>`).join('')}</div>`;
    c.querySelectorAll('button').forEach(el => { el.onclick = () => buttons[+el.dataset.i].fn(); });
  }

  function begin() {
    $('menu').classList.add('hidden');
    KC.app.start('IN', SCN, { training: true, fit: [30.05, 73.0, 31.15, 75.8] });
    const [, b, aew] = units(KC.app.sim);
    b.ai = false; b.roe = 'hold';
    aew.ai = true;
    showIntro(0);
  }

  function showIntro(i) {
    T.idx = i; T.phase = 'intro';
    const L = LESSONS[i];
    const sim = KC.app.sim;
    KC.app.setSpeed(0);
    sim.missiles = [];
    const [s, b, aew] = units(sim);
    L.setup(sim, s, b, aew);
    s.roe = 'hold';
    sim.sweep();
    KC.app.select(i === 3 ? null : s);
    coach(L.title, L.intro, [{ label: 'START', pri: true, fn: run }, { label: 'QUIT', fn: quit }]);
  }

  function run() {
    T.phase = 'run'; T.t0 = KC.app.sim.t; T.m = null; T.fired = false; T.warned = false; T.used = new Set();
    $('coach').classList.add('hidden');
    KC.app.setSpeed(5);
  }

  function defend(mode) {
    const [s] = units(KC.app.sim);
    KC.app.sim.cmdDefend(s, mode);
    T.phase = 'run';
    $('coach').classList.add('hidden');
    KC.app.setSpeed(3);
  }

  function quit() {
    T.phase = 'off';
    $('coach').classList.add('hidden');
    KC.app.showMenu();
  }

  function finish() {
    T.phase = 'off';
    coach('TRAINING COMPLETE', `<p>You've seen the four ideas behind the game:</p>
      <p>· Long shots can be <b>outrun</b> (BREAK).<br>· Close shots can only be <b>spoiled</b> (NOTCH).<br>· Silent data-link shots give <b>no warning</b>.<br>· Your own radar-guided shots need <b>support</b> until PITBULL.</p>`, [
      { label: 'COMMAND INDIA', pri: true, fn: () => { $('coach').classList.add('hidden'); document.body.classList.remove('training'); KC.app.startNight(0, 'IN'); } },
      { label: 'COMMAND PAKISTAN', pri: true, fn: () => { $('coach').classList.add('hidden'); document.body.classList.remove('training'); KC.app.startNight(0, 'PK'); } },
      { label: 'MAIN MENU', fn: quit },
    ]);
  }

  // Called every frame while training is active.
  function tick() {
    const sim = KC.app.sim;
    if (!sim || T.phase === 'off' || T.phase === 'intro' || T.phase === 'done') return;
    const L = LESSONS[T.idx];
    const [s, b] = units(sim);
    if (s.defending && s.defMode) T.used.add(s.defMode);

    if (L.fireAt != null && !T.fired && sim.t - T.t0 >= L.fireAt) {
      T.fired = true;
      sim.sweep();
      b.lastShot = sim.t; sim.setRadar(b, true);   // firing means radar on
      sim.launch(b, s, 'PL15');
      b.wpn.PL15--;
      T.m = sim.missiles[sim.missiles.length - 1];
    }
    if (L.fireAt == null && !T.m) {
      T.m = sim.missiles.find(m => m.shooter === s && m.kind !== 'cruise') || null;
      if (T.m) KC.app.hint('FOX-3! Keep the J-10 inside your radar cone until the Meteor goes PITBULL');
    }
    if (T.phase === 'run' && T.m && L.warn && !T.warned && s.threats.includes(T.m)) {
      T.warned = true;
      T.phase = 'warn';
      KC.app.setSpeed(0);
      KC.app.select(s);
      coach(L.title, L.warn, [
        { label: 'BREAK', pri: true, fn: () => defend('drag') },
        { label: 'NOTCH', pri: true, fn: () => defend('notch') },
        { label: 'LET THE JET DECIDE', fn: () => defend(null) },
      ]);
    }
    if (T.phase === 'warn' && KC.app.ui.speed > 0) { T.phase = 'run'; $('coach').classList.add('hidden'); }

    if (T.m && T.m.dead) {
      T.phase = 'done';
      T.warned = false;
      KC.app.setSpeed(0);
      const html = L.result(T.m, s, T.used, b);
      if (s.state !== 'air') { s.state = 'air'; s.count = 1; }
      const last = T.idx === LESSONS.length - 1;
      coach(L.title, html, [
        { label: last ? 'FINISH' : 'NEXT LESSON', pri: true, fn: () => (last ? finish() : showIntro(T.idx + 1)) },
        { label: 'RETRY', fn: () => showIntro(T.idx) },
        { label: 'QUIT', fn: quit },
      ]);
    }
  }

  function stop() { T.phase = 'off'; $('coach').classList.add('hidden'); }
  KC.training = { begin, tick, stop, LESSONS, goto: showIntro };
})(globalThis.KC);
