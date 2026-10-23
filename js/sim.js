// Simulation core: bases and sorties, sensors, data-link picture, missiles, strikes,
// drones, electronic warfare, escalation, claims and replay recording.
// No DOM access here so it can be stepped headless (see tools/headless.js).
(function (KC) {
  const { AIRCRAFT, WEAPONS, SAMS, PRESETS, STOCKS, AIR_CAP, BASES, SITES, SAM_SITES, RADAR_SITES, LOADOUTS, V_MIN, geo } = KC;
  const DEG = Math.PI / 180;
  const ENEMY = { IN: 'PK', PK: 'IN' };
  const SIDES = ['IN', 'PK'];
  const GIMBAL = 60 * DEG;
  const AI_FIRE = 0.75;          // fraction of max launch range used by automatic shots
  const CLOSED_AT = 60;          // base damage at which the runway closes
  const REPAIR_PER_MIN = 5;      // base damage repaired per minute
  const EW = {
    jam: { dur: 75, cd: 360, name: 'LINK JAM' },
    barrage: { dur: 60, cd: 240, r: 120, name: 'BARRAGE JAM' },
    decoy: { dur: 300, cd: 300, name: 'DECOYS' },
  };
  const CALLSIGN = {
    RAFALE: 'VAJRA', SU30: 'SHAKTI', MIG29: 'BAAZ', M2000: 'TRISHUL', NETRA: 'NETRA', PHALCON: 'PHALCON', HAROP: 'HAROP',
    J10C: 'COBRA', JF17B3: 'THUNDER', JF17B2: 'STORM', F16: 'FALCON', ERIEYE: 'SKYEYE', ZDK03: 'EAGLE', SWARM: 'SWARM',
  };

  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const angNorm = a => Math.atan2(Math.sin(a), Math.cos(a));
  const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  const bearing = (a, b) => Math.atan2(b.x - a.x, -(b.y - a.y)); // 0 = north, clockwise
  const vel = e => ({ x: Math.sin(e.hdg) * e.spd, y: -Math.cos(e.hdg) * e.spd });

  function mulberry32(seed) {
    return function () {
      seed |= 0; seed = seed + 0x6D2B79F5 | 0;
      let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
      t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
      return ((t ^ t >>> 14) >>> 0) / 4294967296;
    };
  }

  class Sim {
    // cfg: { name, start, duration, night?, carry?, flights?, emptyBases?, ai? }
    constructor(cfg, player, seed) {
      this.cfg = cfg;
      this.night = cfg.night || null;
      this.player = player;           // 'IN', 'PK' or null (AI vs AI)
      this.rand = mulberry32(seed == null ? Date.now() : seed);
      this.t = 0;
      this.duration = cfg.duration;
      this.nextId = 1;
      this.units = [];
      this.missiles = [];
      this.events = [];
      this.evSeq = 0;
      this.fx = [];
      this.over = false;
      this.sweepTimer = 0;
      this.trackNo = { IN: 100, PK: 100 };
      this.tracks = { IN: new Map(), PK: new Map() };
      this.callNo = {};
      const carry = cfg.carry || null;
      this.score = {};
      for (const s of SIDES) {
        this.score[s] = {
          kills: 0, killValue: 0, lost: 0, shots: 0, intercepts: 0, droneKills: 0, groundKills: 0, groundKillValue: 0,
          hits: 0, objHits: new Set(), otherHits: new Set(), claimed: 0, falseClaims: 0, groundLost: 0,
        };
      }
      this.stocks = carry ? JSON.parse(JSON.stringify(carry.stocks)) : JSON.parse(JSON.stringify(STOCKS));
      this.bases = BASES.map(b => {
        const base = { id: b.id, side: b.side, x: b.x, y: b.y, kind: 'base', home: b.type, pools: {}, preset: {}, damage: 0 };
        const n = cfg.emptyBases ? 0 : carry && carry.pools[b.id] ? null : b.count;
        if (b.type && n != null) base.pools[b.type] = { ready: n, rearm: [] };
        if (carry && carry.pools[b.id]) for (const [ty, c] of Object.entries(carry.pools[b.id])) base.pools[ty] = { ready: c, rearm: [] };
        for (const ty of Object.keys(base.pools)) base.preset[ty] = b.type === ty && b.preset ? b.preset : Object.keys(PRESETS[ty])[0];
        if (carry && carry.damage[b.id]) base.damage = carry.damage[b.id];
        return base;
      });
      this.sites = this.night && this.night.sites ? SITES.map(s => ({ ...s, kind: 'site', hits: 0 })) : [];
      const deadStatics = new Set(carry ? carry.deadStatics : []);
      if (!cfg.noStatics) {
        for (const d of SAM_SITES) this.addStatic('sam', d, deadStatics.has(d.callsign));
        for (const d of RADAR_SITES) this.addStatic('radar', d, deadStatics.has(d.callsign));
      }
      for (const d of cfg.flights || []) this.addFlight(d);

      this.objectives = { IN: [], PK: [] };
      if (this.night) {
        for (const s of SIDES) {
          this.objectives[s] = this.night.targets[s].map(o => ({ ...o, ref: this.refFor(o.kind, o.id), hit: false }));
        }
      }
      this.esc = carry ? Math.round(carry.esc * 0.6) : 10;
      this.escMarks = new Set();
      this.ew = {};
      for (const s of SIDES) this.ew[s] = { jam: { until: 0, ready: 0 }, barrage: { until: 0, ready: 0, x: 0, y: 0 }, decoy: { until: 0, ready: 0 } };
      this.aewClear = { IN: 0, PK: 0 };
      this.prompts = [];
      this.promptCd = {};
      this.missions = [];
      this.missionTimer = 0;
      this.missionNo = {};
      this.replay = [];
      this.replayTimer = 0;
      // Intelligence: fixed SAM and radar sites are known to the other side before the war.
      for (const u of this.units) if (u.kind === 'sam' || u.kind === 'radar') this.updateTrack(ENEMY[u.side], u, 'esm');
      this.commanders = [];
      if (this.night && KC.Commander && cfg.ai !== false) {
        for (const s of SIDES) if (s !== player) this.commanders.push(new KC.Commander(this, s, this.night.ai[s]));
      }
      this.sweep();
      this.log('ALL', `${cfg.name}: simulation started`, 'info');
    }

    // ---------- setup ----------
    addFlight(d) {
      const def = AIRCRAFT[d.type];
      const u = this.newFlight(d.side, d.type, d.count, d.base, d.callsign);
      Object.assign(u, { x: d.pos.x, y: d.pos.y, wpn: { ...LOADOUTS[d.loadout] }, loadoutKey: d.loadout, order: this.cloneOrder(d.order) });
      u.fuel = def.fuel;
      return u;
    }
    newFlight(side, type, count, baseId, callsign) {
      const def = AIRCRAFT[type];
      const base = this.base(baseId);
      const pre = CALLSIGN[type] || type;
      this.callNo[pre] = (this.callNo[pre] || 0) + 1;
      const u = {
        id: this.nextId++, kind: 'air', side, callsign: callsign || `${pre}-${this.callNo[pre]}`, type, def,
        count, wpn: {}, base: baseId, x: base.x, y: base.y,
        hdg: side === 'IN' ? -90 * DEG : 90 * DEG, spd: def.speed, state: 'air', order: null,
        roe: 'free', radarOn: def.role === 'aew' || (def.role === 'fighter' && !def.linked),
        fuel: def.fuel, lastShot: -99, lastLaunch: -99, launchedAt: this.t,
        threats: [], defending: false, seen: {}, ai: side !== this.player,
        engage: null, anchor: null, cap: null, bingo: false, crossed: false,
      };
      this.units.push(u);
      return u;
    }
    addStatic(kind, d, dead) {
      const u = {
        id: this.nextId++, kind, side: d.side, callsign: d.callsign, type: d.type,
        x: d.pos.x, y: d.pos.y, hdg: 0, spd: 0, linked: true, seen: {}, lastShot: -99, dead: !!dead, radarOn: true,
      };
      if (kind === 'sam') { u.def = SAMS[d.type]; u.ammo = u.def.ammo; u.range = u.def.radar; }
      else { u.range = d.range; u.def = { name: 'Ground radar' }; }
      this.units.push(u);
    }
    cloneOrder(o) {
      if (!o) return null;
      const c = { ...o };
      if (o.list) c.list = o.list.map(x => ({ ...x }));
      return c;
    }
    base(id) { return this.bases.find(b => b.id === id); }
    site(id) { return this.sites.find(s => s.id === id); }
    unit(id) { return this.units.find(u => u.id === id); }
    staticBy(callsign) { return this.units.find(u => (u.kind === 'sam' || u.kind === 'radar') && u.callsign === callsign); }
    refFor(kind, id) {
      if (kind === 'site') return this.site(id);
      if (kind === 'base') return this.base(id);
      return this.staticBy(id);
    }
    targetId(ref) { return ref.kind === 'sam' || ref.kind === 'radar' ? ref.callsign : ref.id; }

    log(side, text, cls, extra) {
      this.events.push({ n: ++this.evSeq, t: this.t, side, text, cls: cls || 'info', ...extra });
      if (this.events.length > 500) this.events.shift();
    }
    clock(t) {
      const s = this.cfg.start;
      const tot = s.h * 3600 + s.m * 60 + Math.floor(t == null ? this.t : t);
      const h = Math.floor(tot / 3600) % 24, m = Math.floor(tot / 60) % 60, sec = tot % 60;
      return `${String(h).padStart(2, '0')}${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}Z`;
    }

    // ---------- queries ----------
    isAlive(e) {
      if (!e) return false;
      if (e.kind === 'air') return e.state === 'air';
      if (e.kind === 'cruise') return !e.dead;
      if (e.kind === 'sam' || e.kind === 'radar') return !e.dead;
      return true;
    }
    isLinked(u) { return u.kind !== 'air' || u.def.linked; }
    emitting(u) {
      if (u.kind === 'air') return u.state === 'air' && !u.ghost && (u.radarOn || u.def.role === 'aew');
      return (u.kind === 'sam' || u.kind === 'radar') && !u.dead && u.radarOn;
    }
    sensorRange(u) {
      if (u.kind === 'air') return this.emitting(u) ? u.def.radar : 0;
      return this.emitting(u) ? u.range : 0;
    }
    targetsOf(side) {
      const out = [];
      for (const u of this.units) if (u.side === side && u.kind === 'air' && u.state === 'air') out.push(u);
      for (const m of this.missiles) if (m.side === side && m.kind === 'cruise' && !m.dead) out.push(m);
      return out;
    }
    isDrone(e) { return e.kind === 'air' && e.def.role === 'drone'; }
    manned(e) { return e.kind === 'air' && !e.ghost && e.def.role !== 'drone'; }
    airborne(side) {
      let n = 0;
      for (const u of this.units) if (u.side === side && u.state === 'air' && this.manned(u)) n += u.count;
      return n;
    }
    baseOpen(b) { return b.damage < CLOSED_AT; }
    detects(s, t) {
      let R0 = this.sensorRange(s);
      if (!R0) return false;
      const rcs = t.kind === 'air' ? t.def.rcs : t.w.rcs;
      const low = t.kind === 'cruise' ? t.w.low : !!t.def.low;
      let f = Math.pow(rcs / 5, 0.25);
      if (low) f *= s.kind === 'air' ? (s.def.role === 'aew' ? 0.7 : 0.55) : 0.35;
      if (t.kind === 'air') f *= 1 - t.def.ew * 0.3;
      const bj = this.ew[t.side].barrage;
      if (bj.until > this.t && Math.hypot(t.x - bj.x, t.y - bj.y) < EW.barrage.r) f *= 0.55;
      const d = dist(s, t);
      if (d > R0 * f) return false;
      if (s.kind === 'air' && s.def.role === 'fighter') {
        if (Math.abs(angNorm(bearing(s, t) - s.hdg)) > GIMBAL) return false;
      }
      if (s.kind === 'air' && this.notching(t, s)) return false;
      return true;
    }
    // A target flying at ~90 degrees to a pulse-Doppler radar has almost no closing
    // speed, so the radar filters it out with the ground clutter ("the notch").
    notching(t, obs) {
      if (t.kind !== 'air' || !t.defending || t.defMode !== 'notch') return false;
      const aspect = Math.abs(angNorm(t.hdg - bearing(obs, t)));
      return Math.abs(aspect - Math.PI / 2) < 20 * DEG;
    }
    // Rough look at an incoming missile: its Mach number, distance, and whether it
    // still has the energy to reach the target if the target keeps doing what it does.
    missileThreat(m, dragging) {
      const t = m.target;
      const d = dist(m, t);
      const los = bearing(m, t);
      const tv = vel(t);
      const away = dragging ? t.def.dash : tv.x * Math.sin(los) - tv.y * Math.cos(los);
      const mach = m.spd / 0.295;
      const need = Math.max(V_MIN, (dragging ? t.def.dash : t.spd) * 1.1);
      // Fly the missile forward in 1 s steps against a target holding its current motion.
      let v = m.spd, gap = d, tm = m.t;
      for (let i = 0; i < 240; i++) {
        if (tm < m.w.burn) v = Math.min(m.w.vMax, v + 0.3); else v -= m.w.drag * v;
        tm += 1;
        gap -= v - away;
        if (gap <= 0) return { mach, d, lethal: v > need, tgo: i + 1 };
        if (v < need) break;
      }
      return { mach, d, lethal: false };
    }
    autoDefMode(u, m) {
      if (!m || m.kind === 'srm') return 'drag';
      // Drag if the missile can be outrun; if it is too fast to outrun, notch it.
      return this.missileThreat(m, true).lethal ? 'notch' : 'drag';
    }
    // Can unit u launch on / guide against t using its own radar or the data link?
    fireQuality(u, t) {
      const seen = t.seen && t.seen[u.side];
      if (!seen || !seen.any) return false;
      if (seen.by.includes(u.id)) return true;
      return this.isLinked(u) && seen.net;
    }
    ownTrack(u, t) {
      const seen = t.seen && t.seen[u.side];
      return !!(seen && seen.by.includes(u.id));
    }
    inFlightAt(side, t) {
      let n = 0;
      for (const m of this.missiles) if (m.side === side && m.target === t && m.kind !== 'cruise') n++;
      return n;
    }
    // Rough max launch range (km) of weapon w from shooter against target t,
    // accounting for target closing speed and shooter launch speed.
    maxRange(w, shooter, t) {
      const d = Math.max(0.1, dist(shooter, t));
      const tv = vel(t);
      const closing = (tv.x * (shooter.x - t.x) + tv.y * (shooter.y - t.y)) / d;
      // Only half credit for a closing target: it can still turn away.
      const eff = closing > 0 ? closing * 0.5 : closing;
      const bonus = shooter.kind === 'air' ? shooter.spd * 20 : 0;
      return Math.max(w.range * 0.3, (w.range + eff * w.tMax * 0.8 + bonus) * 0.85);
    }
    chooseWeapon(u, t, factor) {
      const d = dist(u, t);
      const pool = u.kind === 'sam' ? (u.ammo > 0 ? { [u.def.weapon]: u.ammo } : {}) : u.wpn;
      let best = null, bestRange = Infinity;
      for (const k in pool) {
        if (!pool[k]) continue;
        const w = WEAPONS[k];
        if (w.kind === 'cruise') continue;
        if (w.kind === 'srm' && d > w.range * 0.9) continue;
        const R = this.maxRange(w, u, t) * factor;
        if (R >= d && w.range < bestRange) { best = k; bestRange = w.range; }
      }
      return best;
    }
    bestRange(u, kind) {
      let r = 0;
      for (const k in u.wpn) if (u.wpn[k] && WEAPONS[k].kind === (kind || 'aam')) r = Math.max(r, WEAPONS[k].range);
      return r;
    }
    hasAAM(u) {
      for (const k in u.wpn) if (u.wpn[k] && (WEAPONS[k].kind === 'aam' || WEAPONS[k].kind === 'srm')) return true;
      return false;
    }
    // True when every remaining BVR missile can be guided by third-party link tracks.
    tpOnly(u) {
      for (const k in u.wpn) if (u.wpn[k] && WEAPONS[k].kind === 'aam' && !WEAPONS[k].tp) return false;
      return true;
    }
    // A missile needs the shooter's own radar track unless it can use link tracks.
    canGuide(u, t, k) {
      const w = WEAPONS[k];
      if (w.kind === 'srm' || u.kind === 'sam') return true;
      return this.ownTrack(u, t) || (w.tp && this.isLinked(u) && t.seen[u.side] && t.seen[u.side].net);
    }
    strikeWeapon(u) {
      let best = null;
      for (const k in u.wpn) if (u.wpn[k] && WEAPONS[k].kind === 'cruise' && (!best || WEAPONS[k].range > WEAPONS[best].range)) best = k;
      return best;
    }
    hasAEW(side) {
      return this.units.some(u => u.side === side && u.kind === 'air' && u.state === 'air' && u.def.role === 'aew');
    }
    trackFor(side, e) { return this.tracks[side].get(e.id); }
    nearestOpenBase(side, p, type) {
      let best = null, bd = Infinity;
      for (const b of this.bases) {
        if (b.side !== side || !this.baseOpen(b)) continue;
        const d = dist(b, p) * (type && b.home === type ? 0.7 : 1);
        if (d < bd) { bd = d; best = b; }
      }
      return best;
    }

    // ---------- sensors & picture ----------
    sweep() {
      for (const side of SIDES) {
        const enemy = ENEMY[side];
        const jammed = this.ew[enemy].jam.until > this.t;   // our data link is being jammed
        const sensors = this.units.filter(u => u.side === side && this.emitting(u));
        const table = this.tracks[side];
        for (const t of this.targetsOf(enemy)) {
          const by = [];
          let net = false;
          for (const s of sensors) {
            if (this.detects(s, t)) { by.push(s.id); if (this.isLinked(s) && !jammed) net = true; }
          }
          t.seen[side] = { any: by.length > 0, net, by };
          if (by.length) this.updateTrack(side, t, 'radar');
        }
        // Passive detection (RWR / ESM) of enemy emitters.
        const listeners = this.units.filter(u => u.side === side && (u.kind === 'air' ? u.state === 'air' : !u.dead));
        for (const e of this.units) {
          if (e.side !== enemy || !this.emitting(e)) continue;
          const tr = table.get(e.id);
          if (tr && tr.q === 'radar' && this.t - tr.t < 2) continue;
          if (tr && tr.static) continue;
          const R = Math.min(450, this.sensorRange(e) * 1.25);
          if (listeners.some(l => dist(l, e) <= R)) this.updateTrack(side, e, 'esm');
        }
        for (const [id, tr] of table) {
          if (tr.static) { if (tr.ref.dead && tr.bda) table.delete(id); continue; }
          if (!this.isAlive(tr.ref) || this.t - tr.t > 45) table.delete(id);
        }
      }
      this.checkBaseThreats();
      this.strikerWarnings();
    }
    // Early warning: flag enemy strike jets when first detected and again when close.
    strikerWarnings() {
      for (const side of SIDES) {
        for (const tr of this.tracks[side].values()) {
          const e = tr.ref;
          if (e.kind !== 'air' || e.ghost || !this.isAlive(e) || !e.order || e.order.type !== 'strike' || !e.order.list.length) continue;
          const job = e.order.list[0];
          const tgt = this.refFor(job.kind, job.id);
          if (!tgt) continue;
          const d = dist(e, tgt);
          tr.striker = this.targetId(tgt);
          if (!tr.warnStage) {
            tr.warnStage = 1;
            this.log(side, `⚠ STRIKER INBOUND: T${tr.num} ${e.def.name}${e.count > 1 ? ' ×' + e.count : ''} heading for ${tr.striker}, ${d.toFixed(0)} km out. Send a CHASE.`, 'loss', { striker: e });
          } else if (tr.warnStage === 1 && d < 100) {
            tr.warnStage = 2;
            this.log(side, `⚠ STRIKER CLOSING: T${tr.num} is ${d.toFixed(0)} km from ${tr.striker}`, 'loss', { striker: e });
          }
        }
      }
    }
    updateTrack(side, e, q) {
      const table = this.tracks[side];
      let tr = table.get(e.id);
      if (!tr) {
        tr = { id: e.id, ref: e, num: ++this.trackNo[side], first: this.t, kind: e.kind, static: e.kind === 'sam' || e.kind === 'radar' };
        table.set(e.id, tr);
        if (e.kind === 'air' && side === this.player && !this.isDrone(e)) {
          this.log(side, `New track T${tr.num}: ${e.def.name}${e.count > 1 ? ' x' + e.count : ''} (${q === 'esm' ? 'passive' : 'radar'})`, 'info');
        }
      }
      if (q === 'esm' && tr.q === 'radar' && this.t - tr.t < 2) return;
      const err = q === 'esm' && !tr.static ? 6 : 0;
      tr.q = q;
      tr.t = this.t;
      tr.x = e.x + (this.rand() - 0.5) * err;
      tr.y = e.y + (this.rand() - 0.5) * err;
      const v = vel(e);
      tr.vx = q === 'esm' ? 0 : v.x;
      tr.vy = q === 'esm' ? 0 : v.y;
      tr.count = e.count;
    }
    trackPos(tr) {
      const dt = Math.min(60, this.t - tr.t);
      return { x: tr.x + tr.vx * dt, y: tr.y + tr.vy * dt };
    }

    // ---------- main step ----------
    update(dt) {
      if (this.over) return;
      const STEP = 0.25;
      while (dt > 1e-6) {
        const h = Math.min(STEP, dt);
        this.step(h);
        dt -= h;
        if (this.over) break;
      }
    }
    step(dt) {
      this.t += dt;
      this.sweepTimer -= dt;
      if (this.sweepTimer <= 0) { this.sweep(); this.sweepTimer = 1; }
      for (const c of this.commanders) c.tick(dt);
      this.missionTimer -= dt;
      if (this.missionTimer <= 0) { this.tickMissions(); this.missionTimer = 2; }
      for (const b of this.bases) this.updateBase(b, dt);
      for (const u of this.units) {
        if (u.kind === 'air') { if (u.state === 'air') this.updateFlight(u, dt); }
        else if (u.kind === 'sam' && !u.dead) this.updateSam(u);
      }
      for (const m of this.missiles) {
        if (m.kind === 'cruise') this.updateCruise(m, dt); else this.updateMissile(m, dt);
      }
      this.missiles = this.missiles.filter(m => !m.dead);
      if (this.units.length > 400) this.units = this.units.filter(u => u.kind !== 'air' || u.state === 'air');
      this.fx = this.fx.filter(f => this.t - f.t < 12);
      this.pointDefence(dt);
      this.esc = Math.max(0, this.esc - dt * 0.5 / 60);
      this.prompts = this.prompts.filter(p => !p.done);
      this.replayTimer -= dt;
      if (this.replayTimer <= 0) { this.record(); this.replayTimer = 3; }
      if (this.night && this.night.ceasefireAt && this.t >= this.night.ceasefireAt) this.finish('CEASEFIRE: hostilities end');
      if (this.t >= this.duration) this.finish('Night over');
    }

    // ---------- bases & sorties ----------
    updateBase(b, dt) {
      if (b.damage > 0) {
        const wasClosed = !this.baseOpen(b);
        b.damage = Math.max(0, b.damage - dt * REPAIR_PER_MIN / 60);
        if (wasClosed && this.baseOpen(b)) this.log(b.side, `${b.id}: runway repaired, base operational`, 'good');
      }
      for (const [ty, p] of Object.entries(b.pools)) {
        if (!p.rearm.length) continue;
        const done = p.rearm.filter(tt => tt <= this.t).length;
        if (done) {
          p.rearm = p.rearm.filter(tt => tt > this.t);
          p.ready += done;
          if (b.side === this.player) this.log(b.side, `${b.id}: ${done} ${AIRCRAFT[ty].name} refuelled and re-armed`, 'info');
        }
      }
    }
    // Weapons one jet of this type would take off with from base b, given stocks.
    loadoutFor(b, type, preset) {
      const per = PRESETS[type][preset || b.preset[type]] || {};
      return per;
    }
    // Launch n aircraft of a type from a base. Returns the new flight or an error string.
    launchFlight(baseId, type, n, opts) {
      opts = opts || {};
      const b = this.base(baseId);
      const def = AIRCRAFT[type];
      const p = b && b.pools[type];
      if (!p) return 'No aircraft of that type here';
      if (!this.baseOpen(b)) return `${b.id} runway is closed`;
      n = Math.min(n, p.ready);
      if (n <= 0) return 'No aircraft ready';
      if (def.role !== 'drone') {
        const room = AIR_CAP[b.side] - this.airborne(b.side);
        if (room <= 0) return `Airborne limit reached (${AIR_CAP[b.side]})`;
        n = Math.min(n, room);
      }
      const preset = opts.preset || b.preset[type];
      const per = PRESETS[type][preset] || {};
      const stock = this.stocks[b.side];
      const wpn = {};
      for (const [k, c] of Object.entries(per)) {
        const take = Math.min(c * n, stock[k] == null ? c * n : stock[k]);
        if (stock[k] != null) stock[k] -= take;
        if (take > 0) wpn[k] = take;
      }
      p.ready -= n;
      const u = this.newFlight(b.side, type, n, b.id);
      u.wpn = wpn;
      u.preset = preset;
      u.ai = opts.ai != null ? opts.ai : b.side !== this.player;
      const order = opts.order || this.defaultOrbit(b);
      u.order = this.cloneOrder(order);
      const aim = order.x != null ? order : this.orderPoint(u) || b;
      u.hdg = bearing(b, aim) || u.hdg;
      if (u.ai && u.order.type === 'orbit') u.cap = { x: u.order.x, y: u.order.y, r: u.order.r || 15 };
      if (u.order.type === 'strike' || u.order.type === 'drone') u.strikeSince = this.t;
      if (b.side === this.player) this.log(b.side, `${u.callsign}: ${n} ${def.name} airborne from ${b.id}`, 'info');
      return u;
    }
    defaultOrbit(b) {
      // Hold 25 km off the base, toward the frontier.
      const h = b.side === 'IN' ? -90 * DEG : 90 * DEG;
      return { type: 'orbit', x: b.x + Math.sin(h) * 25, y: b.y - Math.cos(h) * 25, r: 12 };
    }
    orderPoint(u) {
      const o = u.order;
      if (!o) return null;
      if (o.x != null) return o;
      if (o.type === 'attack') return o.target;
      if ((o.type === 'strike' || o.type === 'drone') && o.list && o.list.length) return this.refFor(o.list[0].kind, o.list[0].id);
      return null;
    }
    land(u, b) {
      u.state = 'landed';
      u.threats = []; u.defending = false;
      const p = b.pools[u.type] || (b.pools[u.type] = { ready: 0, rearm: [] });
      if (!b.preset[u.type]) b.preset[u.type] = Object.keys(PRESETS[u.type])[0];
      const turn = u.def.turnaround * (1 + b.damage / 100);
      for (let i = 0; i < u.count; i++) p.rearm.push(this.t + turn);
      const stock = this.stocks[u.side];
      for (const [k, c] of Object.entries(u.wpn)) if (stock[k] != null) stock[k] += c;
      if (u.side === this.player) this.log(u.side, `${u.callsign} landed at ${b.id}: refuel and re-arm ${Math.round(turn / 60)} min`, 'info');
    }
    // Damage to a base from a weapon or drone hit: runway damage plus aircraft on the ground.
    hitBase(b, dmg, attacker, killChance, esc) {
      const wasOpen = this.baseOpen(b);
      b.damage = Math.min(100, b.damage + dmg);
      let killed = 0;
      for (const [ty, p] of Object.entries(b.pools)) {
        const onGround = p.ready + p.rearm.length;
        for (let i = 0; i < Math.min(onGround, 4); i++) {
          if (this.rand() >= killChance) continue;
          if (p.ready > 0) p.ready--; else if (p.rearm.length) p.rearm.pop(); else break;
          killed++;
          this.score[attacker].groundKills++;
          this.score[attacker].groundKillValue += AIRCRAFT[ty].value;
          this.score[b.side].groundLost++;
        }
      }
      if (killed) this.log(b.side, `${b.id}: ${killed} aircraft destroyed on the ground`, 'loss');
      if (wasOpen && !this.baseOpen(b)) {
        this.log('ALL', `${b.id}: RUNWAY CLOSED (damage ${Math.round(b.damage)}%)`, 'impact');
        for (const u of this.units) {
          if (u.state === 'air' && u.side === b.side && u.order && u.order.type === 'rtb' && u.order.base === b.id) this.orderRTB(u);
        }
      }
      this.addEsc((esc == null ? 4 : esc) + killed, 'base attack');
      return killed;
    }
    // Every ground target takes 3 hits to destroy. One or two hits leave it damaged:
    // a base loses runway capacity, a SAM site or radar loses range.
    applyHit(ref, attacker, drone) {
      if (ref.dead) return;
      ref.hits = (ref.hits || 0) + 1;
      const id = this.targetId(ref);
      if (ref.kind === 'base') this.hitBase(ref, 34, attacker, drone ? 0.1 : 0.2, drone ? 1 : 4);
      else if (ref.kind === 'sam' || ref.kind === 'radar') {
        if (ref.hits >= 3) {
          ref.dead = true;
          this.fx.push({ kind: 'kill', x: ref.x, y: ref.y, t: this.t });
          this.log(ref.side, `${id} DESTROYED`, 'loss');
          this.addEsc(2, 'air defences destroyed');
        } else {
          ref.range = Math.round(ref.range * 0.75);
          if (ref.kind === 'sam') ref.ammo = Math.ceil(ref.ammo / 2);
          this.log(ref.side, `${id} damaged (${ref.hits}/3 hits): range reduced`, 'warn');
        }
      } else if (ref.hits === 3) this.addEsc(3, 'site destroyed');
      this.targetHit(attacker, ref);
    }
    targetFrac(ref) { return Math.min(3, (ref && ref.hits) || 0) / 3; }
    // Shared bookkeeping when a weapon lands on a target.
    targetHit(attacker, ref) {
      const id = this.targetId(ref);
      const hits = Math.min(3, ref.hits || 0);
      const obj = this.objectives[attacker].find(o => o.ref === ref);
      if (obj) {
        obj.hit = true;
        if (hits >= 3 && !obj.destroyed) { obj.destroyed = true; this.log('ALL', `PRIORITY TARGET ${id} DESTROYED`, 'impact'); }
        else if (hits < 3) this.log('ALL', `PRIORITY TARGET ${id} HIT (${hits}/3)`, 'impact');
        this.score[attacker].objHits.add(id);
      } else this.score[attacker].otherHits.add(ref);
      this.score[attacker].hits++;
    }

    // ---------- flights ----------
    updateFlight(u, dt) {
      const def = u.def;
      if (u.ghost) { this.updateGhost(u, dt); return; }
      if (def.role === 'drone') { this.updateDrone(u, dt); return; }
      // Threat assessment.
      u.threats = u.threats.filter(m => !m.dead && m.target === u);
      const holding = u.holdUntil > this.t;
      const pressing = holding ? [] : u.threats.filter(m => m.lock || (m.warned && dist(m, u) < 40));
      if (u.manualDef && this.t > u.manualDef.until) u.manualDef = null;
      u.defending = pressing.length > 0 || !!u.manualDef;
      const near = u.threats.length ? u.threats.reduce((a, b) => (dist(a, u) < dist(b, u) ? a : b)) : null;
      u.defMode = u.manualDef ? u.manualDef.mode : u.defending ? this.autoDefMode(u, near) : null;

      // Fuel.
      u.fuel -= dt * (u.spd > def.speed * 1.1 ? 2.5 : 1);
      if (!u.bingo && u.order && u.order.type !== 'rtb') {
        const b = this.nearestOpenBase(u.side, u, u.type) || this.base(u.base);
        if (u.fuel < dist(u, b) / def.speed + 600) {
          u.bingo = true;
          const engaged = u.engage || (u.order && u.order.type === 'attack');
          if (!u.ai && engaged && this.promptFor('bingo', u)) {
            // the player decides (see answer())
          } else {
            this.log(u.side, `${u.callsign} BINGO fuel, returning to base`, 'warn');
            this.orderRTB(u);
          }
        }
      }
      if (u.fuel <= 0) { this.lose(u, u.count, 'ran out of fuel'); return; }
      if (!u.crossed && geo.sideOf(u.x, u.y) !== u.side) {
        u.crossed = true;
        this.addEsc(2, 'airspace violation');
        this.log(u.side, `${u.callsign} crossed into enemy airspace`, 'warn');
      }

      if (u.ai) this.aiThink(u);
      else if (u.order && u.order.type === 'attack' && !this.fireQuality(u, u.order.target) && !u.radarOn &&
               this.isAlive(u.order.target) && dist(u, u.order.target) < def.radar) {
        u.radarOn = true;
        this.log(u.side, `${u.callsign} radar ON: no data-link track, using own radar for a firing solution`, 'info');
      }

      let want = u.hdg, spd = def.speed;
      if (u.defending) {
        // Turn away from the nearest missile (or the nearest enemy, for a pre-emptive break).
        const from = near || this.nearestEnemy(u, 400, true);
        const away = from ? bearing(from, u) : u.hdg;
        if (u.defMode === 'notch') {
          const a = angNorm(away + 90 * DEG), b = angNorm(away - 90 * DEG);
          want = Math.abs(angNorm(a - u.hdg)) < Math.abs(angNorm(b - u.hdg)) ? a : b;
        } else want = away;
        spd = def.dash;
        if (near && (!u.chaffAt || this.t - u.chaffAt > 8) && dist(near, u) < 12) {
          u.chaffAt = this.t;
          this.fx.push({ kind: 'chaff', x: u.x, y: u.y, t: this.t });
        }
      } else {
        const r = this.steer(u);
        want = r.hdg; spd = r.spd;
      }
      if (u.state !== 'air') return; // landed during steer
      this.move(u, want, spd, dt, u.defending ? 1.3 : 1);
      if (u.salvo) this.fireSalvo(u);
      if (!u.defending && u.roe === 'free' && def.role === 'fighter') this.autoEngage(u);
    }
    move(u, want, spd, dt, turnMul) {
      const maxTurn = u.def.turn * DEG * dt * (turnMul || 1);
      u.hdg = angNorm(u.hdg + clamp(angNorm(want - u.hdg), -maxTurn, maxTurn));
      u.spd += clamp(spd - u.spd, -0.01 * dt, 0.01 * dt);
      u.x += Math.sin(u.hdg) * u.spd * dt;
      u.y -= Math.cos(u.hdg) * u.spd * dt;
    }

    steer(u) {
      const def = u.def, o = u.order;
      const tgt = u.engage || (o && o.type === 'attack' ? o.target : null);
      if (tgt) return this.steerAttack(u, tgt);
      if (!o) return this.orbitAt(u, { x: u.x, y: u.y, r: 15 });
      if (o.type === 'orbit') return this.orbitAt(u, o);
      if (o.type === 'rtb') {
        let b = this.base(o.base);
        if (!b || !this.baseOpen(b)) {
          b = this.nearestOpenBase(u.side, u, u.type);
          if (!b) return this.orbitAt(u, { x: u.x, y: u.y, r: 15 });
          if (o.base && o.base !== b.id) this.log(u.side, `${u.callsign} diverting to ${b.id}`, 'warn');
          o.base = b.id;
        }
        if (dist(u, b) < 6) { this.land(u, b); return { hdg: u.hdg, spd: 0 }; }
        return { hdg: bearing(u, b), spd: def.speed };
      }
      if (o.type === 'strike') return this.steerStrike(u, o);
      return { hdg: u.hdg, spd: def.speed };
    }
    orbitAt(u, p) {
      const r = p.r || 15;
      const d = dist(u, p);
      let h;
      if (d > r * 1.5) h = bearing(u, p);
      else h = bearing(p, u) + 90 * DEG + clamp((d - r) / r, -1, 1) * 40 * DEG;
      return { hdg: this.leash(u, h), spd: u.def.speed };
    }
    // AI fighters stay inside their own airspace (no deliberate border crossing).
    leash(u, h) {
      if (!u.ai || u.def.role === 'drone') return h;
      // Strike flights and their escorts are allowed into enemy airspace.
      // So are the commander's fighter sweeps and raids (u.offensive).
      if (u.offensive || (u.order && u.order.type === 'strike') || (u.mission && u.mission.kind === 'escort')) return h;
      const ok = hh => {
        const x = u.x + Math.sin(hh) * 25, y = u.y - Math.cos(hh) * 25;
        return geo.sideOf(x, y) === u.side && geo.frontierDist(x, y) > 10;
      };
      if (ok(h)) return h;
      for (const off of [30, 60, 90, 120, 150]) {
        for (const s of [1, -1]) {
          const hh = h + s * off * DEG;
          if (ok(hh)) return hh;
        }
      }
      return h + Math.PI;
    }
    steerAttack(u, t) {
      const def = u.def;
      const done = () => { u.engage = null; if (u.order && u.order.type === 'attack') u.order = { type: 'orbit', ...(u.anchor || { x: u.x, y: u.y }), r: 15 }; };
      const tr = this.trackFor(u.side, t);
      if (!this.isAlive(t) || !tr) { done(); return this.orbitAt(u, u.anchor || { x: u.x, y: u.y, r: 15 }); }
      if (!this.hasAAM(u)) {
        this.log(u.side, `${u.callsign} Winchester (no missiles left)`, 'info');
        done();
        if (u.ai || u.order?.type !== 'strike') this.orderRTB(u);
        return { hdg: u.hdg, spd: def.speed };
      }
      const tp = this.trackPos(tr);
      const brg = bearing(u, tp);
      const mine = this.missiles.filter(m => m.shooter === u && m.target === t && m.kind !== 'cruise');
      const needSupport = mine.some(m => !m.lock && !(m.w.tp && t.seen[u.side] && t.seen[u.side].net));
      if (needSupport) {
        // Crank: keep the target near the edge of the radar gimbal while slowing closure.
        const left = angNorm(brg - 50 * DEG), right = angNorm(brg + 50 * DEG);
        const h = Math.abs(angNorm(left - u.hdg)) < Math.abs(angNorm(right - u.hdg)) ? left : right;
        return { hdg: this.leash(u, h), spd: def.speed };
      }
      const want = t.kind === 'air' ? t.count : 1;
      if (this.inFlightAt(u.side, t) >= want && u.anchor) {
        // Missiles are self-sufficient (data link / seeker): turn cold.
        return { hdg: this.leash(u, bearing(u, u.anchor)), spd: def.speed };
      }
      this.tryFire(u, t, u.ai ? AI_FIRE : 0.95);
      return { hdg: this.leash(u, brg), spd: def.dash };
    }
    steerStrike(u, o) {
      const def = u.def;
      while (o.list.length) {
        const r = this.refFor(o.list[0].kind, o.list[0].id);
        if (r && this.isAlive(r) && (r.hits || 0) < 3) break;
        o.list.shift();
      }
      if (!o.list.length) { this.strikeDone(u); return { hdg: u.hdg, spd: def.speed }; }
      const job = o.list[0];
      const ref = this.refFor(job.kind, job.id);
      const wk = this.strikeWeapon(u);
      if (!wk) { o.list.length = 0; this.strikeDone(u); return { hdg: u.hdg, spd: def.speed }; }
      const w = WEAPONS[wk];
      const d = dist(u, ref);
      const maxR = w.range * 0.85;
      // Hold in safe airspace until an assigned escort has joined up (up to 7 minutes).
      if (!u.firing) {
        const esc = this.missions.find(m => m.kind === 'escort' && m.status !== 'done' && (m.escortUnit === u || (u.mission && m.escortOf === u.mission.id)));
        const e = esc && esc.unit;
        if (esc && !(e && e.state === 'air' && dist(e, u) < 25)) {
          u.waitSince = u.waitSince || this.t;
          if (this.t - u.waitSince < 420) { u.holding = true; return this.holdSafe(u); }
        }
        u.holding = false;
        u.holdPt = null;
      }
      // Press in as close as possible; fire early only if threatened.
      // An escort flying with the striker keeps it pressing in; a missile at it never does.
      const escorted = this.units.some(e => e.side === u.side && e.state === 'air' && e.mission && e.mission.kind === 'escort' && e.mission.status !== 'done' &&
        (e.mission.escortUnit === u || (u.mission && e.mission.escortOf === u.mission.id)) && this.hasAAM(e) && dist(e, u) < 40);
      const threatened = u.threats.length > 0 || (!escorted && this.enemyFightersNear(u, 55));
      if (u.firing || d <= Math.min(maxR, 25) || (threatened && d <= maxR)) {
        if (!u.firing) {
          u.firing = true;
          this.log(u.side, `${u.callsign} ${threatened && d > 25 ? 'under threat, firing early' : 'in close, firing'}: ${d.toFixed(0)} km from ${job.id}`, 'shot');
        }
        if (this.t - u.lastLaunch > 1.5) {
          this.launchCruise(u, wk, ref);
          u.lastLaunch = this.t;
          job.n--;
          if (job.n <= 0 || !u.wpn[wk]) { o.list.shift(); u.firing = false; }
          if (!o.list.length) this.strikeDone(u);
        }
        return { hdg: bearing(u, ref), spd: def.speed };
      }
      // AI strike packages give up if they can't reach a launch point in time.
      if (u.ai && this.t - (u.strikeSince || 0) > 2700) { this.log(u.side, `${u.callsign} strike aborted`, 'info'); this.orderRTB(u); }
      return { hdg: this.leash(u, bearing(u, ref)), spd: def.speed };
    }
    // Waiting for an escort: orbit inside our own airspace, well back from the border.
    holdSafe(u) {
      if (geo.sideOf(u.x, u.y) !== u.side || geo.frontierDist(u.x, u.y) < 20) return { hdg: bearing(u, this.base(u.base)), spd: u.def.speed };
      u.holdPt = u.holdPt || { x: u.x, y: u.y, r: 10 };
      return this.orbitAt(u, u.holdPt);
    }
    enemyFightersNear(u, R) {
      for (const tr of this.tracks[u.side].values()) {
        const e = tr.ref;
        if (e.kind !== 'air' || e.ghost || e.def.role !== 'fighter' || !this.isAlive(e)) continue;
        if (dist(u, this.trackPos(tr)) < R) return true;
      }
      return false;
    }
    strikeDone(u) {
      this.log(u.side, `${u.callsign} weapons released, egressing`, 'info');
      if (u.ai || !this.hasAAM(u)) this.orderRTB(u);
      else u.order = { type: 'orbit', x: u.x + Math.sin(u.hdg + Math.PI) * 40, y: u.y - Math.cos(u.hdg + Math.PI) * 40, r: 15 };
    }
    orderRTB(u, baseId) {
      const b = baseId ? this.base(baseId) : (this.baseOpen(this.base(u.base)) ? this.base(u.base) : this.nearestOpenBase(u.side, u, u.type));
      u.order = { type: 'rtb', base: b ? b.id : u.base };
      u.engage = null;
    }

    // ---------- drones ----------
    updateDrone(u, dt) {
      const def = u.def, o = u.order || { type: 'orbit', x: u.x, y: u.y, r: 15 };
      u.fuel -= dt;
      if (u.fuel <= 0) { u.state = 'expended'; this.log(u.side, `${u.callsign}: drones out of fuel`, 'info'); return; }
      let aim = null, hit = null;
      if (o.type === 'drone' && o.list && o.list.length) {
        while (o.list.length && !this.isAlive(this.refFor(o.list[0].kind, o.list[0].id))) o.list.shift();
        if (o.list.length) { aim = this.refFor(o.list[0].kind, o.list[0].id); hit = aim; }
      } else if (o.type === 'sead' || (o.type === 'orbit' && def.mission === 'sead')) {
        // Anti-radiation seeker: home on the nearest enemy emitter within 45 km.
        let best = null, bd = 45;
        for (const e of this.units) {
          if (e.side === u.side || (e.kind !== 'sam' && e.kind !== 'radar') || !this.emitting(e)) continue;
          const d = dist(u, e);
          if (d < bd) { bd = d; best = e; }
        }
        if (best) { aim = best; hit = best; }
      }
      let want;
      if (aim) want = bearing(u, aim);
      else want = this.orbitAt(u, o.x != null ? o : { x: u.x, y: u.y, r: 20 }).hdg;
      this.move(u, want, aim ? def.dash : def.speed, dt);
      if (hit && dist(u, hit) < 1.5) this.droneImpact(u, hit, o);
    }
    droneImpact(u, ref, o) {
      const attackMode = o.type === 'drone';
      const n = attackMode ? Math.max(1, Math.ceil(u.count / Math.max(1, o.list.length))) : 1;
      let hits = 0;
      for (let i = 0; i < n && u.count > 0; i++) {
        u.count--;
        if (this.rand() > 0.6) continue;
        hits++;
        this.applyHit(ref, u.side, true);
      }
      this.fx.push({ kind: 'impact', x: ref.x, y: ref.y, t: this.t });
      if (hits) {
        this.log('ALL', `DRONE STRIKE: ${hits} hit${hits > 1 ? 's' : ''} on ${this.targetId(ref)}`, 'impact');
        this.addEsc(1, 'drone strike');
      }
      if (attackMode) o.list.shift();
      if (u.count <= 0) u.state = 'expended';
      else if (attackMode && !o.list.length) u.order = { type: 'orbit', x: u.x, y: u.y, r: 15 };
    }
    // Guns and short-range defences around bases and SAM sites shoot at drones nearby.
    pointDefence(dt) {
      for (const u of this.units) {
        if (u.state !== 'air' || !this.isDrone(u)) continue;
        const near = this.bases.some(b => b.side !== u.side && dist(b, u) < 6) ||
          this.units.some(s => s.kind === 'sam' && !s.dead && s.side !== u.side && dist(s, u) < 6);
        if (near && this.rand() < 0.05 * dt) {
          u.count--;
          this.score[ENEMY[u.side]].droneKills++;
          this.fx.push({ kind: 'miss', x: u.x, y: u.y, t: this.t });
          if (u.count <= 0) u.state = 'dead';
        }
      }
    }

    // ---------- electronic warfare ----------
    ewReady(side, kind) { return this.ew[side][kind].ready <= this.t; }
    cmdEW(side, kind, x, y) {
      const e = this.ew[side][kind];
      if (e.ready > this.t) return false;
      e.until = this.t + EW[kind].dur;
      e.ready = this.t + EW[kind].cd;
      if (kind === 'barrage') { e.x = x; e.y = y; }
      if (kind === 'decoy') this.spawnDecoys(side, x, y);
      this.log(side, kind === 'jam' ? 'LINK JAM: enemy data link degraded for 75 s'
        : kind === 'barrage' ? 'BARRAGE JAM: enemy radars degraded in the area for 60 s'
          : 'DECOYS launched: fake fighter tracks heading for the target area', 'shot');
      if (kind === 'jam') this.log(ENEMY[side], 'Data link jammed! Fighters must use their own radar to shoot', 'warn');
      return true;
    }
    spawnDecoys(side, x, y) {
      const type = side === 'IN' ? 'RAFALE' : 'J10C';
      const fr = this.units.filter(u => u.side === side && u.state === 'air' && this.manned(u));
      const from = fr.length ? fr.reduce((a, b) => (dist(a, { x, y }) < dist(b, { x, y }) ? a : b))
        : this.nearestOpenBase(side, { x, y }) || this.bases.find(b => b.side === side);
      for (let i = 0; i < 2; i++) {
        const u = this.newFlight(side, type, 2, this.bases.find(b => b.side === side).id, `DECOY-${i + 1}`);
        u.ghost = true; u.ai = true; u.radarOn = false;
        u.x = from.x + (i ? 6 : -6); u.y = from.y + (i ? 4 : -4);
        u.order = { type: 'orbit', x: x + (i ? 15 : -15), y, r: 15 };
        u.hdg = bearing(u, u.order);
        u.fuel = EW.decoy.dur;
      }
    }
    updateGhost(u, dt) {
      u.fuel -= dt;
      if (u.fuel <= 0) { u.state = 'expended'; return; }
      const r = this.orbitAt(u, u.order);
      this.move(u, r.hdg, u.def.speed, dt);
    }

    // ---------- shooting ----------
    autoEngage(u) {
      if (this.t - u.lastShot < 3) return;
      const enemies = this.targetsOf(ENEMY[u.side]);
      let best = null, bestScore = -1;
      for (const t of enemies) {
        if (!this.fireQuality(u, t)) continue;
        const cheap = t.kind === 'cruise' || this.isDrone(t);
        if (cheap && u.order && u.order.type === 'strike') continue;
        const want = t.kind === 'air' ? t.count : 1;
        if (this.inFlightAt(u.side, t) >= Math.min(want, this.isDrone(t) ? 1 : want)) continue;
        const k = this.chooseWeapon(u, t, AI_FIRE);
        if (!k || !this.canGuide(u, t, k)) continue;
        const off = Math.abs(angNorm(bearing(u, t) - u.hdg));
        if (off > (WEAPONS[k].kind === 'srm' ? 90 : 60) * DEG) continue;
        const prio = this.isDrone(t) ? 0.3 : t.kind === 'cruise' ? 0.5 : t.def.role === 'aew' ? 3 : this.strikeWeapon(t) ? 2 : 1;
        const sc = prio * 1000 - dist(u, t);
        if (sc > bestScore) { bestScore = sc; best = t; }
      }
      if (best) this.tryFire(u, best, AI_FIRE);
    }

    tryFire(u, t, factor) {
      if (this.t - u.lastShot < 3) return false;
      if (!this.fireQuality(u, t)) return false;
      // Two missiles per enemy aircraft (it takes two hits to bring one down).
      const want = this.manned(t) ? 2 : 1;
      if (this.inFlightAt(u.side, t) >= want) return false;
      const k = this.chooseWeapon(u, t, factor);
      if (!k || !this.canGuide(u, t, k)) return false;
      const w = WEAPONS[k];
      if (u.kind === 'air') {
        const off = Math.abs(angNorm(bearing(u, t) - u.hdg));
        if (off > (w.kind === 'srm' ? 90 : 60) * DEG) return false;
        u.wpn[k]--;
      } else u.ammo--;
      u.lastShot = this.t;
      this.launch(u, t, k);
      if (want === 2) u.salvo = { t, k, at: this.t + 1 };
      return true;
    }
    // Second missile of a salvo, one second after the first.
    fireSalvo(u) {
      const s = u.salvo;
      if (!s || this.t < s.at) return;
      u.salvo = null;
      if (!this.isAlive(s.t)) return;
      if (u.kind === 'air') { if (!u.wpn[s.k]) return; u.wpn[s.k]--; } else { if (u.ammo <= 0) return; u.ammo--; }
      this.launch(u, s.t, s.k);
    }

    launch(u, t, k) {
      const w = WEAPONS[k];
      const v = vel(t);
      const m = {
        id: this.nextId++, kind: w.kind, side: u.side, wkey: k, w, x: u.x, y: u.y,
        hdg: u.kind === 'air' ? u.hdg : bearing(u, t), spd: Math.max(0.1, u.spd || 0.1), t: 0,
        target: t, shooter: u, est: { x: t.x, y: t.y, vx: v.x, vy: v.y }, active: false, lock: false,
        warned: false, trail: [], trailT: 0,
      };
      this.missiles.push(m);
      this.score[u.side].shots++;
      const tr = this.trackFor(u.side, t);
      const tname = t.kind === 'air' ? `T${tr ? tr.num : '?'} ${t.def.name}` : `cruise missile`;
      const call = w.kind === 'srm' ? 'FOX-2' : w.kind === 'sam' ? 'BIRD AWAY' : 'FOX-3';
      const how = w.kind === 'aam' && !this.ownTrack(u, t) ? ' (data-link cue, radar silent)' : '';
      if (!this.isDrone(t)) this.log(u.side, `${u.callsign} ${call} ${w.name} at ${tname}, ${dist(u, t).toFixed(0)} km${how}`, 'shot');
      // A radar-guided launch from an emitting shooter tripping the target's RWR.
      if (t.kind === 'air' && !this.isDrone(t) && (u.kind === 'sam' || (u.radarOn && this.ownTrack(u, t)))) {
        m.warned = true;
        this.warn(t, m, 'LAUNCH WARNING');
      }
    }

    warn(t, m, what) {
      if (t.kind !== 'air' || t.ghost || this.isDrone(t)) return;
      if (!t.threats.includes(m)) t.threats.push(m);
      this.log(t.side, `${t.callsign}: ${what}, ${m.w.name} ${dist(m, t).toFixed(0)} km`, 'warn', { alert: true, ref: t });
    }

    // ---------- missiles ----------
    updateMissile(m, dt) {
      const w = m.w, t = m.target;
      const alive = this.isAlive(t);
      m.t += dt;
      if (m.t < w.burn) m.spd = Math.min(w.vMax, m.spd + 0.3 * dt);
      else m.spd -= w.drag * m.spd * dt;

      // Mid-course guidance: updates from the shooter, or the data link for tp missiles.
      let support = m.lock;
      if (!support && alive) {
        const s = m.shooter;
        const shooterOk = this.isAlive(s) && this.ownTrack(s, t) &&
          (s.kind !== 'air' || Math.abs(angNorm(bearing(s, t) - s.hdg)) <= GIMBAL);
        support = shooterOk || (w.tp && t.seen[m.side] && t.seen[m.side].net);
      }
      if (support && alive) {
        const v = vel(t);
        m.est = { x: t.x, y: t.y, vx: v.x, vy: v.y };
      } else {
        m.est.x += m.est.vx * dt; m.est.y += m.est.vy * dt;
      }
      m.supported = support;

      if (!m.active && dist(m, m.est) <= w.seeker) {
        m.active = true;
        if (w.kind === 'aam' && this.isAlive(m.shooter) && !this.isDrone(t)) this.log(m.side, `${m.shooter.callsign} ${w.name} PITBULL (seeker active)`, 'info');
      }
      // A notching target can break a radar seeker's lock.
      if (m.lock && alive && m.kind !== 'srm' && this.notching(t, m) && this.rand() < 0.25 * dt) {
        m.lock = false;
        this.log(t.side, `${t.callsign} notched the ${w.name}: seeker lock broken`, 'good');
      }
      if (m.active && !m.lock && alive) this.trySeeker(m);

      // Lead pursuit toward the (true or estimated) target.
      const aim = m.lock && alive ? { x: t.x, y: t.y, ...(() => { const v = vel(t); return { vx: v.x, vy: v.y }; })() } : m.est;
      const d = Math.hypot(aim.x - m.x, aim.y - m.y);
      const tgo = d / Math.max(0.2, m.spd);
      const ip = { x: aim.x + aim.vx * tgo * 0.8, y: aim.y + aim.vy * tgo * 0.8 };
      const want = bearing(m, ip);
      const rate = (w.kind === 'srm' ? 50 : 30) * DEG * dt;
      m.hdg = angNorm(m.hdg + clamp(angNorm(want - m.hdg), -rate, rate));

      const px = m.x, py = m.y;
      m.x += Math.sin(m.hdg) * m.spd * dt;
      m.y -= Math.cos(m.hdg) * m.spd * dt;
      m.trailT -= dt;
      if (m.trailT <= 0) { m.trail.push({ x: px, y: py }); if (m.trail.length > 24) m.trail.shift(); m.trailT = 1; }

      if (m.lock && alive && dist(m, t) <= m.spd * dt * 1.5 + 0.5) { this.resolve(m); return; }
      if ((m.t > w.burn && m.spd < V_MIN) || m.t > w.tMax * 1.5) {
        m.dead = true;
        m.end = 'energy';
        if (alive && m.lock && !this.isDrone(t)) this.log(t.side, `${t.callsign} defeated a ${w.name} (out of energy)`, 'good');
        else if (alive && !this.isDrone(t)) this.log(m.side, `${w.name} from ${m.shooter.callsign} lost its target`, 'info');
      }
    }
    trySeeker(m) {
      const t = m.target;
      const d = dist(m, t);
      const off = Math.abs(angNorm(bearing(m, t) - m.hdg));
      if (m.kind !== 'srm' && this.notching(t, m)) return;
      if (d <= m.w.seeker * 1.3 && off < 40 * DEG) {
        m.lock = true;
        if (t.kind === 'air' && !m.warned) this.warn(t, m, 'MISSILE WARNING (no prior launch warning!)');
        else if (t.kind === 'air' && !m.activeWarned) this.warn(t, m, 'MISSILE ACTIVE');
        m.warned = true;
        m.activeWarned = true;
      }
    }
    resolve(m) {
      const t = m.target, w = m.w;
      m.dead = true;
      const energy = clamp((m.spd - V_MIN) / (w.vMax * 0.5), 0.25, 1);
      let pk = w.pk * energy;
      if (t.kind === 'air' && !t.ghost && !this.isDrone(t)) {
        pk *= 1 - t.def.ew * 0.4;
        if (t.defending) {
          if (t.def.role === 'aew') pk *= 0.9;
          else if (w.kind !== 'srm' && this.notching(t, m)) pk *= 0.45; // seeker loses the target in clutter
          else pk *= 0.95 - 0.3 * (1 - energy);                         // drag + chaff: works on a tired missile
        }
      } else if (t.kind === 'cruise') pk *= 0.6 * clamp(0.35 / t.w.speed, 0.3, 1);   // fast missiles are hard to hit
      else if (this.isDrone(t)) pk *= 0.6;
      m.pk = pk;
      m.end = 'miss';
      const hit = this.rand() < pk;
      if (hit) {
        m.end = 'hit';
        this.fx.push({ kind: 'kill', x: t.x, y: t.y, t: this.t });
      } else this.fx.push({ kind: 'miss', x: t.x, y: t.y, t: this.t });

      if (t.kind === 'cruise') {
        if (hit) {
          t.dead = true;
          this.score[m.side].intercepts++;
          this.log(m.side, `${t.w.name} intercepted by ${w.name}`, 'kill');
          this.log(t.side, `A ${t.w.name} was shot down`, 'warn');
        }
        return;
      }
      if (this.isDrone(t)) {
        if (hit) { t.count--; this.score[m.side].droneKills++; if (t.count <= 0) t.state = 'dead'; }
        return;
      }
      // One hit damages; a second hit on a damaged flight brings a jet down.
      if (hit && !t.ghost) {
        t.dmg = (t.dmg || 0) + 1;
        if (t.dmg < 2) {
          const tr0 = this.trackFor(m.side, t);
          this.log(m.side, `${w.name} from ${m.shooter.callsign}: HIT on T${tr0 ? tr0.num : '?'} ${t.def.name}, damaged`, 'kill');
          this.log(t.side, `${t.callsign} DAMAGED by a ${w.name}: one more hit will bring a jet down`, 'loss');
          return;
        }
        t.dmg = 0;
      }
      // Fog of war: the shooter only gets a claim, not the truth.
      const r = this.rand();
      const sc = this.score[m.side];
      const tr = this.trackFor(m.side, t);
      const tn = `T${tr ? tr.num : '?'} ${t.def.name}`;
      if (hit ? r < 0.8 : r < 0.1) {
        sc.claimed++;
        if (!hit || t.ghost) sc.falseClaims++;
        this.log(m.side, `SPLASH: ${tn} (probable kill, ${w.name} from ${m.shooter.callsign})`, 'kill');
      } else if (hit ? true : r < 0.3) {
        this.log(m.side, `${w.name} from ${m.shooter.callsign}: result UNCONFIRMED on ${tn}`, 'info');
      } else this.log(m.side, `${w.name} from ${m.shooter.callsign} missed ${tn}`, 'info');
      if (!hit) {
        if (!t.ghost) this.log(t.side, `${t.callsign} evaded a ${w.name}`, 'good');
        return;
      }
      if (t.ghost) { t.count--; if (t.count <= 0) t.state = 'dead'; return; }
      sc.kills++;
      sc.killValue += t.def.value;
      this.addEsc(t.def.role === 'aew' ? 8 : 2, 'aircraft shot down');
      this.lose(t, 1, `shot down by ${w.name}`);
    }
    lose(u, n, why) {
      u.count -= n;
      this.score[u.side].lost += n;
      this.log(u.side, `${u.callsign}: ${u.def.name} LOST, ${why}`, 'loss');
      if (u.count <= 0) { u.state = 'dead'; u.threats = []; }
    }

    launchCruise(u, k, ref) {
      const w = WEAPONS[k];
      u.wpn[k]--;
      const m = {
        id: this.nextId++, kind: 'cruise', side: u.side, wkey: k, w, x: u.x, y: u.y,
        hdg: bearing(u, ref), spd: w.speed, t: 0, tgt: ref, shooter: u, count: 1, seen: {}, trail: [], trailT: 0,
        launchDist: dist(u, ref),
      };
      this.missiles.push(m);
      this.log(u.side, `${u.callsign} launched ${w.name} at ${this.targetId(ref)} (${dist(u, ref).toFixed(0)} km)`, 'shot');
    }
    updateCruise(m, dt) {
      m.t += dt;
      const ref = m.tgt;
      const want = bearing(m, ref);
      m.hdg = angNorm(m.hdg + clamp(angNorm(want - m.hdg), -5 * DEG * dt, 5 * DEG * dt));
      const px = m.x, py = m.y;
      m.x += Math.sin(m.hdg) * m.spd * dt;
      m.y -= Math.cos(m.hdg) * m.spd * dt;
      m.trailT -= dt;
      if (m.trailT <= 0) { m.trail.push({ x: px, y: py }); if (m.trail.length > 24) m.trail.shift(); m.trailT = m.spd > 0.5 ? 1 : 4; }
      // Short-range guns and missiles along the route: the longer a weapon flies over
      // enemy ground, the more likely it is shot down. Fast weapons are harder to hit.
      if (geo.sideOf(m.x, m.y) !== m.side && this.rand() < (m.spd > 0.6 ? 0.0012 : 0.0025) * m.spd * dt) {
        m.dead = true;
        this.score[ENEMY[m.side]].intercepts++;
        this.fx.push({ kind: 'miss', x: m.x, y: m.y, t: this.t });
        this.log(ENEMY[m.side], `Ground air defences shot down a ${m.w.name}`, 'good');
        this.log(m.side, `A ${m.w.name} was shot down by ground fire (launched ${m.launchDist.toFixed(0)} km out)`, 'warn');
        return;
      }
      if (dist(m, ref) < m.spd * dt + 0.3) {
        m.dead = true;
        // Closer launches are more accurate.
        if (this.rand() < clamp(0.95 - m.launchDist * 0.0015, 0.55, 0.95)) {
          this.fx.push({ kind: 'impact', x: ref.x, y: ref.y, t: this.t });
          this.log('ALL', `IMPACT: ${m.w.name} hit ${this.targetId(ref)}`, 'impact');
          this.applyHit(ref, m.side, false);
          if (ref.kind === 'site') this.addEsc(2, 'strike');
        } else this.log(m.side, `${m.w.name} missed ${this.targetId(ref)} (launched ${m.launchDist.toFixed(0)} km out)`, 'info');
      }
      if (m.t > m.w.range / m.w.speed * 1.3) m.dead = true;
    }

    // ---------- SAMs ----------
    updateSam(u) {
      if (u.salvo) this.fireSalvo(u);
      if (!u.radarOn || u.ammo <= 0 || this.t - u.lastShot < 4) return;
      let best = null, bestScore = -Infinity;
      for (const t of this.targetsOf(ENEMY[u.side])) {
        if (!this.fireQuality(u, t)) continue;
        if (!this.chooseWeapon(u, t, AI_FIRE)) continue;
        const sc = (this.isDrone(t) ? 200 : t.kind === 'air' ? 1000 : 500) - dist(u, t);
        if (sc > bestScore) { bestScore = sc; best = t; }
      }
      if (best) this.tryFire(u, best, AI_FIRE);
    }

    // ---------- AI (per flight) ----------
    aiThink(u) {
      const def = u.def;
      if (def.role === 'aew') return;
      // Radar discipline: linked fighters stay silent while an AEW&C builds the picture.
      const aew = this.hasAEW(u.side) && this.ew[ENEMY[u.side]].jam.until <= this.t;
      const tgt = u.engage || (u.order && u.order.type === 'attack' ? u.order.target : null);
      const near = tgt && this.isAlive(tgt) && dist(u, tgt) < def.radar;
      const guiding = this.missiles.some(m => m.shooter === u && !m.lock && !m.w.tp && m.kind !== 'cruise');
      if (u.radarManual) { /* the player set this flight's radar */ }
      else if (def.linked) u.radarOn = !aew || guiding || (near && (!this.fireQuality(u, tgt) || !this.tpOnly(u)));
      else u.radarOn = !!tgt || this.nearestEnemy(u, 150) != null;

      if (!this.hasAAM(u) && !this.strikeWeapon(u) && u.order && u.order.type !== 'rtb') { u.engage = null; this.orderRTB(u); return; }
      if (!u.order || u.order.type !== 'orbit' || !u.cap) return;
      if (u.engage && !this.isAlive(u.engage)) u.engage = null;
      if (u.engage && dist(u, u.cap) > 140) u.engage = null;
      if (!u.engage && this.hasAAM(u)) {
        const commit = Math.max(60, this.bestRange(u) * 1.3);
        // Patrols guarding an asset also go after cruise missiles.
        const guard = u.mission && u.mission.patrolOf;
        const e = this.nearestEnemy(u, commit, !guard, true);
        if (e) { u.engage = e; u.anchor = { x: u.cap.x, y: u.cap.y, r: u.cap.r }; }
      }
    }
    nearestEnemy(u, range, airOnly, mannedOnly) {
      let best = null, bd = range;
      for (const tr of this.tracks[u.side].values()) {
        const e = tr.ref;
        if (!this.isAlive(e) || (airOnly && e.kind !== 'air')) continue;
        if (e.kind !== 'air' && e.kind !== 'cruise') continue;
        if (mannedOnly && this.isDrone(e)) continue;
        const d = dist(u, this.trackPos(tr));
        if (d < bd) { bd = d; best = e; }
      }
      return best;
    }

    // ---------- escalation ----------
    addEsc(n, why) {
      const before = this.esc;
      this.esc = Math.min(100, this.esc + n);
      for (const mark of [50, 75, 90]) {
        if (before < mark && this.esc >= mark && !this.escMarks.has(mark)) {
          this.escMarks.add(mark);
          this.log('ALL', `ESCALATION ${mark}%: ${mark === 50 ? 'international pressure rising' : mark === 75 ? 'leaders under pressure to stand down' : 'crisis is close to spiralling out of control'}`, 'warn');
        }
      }
      if (this.player && before < 80 && this.esc >= 80) this.promptFor('escalation', null);
      if (this.esc >= 100) this.finish('ESCALATION 100%: the crisis spiralled out of control', true);
      void why;
    }

    // ---------- decision cards (player only) ----------
    promptFor(kind, ref) {
      if (!this.player || this.over) return false;
      const key = kind + (ref ? ':' + (ref.id) : '');
      if ((this.promptCd[key] || 0) > this.t || (this.promptCd[kind] || 0) > this.t) return false;
      if (this.prompts.some(p => !p.done)) return false;
      if (kind === 'aew' && this.t < 180) return false;
      const cds = { guide: 30, bingo: 60, base: 300, aew: 600, escalation: 99999 };
      this.promptCd[key] = this.t + cds[kind];
      this.promptCd[kind] = this.t + 20;
      const P = (text, options, def) => {
        this.prompts.push({ id: this.nextId++, kind, ref, text, options, def, t: this.t });
        return true;
      };
      if (kind === 'guide') return P(`${ref.callsign} is being targeted while its radar-guided missile is still in flight.`,
        [['hold', 'HOLD & GUIDE', 'Keep cranking until the missile goes active. Risky.'], ['drag', 'BREAK', 'Run. Your missile goes blind.'], ['notch', 'NOTCH', 'Beam the threat. Your missile may lose support.']], 'drag');
      if (kind === 'bingo') return P(`${ref.callsign} is at BINGO fuel in the middle of a fight.`,
        [['rtb', 'RETURN TO BASE', 'Disengage now and land.'], ['press', 'PRESS ON', 'Keep fighting. If the tanks run dry, the jets are lost.']], 'rtb');
      if (kind === 'base') return P(`Enemy weapons are inbound to ${ref.id}. Aircraft on the ground are exposed.`,
        [['scramble', 'SCRAMBLE READY JETS', 'Get them airborne before impact (uses airborne slots).'], ['shelter', 'STAY IN SHELTERS', 'Keep slots free and hope the shelters hold.']], 'shelter');
      if (kind === 'aew') return P(`An enemy AEW&C (${ref.def.name}) is within reach. Shooting it down is a big blow, but it raises escalation sharply.`,
        [['fire', 'WEAPONS FREE ON AEW&C', 'Clear your fighters and SAMs to engage it for 5 minutes.'], ['hold', 'HOLD FIRE', 'Leave it alone for now.']], 'hold');
      if (kind === 'escalation') return P('Escalation has passed 80%. International pressure is intense and the next step could spiral out of control.',
        [['stand', 'STAND DOWN STRIKES', 'Recall strike flights for 5 min. Escalation -15.'], ['press', 'PRESS ON', 'Keep going and risk losing everything at 100%.']], 'stand');
      return false;
    }
    answer(p, key) {
      if (p.done) return;
      p.done = true;
      p.choice = key;
      const u = p.ref;
      if (p.kind === 'guide' && this.isAlive(u)) {
        if (key === 'hold') { u.holdUntil = this.t + 25; u.manualDef = null; this.log(u.side, `${u.callsign} holding to guide its missile`, 'warn'); }
        else this.cmdDefend(u, key);
      } else if (p.kind === 'bingo' && this.isAlive(u)) {
        if (key === 'rtb') this.orderRTB(u);
        else this.log(u.side, `${u.callsign} pressing on below bingo fuel`, 'warn');
      } else if (p.kind === 'base') {
        if (key === 'scramble') this.scrambleAll(u, true);
      } else if (p.kind === 'aew') {
        if (key === 'fire') { this.aewClear[this.player] = this.t + 300; this.log(this.player, 'Weapons free on enemy AEW&C for 5 minutes', 'warn'); }
      } else if (p.kind === 'escalation') {
        if (key === 'stand') this.standDown(this.player);
      }
    }
    standDown(side) {
      this.esc = Math.max(0, this.esc - 15);
      for (const u of this.units) {
        if (u.side === side && u.state === 'air' && u.order && (u.order.type === 'strike' || u.order.type === 'drone')) this.orderRTB(u);
      }
      this.log('ALL', `${side === 'IN' ? 'India' : 'Pakistan'} stands down strike operations. Escalation eases.`, 'good');
    }
    // Enemy weapons/drones heading for one of our bases: offer to get jets off the ground.
    checkBaseThreats() {
      for (const side of SIDES) {
        for (const tr of this.tracks[side].values()) {
          const e = tr.ref;
          let ref = null;
          if (e.kind === 'cruise' && !e.dead) ref = e.tgt;
          else if (this.isDrone(e) && e.state === 'air' && e.order && e.order.type === 'drone' && e.order.list.length) ref = this.refFor(e.order.list[0].kind, e.order.list[0].id);
          if (!ref || ref.kind !== 'base' || ref.side !== side || dist(e, ref) > 150) continue;
          const ready = Object.values(ref.pools).reduce((a, p) => a + p.ready, 0);
          if (!ready) continue;
          // The player's bases defend themselves through autoDefend(); AI bases scramble.
          if (side !== this.player) for (const c of this.commanders) if (c.side === side) c.baseThreat(ref);
        }
      }
    }
    // Launch every ready jet at a base, in flights of up to four.
    scrambleAll(b, away) {
      let launched = 0;
      for (const ty of Object.keys(b.pools)) {
        if (AIRCRAFT[ty].role === 'drone') continue;
        while (b.pools[ty].ready > 0) {
          const order = away ? { type: 'orbit', x: b.x + (this.rand() - 0.5) * 60, y: b.y + (this.rand() - 0.5) * 60, r: 12 } : null;
          const u = this.launchFlight(b.id, ty, Math.min(4, b.pools[ty].ready), { order: order || undefined });
          if (typeof u === 'string') return launched;
          launched += u.count;
        }
      }
      return launched;
    }

    // ---------- player commands ----------
    cmdMove(u, x, y) {
      if (u.mission && u.mission.at) u.mission.at = { x, y };
      if (this.isDrone(u)) { u.order = { type: u.def.mission === 'sead' ? 'sead' : 'orbit', x, y, r: 20 }; return; }
      u.order = { type: 'orbit', x, y, r: u.def.role === 'aew' ? 25 : 15 };
      u.engage = null; u.anchor = null;
      if (u.ai) u.cap = { x, y, r: 15 };
    }
    cmdAttack(u, t) {
      if (u.ai && u.cap && u.order && u.order.type === 'orbit') {
        // A patrol keeps its station: engage, then return to it.
        u.engage = t;
        u.anchor = { x: u.cap.x, y: u.cap.y, r: u.cap.r };
        return;
      }
      u.anchor = { x: u.x, y: u.y, r: 15 };
      u.order = { type: 'attack', target: t };
      u.engage = null;
    }
    cmdStrike(u, ref) {
      const id = this.targetId(ref);
      if (this.isDrone(u)) { u.order = { type: 'drone', list: [{ kind: ref.kind, id }] }; return true; }
      const wk = this.strikeWeapon(u);
      if (!wk) return false;
      u.order = { type: 'strike', list: [{ kind: ref.kind, id, n: Math.min(2, u.wpn[wk]) }] };
      u.strikeSince = this.t;
      u.engage = null;
      return true;
    }
    cmdRTB(u) { this.orderRTB(u); }
    // Manual defence: 'drag' (BREAK), 'notch', or null to hand back to automatic.
    cmdDefend(u, mode) {
      u.manualDef = mode ? { mode, until: this.t + 45 } : null;
      u.holdUntil = 0;
    }
    cmdSamRadar(u, on) { u.radarOn = on; }

    // ---------- missions: the player plans, the AI flies ----------
    // kind: cap | aew | strike | escort | sead | attack. A CAP or AEW&C mission keeps its
    // station manned: when a flight heads home, a relief flight launches from the base.
    createMission(m) {
      const pre = m.auto ? 'DEFEND' : { cap: 'CAP', aew: 'AEW', strike: 'STRIKE', escort: 'ESCORT', sead: 'SEAD', attack: 'ATTACK', chase: 'CHASE' }[m.kind];
      this.missionNo[pre] = (this.missionNo[pre] || 0) + 1;
      const ms = {
        id: this.nextId++, name: `${pre}-${this.missionNo[pre]}`, status: 'planned', unit: null, flights: 0,
        repeat: m.kind === 'cap' || m.kind === 'aew', launchAt: this.t, ...m,
      };
      if (ms.kind === 'strike' && ms.tot) ms.launchAt = this.strikeLaunchTime(ms);
      this.missions.push(ms);
      if (ms.unit) this.bindMission(ms, ms.unit);
      this.tickMissions();
      if (ms.side === this.player && !ms.auto) {
        const when = ms.launchAt > this.t + 5 ? `, takes off at ${this.clock(ms.launchAt).slice(0, 4)}Z` : '';
        this.log(ms.side, `${ms.name} planned: ${ms.n} ${AIRCRAFT[ms.type].name} from ${ms.base}${when}`, 'info');
      }
      return ms;
    }
    missionWeapon(type, preset) {
      let best = null;
      for (const k of Object.keys(PRESETS[type][preset] || {})) if (WEAPONS[k].kind === 'cruise' && (!best || WEAPONS[k].range > WEAPONS[best].range)) best = k;
      return best;
    }
    // When a strike must take off so its weapons arrive at the time-on-target.
    strikeLaunchTime(ms) {
      const b = this.base(ms.base), ref = this.refFor(ms.target.kind, ms.target.id);
      const wk = this.missionWeapon(ms.type, ms.preset);
      if (!b || !ref || !wk) return this.t;
      const w = WEAPONS[wk];
      const d = dist(b, ref), launchDist = Math.min(d, w.range * 0.85);
      return ms.tot - (d - launchDist) / AIRCRAFT[ms.type].speed - launchDist / w.speed - 30;
    }
    missionArrival(ms) {
      const b = this.base(ms.base), ref = this.refFor(ms.target.kind, ms.target.id);
      const wk = this.missionWeapon(ms.type, ms.preset);
      if (!b || !ref || !wk) return this.t;
      const w = WEAPONS[wk];
      const d = dist(b, ref), launchDist = Math.min(d, w.range * 0.85);
      return Math.max(this.t, ms.launchAt) + (d - launchDist) / AIRCRAFT[ms.type].speed + launchDist / w.speed + 30;
    }
    // Give an airborne flight a new mission (it keeps its current weapons).
    assignMission(u, m) {
      if (u.mission && u.mission.unit === u && u.mission.status !== 'done') u.mission.status = 'done';
      const ms = this.createMission({ ...m, side: u.side, base: u.base, type: u.type, n: u.count, unit: u });
      return ms;
    }
    bindMission(ms, u) {
      u.mission = ms;
      u.ai = true;
      u.engage = null;
      u.order = this.missionOrder(ms);
      if (ms.kind === 'chase') u.anchor = { x: u.x, y: u.y, r: 15 };
      if (u.order.type === 'strike' || u.order.type === 'drone') u.strikeSince = this.t;
      ms.flights = Math.max(1, ms.flights);
      ms.status = 'airborne';
      if (ms.kind === 'escort') this.steerEscort(ms, u);
    }
    missionOrder(ms) {
      if (ms.kind === 'chase') return { type: 'attack', target: ms.prey };
      if (ms.kind === 'strike' && ms.unit && ms.unit.state === 'air') {
        const n = Object.entries(ms.unit.wpn).filter(([k]) => WEAPONS[k].kind === 'cruise').reduce((a, [, c]) => a + c, 0);
        return { type: 'strike', list: [{ ...ms.target, n }] };
      }
      if (ms.kind === 'cap' || ms.kind === 'aew') return { type: 'orbit', x: ms.at.x, y: ms.at.y, r: ms.kind === 'aew' ? 25 : 15 };
      if (ms.kind === 'sead') return { type: 'sead', x: ms.at.x, y: ms.at.y, r: 20 };
      if (ms.kind === 'attack') return { type: 'drone', list: [{ ...ms.target }] };
      if (ms.kind === 'strike') {
        const per = PRESETS[ms.type][ms.preset] || {};
        const n = Object.entries(per).filter(([k]) => WEAPONS[k].kind === 'cruise').reduce((a, [, c]) => a + c, 0) * ms.n;
        return { type: 'strike', list: [{ ...ms.target, n }] };
      }
      const b = this.base(ms.base);
      return { type: 'orbit', x: b.x, y: b.y, r: 12 };
    }
    tickMissions() {
      for (const ms of this.missions) {
        if (ms.status === 'done') continue;
        const u = ms.unit, alive = u && u.state === 'air';
        if (alive) {
          const home = u.order && u.order.type === 'rtb';
          ms.status = home ? 'returning' : ms.kind === 'strike' ? (u.order.type === 'strike' ? 'inbound' : 'egress') : ms.kind === 'escort' ? 'escorting' : ms.kind === 'chase' ? 'chasing' : 'on station';
          if (ms.kind === 'escort' && !home) this.steerEscort(ms, u);
          if (ms.kind === 'chase' && !home) this.checkChase(ms, u);
          if (ms.patrolOf && !home) this.checkPatrol(ms, u);
          if (!home || !ms.repeat) continue;
        }
        const once = !ms.repeat;
        if (once && ms.flights > 0) { if (!alive) ms.status = 'done'; continue; }
        if (this.t < ms.launchAt) { ms.status = 'scheduled'; continue; }
        if (ms.kind === 'escort' && !ms.escortUnit) {
          const s = this.missions.find(x => x.id === ms.escortOf);
          if (!s || s.status === 'done') { ms.status = 'done'; continue; }
          if (!(s.unit && s.unit.state === 'air')) { ms.status = 'waiting for strike'; continue; }
        }
        if (ms.kind === 'chase' && !this.isAlive(ms.prey)) { ms.status = 'done'; continue; }
        const r = this.launchFlight(ms.base, ms.type, ms.n, { order: this.missionOrder(ms), preset: ms.preset, ai: true });
        if (typeof r === 'string') { ms.status = 'waiting: ' + r.toLowerCase(); continue; }
        if (alive) u.mission = null;                   // the old flight just flies home
        r.mission = ms;
        ms.unit = r;
        ms.flights++;
        ms.status = 'launching';
        if (r.order.type === 'strike' || r.order.type === 'drone') r.strikeSince = this.t;
        if (ms.kind === 'chase') { r.anchor = { x: r.x, y: r.y, r: 15 }; r.hdg = bearing(r, ms.prey); }
      }
      this.autoDefend();
      if (this.cfg.autoPatrol) for (const s of SIDES) this.autoPatrol(s);
    }
    // Rule: when an enemy strike jet comes close to one of our ground assets (site, base,
    // radar or SAM), the closest fighter (airborne or ready on the ground) patrols over it.
    autoPatrol(side) {
      this.patrolCd = this.patrolCd || {};
      const assets = [
        ...this.bases.filter(b => b.side === side),
        ...this.sites.filter(s => s.side === side && (s.hits || 0) < 3),
        ...this.units.filter(u => (u.kind === 'sam' || u.kind === 'radar') && u.side === side && !u.dead),
      ];
      const guarded = new Set(this.missions.filter(m => m.patrolOf && m.status !== 'done').map(m => m.patrolOf));
      for (const tr of this.tracks[side].values()) {
        const e = tr.ref;
        if (e.kind !== 'air' || !this.isAlive(e) || !e.order || e.order.type !== 'strike') continue;
        const p = this.trackPos(tr);
        let asset = null, bd = 120;
        for (const a of assets) { const d = dist(a, p); if (d < bd) { bd = d; asset = a; } }
        if (!asset || guarded.has(asset) || (this.patrolCd[asset.id || asset.callsign] || 0) > this.t) continue;
        // Closest fighter: free airborne fighters, or ready jets on the ground (take-off counts as 40 km).
        let best = null, bestD = Infinity;
        for (const u of this.units) {
          if (u.side !== side || u.state !== 'air' || u.kind !== 'air' || u.ghost || u.def.role !== 'fighter' || !this.hasAAM(u)) continue;
          // Only idle patrols can be re-tasked; never pull a jet off an intercept or escort.
          if (u.mission && u.mission.status !== 'done' && (u.mission.kind !== 'cap' || u.mission.patrolOf)) continue;
          if (u.engage || (u.order && u.order.type !== 'orbit')) continue;
          const d = dist(u, asset);
          if (d < bestD) { bestD = d; best = { unit: u }; }
        }
        if (this.airborne(side) < AIR_CAP[side]) {
          for (const b of this.bases) {
            if (b.side !== side || !this.baseOpen(b)) continue;
            for (const [ty, pool] of Object.entries(b.pools)) {
              if (AIRCRAFT[ty].role !== 'fighter' || !PRESETS[ty].AIR || pool.ready < 1) continue;
              const d = dist(b, asset) + 40;
              if (d < bestD) { bestD = d; best = { base: b.id, type: ty, n: Math.min(2, pool.ready) }; }
            }
          }
        }
        if (!best) continue;
        this.patrolCd[asset.id || asset.callsign] = this.t + 180;
        const m = { side, kind: 'cap', auto: true, patrolOf: asset, at: { x: asset.x, y: asset.y }, preset: 'AIR', repeat: false };
        const ms = best.unit ? this.assignMission(best.unit, m) : this.createMission({ ...m, base: best.base, type: best.type, n: best.n });
        if (ms.unit && ms.unit.order && ms.unit.order.type === 'orbit') ms.unit.order.r = 20;
        guarded.add(asset);
        if (side === this.player && ms.unit) this.log(side, `${ms.unit.callsign} patrolling over ${this.targetId(asset)}: enemy strike jet ${bd.toFixed(0)} km away`, 'warn');
        this.patrolStats = this.patrolStats || { IN: 0, PK: 0 };
        this.patrolStats[side]++;
      }
    }
    // A patrol over a threatened asset ends once no enemy strike jet has been near it for 2 minutes.
    checkPatrol(ms, u) {
      const a = ms.patrolOf;
      let near = false;
      for (const tr of this.tracks[ms.side].values()) {
        const e = tr.ref;
        if (e.kind === 'air' && this.isAlive(e) && e.order && e.order.type === 'strike' && dist(this.trackPos(tr), a) < 150) { near = true; break; }
      }
      if (near) { ms.quietSince = null; return; }
      ms.quietSince = ms.quietSince || this.t;
      if (this.t - ms.quietSince > 120) { ms.status = 'done'; this.orderRTB(u); }
    }
    // A chase ends when the target is gone, lost, or has fled back into its own airspace.
    checkChase(ms, u) {
      const e = ms.prey;
      const own = ms.side === this.player;
      const end = why => {
        ms.status = 'done';
        this.orderRTB(u);
        if (own) this.log(ms.side, `${ms.name}: ${why}, ${u.callsign} returning to base`, why.includes('turned') ? 'good' : 'info');
      };
      if (!this.isAlive(e)) return end('target no longer on radar');
      if (!this.trackFor(ms.side, e)) return end('lost contact with the target');
      // "Chased away": our jets got close while the target was near (or over) our side,
      // then it retreated deep into its own airspace.
      if (e.kind === 'air') {
        const home = geo.sideOf(e.x, e.y) === e.side, fd = geo.frontierDist(e.x, e.y);
        if ((!home || fd < 30) && dist(u, e) < 100) ms.wasClose = true;
        if (ms.wasClose && home && fd > 50 && dist(u, e) > 60) return end('target turned back into its own airspace');
      }
      if (!this.hasAAM(u)) end('out of missiles');
    }
    // Ready fighters at the player's bases scramble on their own when enemies come close.
    autoDefend() {
      const side = this.player;
      if (!side || !this.night || this.defending) return;
      this.defending = true;
      this.defendCd = this.defendCd || {};
      const chased = new Set(this.missions.filter(m => m.side === side && m.kind === 'chase' && m.status !== 'done').map(m => m.prey));
      for (const b of this.bases) {
        if (b.side !== side || !this.baseOpen(b) || (this.defendCd[b.id] || 0) > this.t) continue;
        const pool = Object.entries(b.pools).find(([ty, p]) => AIRCRAFT[ty].role === 'fighter' && PRESETS[ty].AIR && p.ready > 0);
        if (!pool) continue;
        let threat = null, bd = 110;
        for (const tr of this.tracks[side].values()) {
          const e = tr.ref;
          if (chased.has(e) || !this.isAlive(e) || e.ghost) continue;
          if (e.kind !== 'cruise' && e.kind !== 'air') continue;
          if (e.kind === 'air' && e.def.role === 'aew') continue;
          const p = this.trackPos(tr);
          if (geo.sideOf(p.x, p.y) !== side && geo.frontierDist(p.x, p.y) > 25) continue;
          const d = dist(b, p);
          if (d < bd) { bd = d; threat = e; }
        }
        if (!threat) continue;
        this.defendCd[b.id] = this.t + 90;
        const ms = this.createMission({ side, kind: 'chase', auto: true, base: b.id, type: pool[0], n: Math.min(2, pool[1].ready), preset: 'AIR', prey: threat });
        chased.add(threat);
        if (ms.unit) {
          const tr = this.trackFor(side, threat);
          this.log(side, `${b.id} scrambles ${ms.unit.callsign} to intercept T${tr ? tr.num : '?'} (${threat.kind === 'cruise' ? threat.w.name : threat.def.name})`, 'warn');
        }
      }
      this.defending = false;
    }
    // Escorts hold a point 20 km ahead of their strike flight, toward its target.
    steerEscort(ms, u) {
      const s = ms.escortUnit ? null : this.missions.find(x => x.id === ms.escortOf);
      const st = ms.escortUnit || (s && s.unit);
      if (!st || st.state !== 'air' || (st.order && st.order.type === 'rtb')) {
        if (u.order.type !== 'rtb') this.orderRTB(u);
        return;
      }
      const job = st.order && st.order.list && st.order.list[0];
      const ref = (job && this.refFor(job.kind, job.id)) || (s && s.target && this.refFor(s.target.kind, s.target.id)) || st;
      const h = bearing(st, ref);
      const p = { x: st.x + Math.sin(h) * 20, y: st.y - Math.cos(h) * 20 };
      u.order = { type: 'orbit', x: p.x, y: p.y, r: 10 };
      u.cap = { x: p.x, y: p.y, r: 10 };
    }
    cancelMission(ms) {
      ms.repeat = false;
      ms.status = 'done';
      if (ms.unit && ms.unit.state === 'air') this.orderRTB(ms.unit);
      this.log(ms.side, `${ms.name} cancelled`, 'info');
    }

    // ---------- replay ----------
    record() {
      const u = [];
      for (const x of this.units) {
        if (x.kind === 'air') { if (x.state === 'air') u.push([x.id, x.side, x.type, Math.round(x.x), Math.round(x.y), +x.hdg.toFixed(2), x.count, x.radarOn ? 1 : 0, x.ghost ? 1 : 0, x.callsign]); }
        else u.push([x.id, x.side, x.kind, Math.round(x.x), Math.round(x.y), 0, x.dead ? 0 : 1, x.radarOn ? 1 : 0, 0, x.callsign]);
      }
      const m = this.missiles.map(x => [Math.round(x.x * 10) / 10, Math.round(x.y * 10) / 10, x.side, x.kind === 'cruise' ? 1 : 0]);
      const fx = this.fx.filter(f => f.t > this.t - 3 && (f.kind === 'kill' || f.kind === 'impact')).map(f => [f.kind, Math.round(f.x), Math.round(f.y)]);
      const damage = this.bases.map(b => Math.round(b.damage));
      this.replay.push({ t: this.t, u, m, fx, damage, esc: Math.round(this.esc) });
    }

    // ---------- end & scoring ----------
    finish(reason, crisis) {
      if (this.over) return;
      this.over = true;
      this.crisis = !!crisis;
      this.endReason = reason;
      this.record();
      this.log('ALL', `Night ended: ${reason}`, 'info');
    }
    // Targets score by the share of their 3 hits taken.
    points(side) {
      const s = this.score[side], enemy = ENEMY[side];
      let p = 0;
      for (const o of this.objectives[side]) p += 10 * this.targetFrac(o.ref);
      for (const o of this.objectives[enemy]) p += 8 * (1 - this.targetFrac(o.ref));
      for (const r of s.otherHits) p += 6 * this.targetFrac(r);
      return Math.round(p + s.killValue + s.groundKillValue / 2 + s.intercepts * 3 + s.droneKills);
    }
    summary() {
      const out = { crisis: this.crisis };
      for (const side of SIDES) {
        const objs = this.objectives[side];
        const destroyed = objs.filter(o => this.targetFrac(o.ref) >= 1).length, damaged = objs.filter(o => o.hit && this.targetFrac(o.ref) < 1).length;
        out[side] = {
          ...this.score[side], objDestroyed: destroyed, objDamaged: damaged, objHits: destroyed + damaged,
          otherHits: this.score[side].otherHits.size, points: this.points(side),
        };
      }
      return out;
    }
    // State to carry into the next night of a campaign.
    carryOut() {
      const pools = {};
      for (const b of this.bases) {
        pools[b.id] = {};
        for (const [ty, p] of Object.entries(b.pools)) pools[b.id][ty] = p.ready + p.rearm.length;
      }
      for (const u of this.units) {
        if (u.kind !== 'air' || u.state !== 'air' || u.ghost || this.isDrone(u)) continue;
        const b = this.base(u.base);
        pools[b.id][u.type] = (pools[b.id][u.type] || 0) + u.count;
        const stock = this.stocks[u.side];
        for (const [k, c] of Object.entries(u.wpn)) if (stock[k] != null) stock[k] += c;
      }
      const damage = {};
      for (const b of this.bases) if (b.damage > 0) damage[b.id] = Math.round(b.damage * 0.4);
      // Half of destroyed air defences are repaired or replaced between nights.
      const deadStatics = this.units.filter(u => (u.kind === 'sam' || u.kind === 'radar') && u.dead && this.rand() < 0.5).map(u => u.callsign);
      return { pools, stocks: this.stocks, damage, deadStatics, esc: this.esc };
    }
  }

  Object.assign(KC, { Sim, EW, util: { clamp, angNorm, dist, bearing, vel, DEG, ENEMY } });
})(globalThis.KC);
