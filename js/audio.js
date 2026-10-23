// Sound: everything is synthesised with the Web Audio API (no audio files).
// Browsers only allow sound after the player interacts, so unlock() runs on the first tap.
(function (KC) {
  let ctx = null, master = null, noise = null, hum = null;
  let muted = false;
  try { muted = localStorage.getItem('kc-sound') === '0'; } catch (e) { /* storage unavailable */ }
  const last = {};
  // Minimum gap between two sounds of the same kind (ms), so fast-forward doesn't turn into noise.
  const GAP = { launch: 220, lock: 400, rwr: 1300, active: 1300, hit: 260, impact: 380, takeoff: 1500, alarm: 3500, loss: 900, click: 40, intel: 2500 };

  function unlock() {
    if (ctx) { if (ctx.state === 'suspended') ctx.resume(); return; }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    ctx = new AC();
    const comp = ctx.createDynamicsCompressor();
    comp.threshold.value = -18; comp.ratio.value = 4;
    master = ctx.createGain();
    master.gain.value = muted ? 0 : 0.7;
    master.connect(comp).connect(ctx.destination);
    const len = ctx.sampleRate * 2;
    noise = ctx.createBuffer(1, len, ctx.sampleRate);
    const d = noise.getChannelData(0);
    for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
    startHum();
  }

  // ---------- building blocks ----------
  const now = () => ctx.currentTime;
  function env(g, t, a, peak, dec) {
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(peak, t + a);
    g.gain.exponentialRampToValueAtTime(0.0001, t + a + dec);
  }
  function noiseSrc(t, dur) {
    const s = ctx.createBufferSource();
    s.buffer = noise; s.loop = true;
    s.start(t, Math.random() * 1.5); s.stop(t + dur);
    return s;
  }
  function tone(type, freq, t, dur, peak, out) {
    const o = ctx.createOscillator(), g = ctx.createGain();
    o.type = type; o.frequency.setValueAtTime(freq, t);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(peak, t + 0.005);
    g.gain.setValueAtTime(peak, t + dur - 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.connect(g).connect(out || master);
    o.start(t); o.stop(t + dur + 0.02);
    return o;
  }

  // ---------- ambience: sonar-style radar ping over a quiet, slowly swelling pad ----------
  // Pure sine tones only (no noise), so the background stays clean.
  function startHum() {
    const bus = ctx.createGain();
    bus.gain.value = 1;
    bus.connect(master);
    // Deep pad: two slightly detuned low sines that beat slowly, swelling in and out.
    const pad = ctx.createGain();
    pad.gain.value = 0.02;
    pad.connect(bus);
    for (const f of [55, 55.4, 82.5]) {
      const o = ctx.createOscillator(), g = ctx.createGain();
      o.type = 'sine'; o.frequency.value = f; g.gain.value = f > 60 ? 0.35 : 1;
      o.connect(g).connect(pad); o.start();
    }
    const lfo = ctx.createOscillator(), lg = ctx.createGain();
    lfo.frequency.value = 0.05; lg.gain.value = 0.012;
    lfo.connect(lg).connect(pad.gain); lfo.start();
    // Spacious echo: a long feedback delay, darkened a little on each repeat.
    const echo = ctx.createDelay(); echo.delayTime.value = 0.42;
    const fb = ctx.createGain(); fb.gain.value = 0.45;
    const damp = ctx.createBiquadFilter(); damp.type = 'lowpass'; damp.frequency.value = 2200;
    const eg = ctx.createGain(); eg.gain.value = 0.5;
    echo.connect(damp).connect(fb).connect(echo); damp.connect(eg).connect(bus);
    const blip = (t, f, peak, dec) => {
      const o = ctx.createOscillator(), g = ctx.createGain();
      o.type = 'sine'; o.frequency.setValueAtTime(f, t); o.frequency.exponentialRampToValueAtTime(f * 0.985, t + dec);
      env(g, t, 0.012, peak, dec);
      o.connect(g); g.connect(bus); g.connect(echo);
      o.start(t); o.stop(t + dec + 0.05);
    };
    // Main sweep ping every 4.5 s; sometimes a faint, delayed "return" from a contact.
    const ping = () => {
      if (!ctx || ctx.state !== 'running') return;
      const t = now() + 0.05;
      blip(t, 1180, 0.05, 2.2);
      if (Math.random() < 0.45) blip(t + 1.2 + Math.random() * 1.6, 1180 * (0.9 + Math.random() * 0.15), 0.012, 0.7);
    };
    hum = { bus, timer: setInterval(ping, 4500) };
    ping();
  }

  // ---------- effects ----------
  const FX = {
    // Missile launch: a rushing whoosh with a low rocket-motor rumble.
    launch(v) {
      const t = now();
      const s = noiseSrc(t, 1.8), bp = ctx.createBiquadFilter(), g = ctx.createGain();
      bp.type = 'bandpass'; bp.Q.value = 1.2;
      bp.frequency.setValueAtTime(2600, t); bp.frequency.exponentialRampToValueAtTime(500, t + 1.5);
      env(g, t, 0.03, 0.35 * v, 1.5);
      s.connect(bp).connect(g).connect(master);
      const r = noiseSrc(t, 1.2), lp = ctx.createBiquadFilter(), rg = ctx.createGain();
      lp.type = 'lowpass'; lp.frequency.value = 180;
      env(rg, t, 0.02, 0.5 * v, 1.0);
      r.connect(lp).connect(rg).connect(master);
    },
    // Own missile seeker lock-on: two crisp beeps.
    lock(v) {
      const t = now();
      tone('square', 1400, t, 0.07, 0.05 * v);
      tone('square', 1400, t + 0.13, 0.07, 0.05 * v);
    },
    // RWR launch warning: fast alternating two-tone beeps.
    rwr(v) {
      const t = now();
      for (let i = 0; i < 12; i++) tone('square', i % 2 ? 1250 : 980, t + i * 0.075, 0.06, 0.045 * v);
    },
    // Missile active / no-warning shot: urgent warble.
    active(v) {
      const t = now();
      const o = ctx.createOscillator(), g = ctx.createGain(), l = ctx.createOscillator(), lg = ctx.createGain();
      o.type = 'square'; o.frequency.value = 1150;
      l.frequency.value = 14; lg.gain.value = 350;
      l.connect(lg).connect(o.frequency);
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.05 * v, t + 0.02);
      g.gain.setValueAtTime(0.05 * v, t + 1.0); g.gain.exponentialRampToValueAtTime(0.0001, t + 1.15);
      o.connect(g).connect(master);
      o.start(t); l.start(t); o.stop(t + 1.2); l.stop(t + 1.2);
    },
    // Air-to-air hit: a distant crack and boom.
    hit(v) {
      const t = now();
      const s = noiseSrc(t, 1.6), lp = ctx.createBiquadFilter(), g = ctx.createGain();
      lp.type = 'lowpass'; lp.frequency.setValueAtTime(2200, t); lp.frequency.exponentialRampToValueAtTime(180, t + 1.2);
      env(g, t, 0.004, 0.45 * v, 1.4);
      s.connect(lp).connect(g).connect(master);
      const o = ctx.createOscillator(), og = ctx.createGain();
      o.frequency.setValueAtTime(90, t); o.frequency.exponentialRampToValueAtTime(35, t + 0.5);
      env(og, t, 0.005, 0.5 * v, 0.6);
      o.connect(og).connect(master); o.start(t); o.stop(t + 0.7);
    },
    // Ground impact: heavier, longer, deeper.
    impact(v) {
      const t = now();
      const s = noiseSrc(t, 3), lp = ctx.createBiquadFilter(), g = ctx.createGain();
      lp.type = 'lowpass'; lp.frequency.setValueAtTime(1100, t); lp.frequency.exponentialRampToValueAtTime(90, t + 2.4);
      env(g, t, 0.01, 0.6 * v, 2.6);
      s.connect(lp).connect(g).connect(master);
      const o = ctx.createOscillator(), og = ctx.createGain();
      o.frequency.setValueAtTime(60, t); o.frequency.exponentialRampToValueAtTime(25, t + 1.2);
      env(og, t, 0.01, 0.8 * v, 1.3);
      o.connect(og).connect(master); o.start(t); o.stop(t + 1.5);
    },
    // Jet take-off: a roar that swells and rises, with a turbine whine.
    takeoff(v) {
      const t = now(), dur = 4.5;
      const s = noiseSrc(t, dur), lp = ctx.createBiquadFilter(), g = ctx.createGain();
      lp.type = 'lowpass'; lp.Q.value = 0.8;
      lp.frequency.setValueAtTime(250, t); lp.frequency.exponentialRampToValueAtTime(2400, t + 2.2); lp.frequency.exponentialRampToValueAtTime(700, t + dur);
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.3 * v, t + 1.6); g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      s.connect(lp).connect(g).connect(master);
      const o = ctx.createOscillator(), bp = ctx.createBiquadFilter(), og = ctx.createGain();
      o.type = 'sawtooth'; o.frequency.setValueAtTime(260, t); o.frequency.exponentialRampToValueAtTime(1100, t + 2.5);
      bp.type = 'bandpass'; bp.frequency.value = 1400; bp.Q.value = 2;
      og.gain.setValueAtTime(0.0001, t); og.gain.exponentialRampToValueAtTime(0.025 * v, t + 1.5); og.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      o.connect(bp).connect(og).connect(master); o.start(t); o.stop(t + dur);
    },
    // Striker inbound: two-tone klaxon.
    alarm(v) {
      const t = now();
      for (let i = 0; i < 4; i++) tone('triangle', i % 2 ? 660 : 880, t + i * 0.24, 0.22, 0.07 * v);
    },
    // One of our aircraft lost: a low falling tone after the boom.
    loss(v) {
      FX.hit(v);
      const t = now() + 0.3;
      const o = ctx.createOscillator(), g = ctx.createGain();
      o.type = 'triangle'; o.frequency.setValueAtTime(440, t); o.frequency.exponentialRampToValueAtTime(180, t + 0.9);
      env(g, t, 0.02, 0.05 * v, 0.9);
      o.connect(g).connect(master); o.start(t); o.stop(t + 1.1);
    },
    // Intelligence tell: a short three-note comms chirp, rising.
    intel(v) {
      const t = now();
      [620, 780, 1040].forEach((f, i) => tone('sine', f, t + i * 0.11, 0.09, 0.05 * v));
    },
    click(v) { tone('sine', 2100, now(), 0.025, 0.03 * v); },
  };

  function play(name, vol) {
    if (!ctx || muted || ctx.state !== 'running' || !FX[name]) return;
    const ms = performance.now();
    if (ms - (last[name] || 0) < (GAP[name] || 100)) return;
    last[name] = ms;
    try { FX[name](vol == null ? 1 : vol); } catch (e) { /* a failed sound must never break the game */ }
  }
  function setMuted(m) {
    muted = m;
    try { localStorage.setItem('kc-sound', m ? '0' : '1'); } catch (e) { /* storage unavailable */ }
    if (master) master.gain.setTargetAtTime(m ? 0 : 0.7, ctx.currentTime, 0.05);
  }

  KC.Audio = { unlock, play, setMuted, get muted() { return muted; } };
})(globalThis.KC = globalThis.KC || {});
