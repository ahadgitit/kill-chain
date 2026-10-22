// Simulation core: sensors, data-link picture, missiles, flight behaviour and AI.
// No DOM access here so it can be stepped headless (see tools/headless.js).
(function (KC) {
  const { AIRCRAFT, WEAPONS, SAMS, LOADOUTS, BASES, SITES, V_MIN, geo } = KC;
  const DEG = Math.PI / 180;
  const ENEMY = { IN: 'PK', PK: 'IN' };
  const SIDES = ['IN', 'PK'];
  const GIMBAL = 60 * DEG;
  const AI_FIRE = 0.75; // fraction of max launch range used by automatic shots

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
    constructor(scn, player, seed) {
      this.scn = scn;
      this.player = player;           // 'IN', 'PK' or null (AI vs AI)
      this.rand = mulberry32(seed == null ? Date.now() : seed);
      this.t = 0;
      this.duration = scn.duration;
      this.nextId = 1;
      this.units = [];
      this.missiles = [];
      this.events = [];
      this.fx = [];
      this.over = false;
      this.sweepTimer = 0;
      this.trackNo = { IN: 100, PK: 100 };
      this.tracks = { IN: new Map(), PK: new Map() };
      this.score = {};
      for (const s of SIDES) this.score[s] = { kills: 0, killValue: 0, lost: 0, hits: 0, intercepts: 0, shots: 0 };
      this.bases = BASES.map(b => ({ ...b, kind: 'base' }));
      this.sites = SITES.map(s => ({ ...s, kind: 'site', hits: 0 }));

      for (const d of scn.flights) this.addFlight(d, true);
      for (const d of scn.reserves) this.addFlight(d, false);
      for (const d of scn.sams) this.addStatic('sam', d);
      for (const d of scn.radars) this.addStatic('radar', d);
      this.sweep();
      this.log('ALL', `${scn.name}: simulation started`, 'info');
    }

    // ---------- setup ----------
    addFlight(d, airborne) {
      const def = AIRCRAFT[d.type];
      const base = this.base(d.base);
      const u = {
        id: this.nextId++, kind: 'air', side: d.side, callsign: d.callsign, type: d.type, def,
        count: d.count, loadoutKey: d.loadout, wpn: { ...LOADOUTS[d.loadout] }, base: d.base,
        x: airborne ? d.pos.x : base.x, y: airborne ? d.pos.y : base.y,
        hdg: d.side === 'IN' ? -90 * DEG : 90 * DEG, spd: def.speed,
        state: airborne ? 'air' : 'ground', readyAt: 0,
        order: airborne ? this.cloneOrder(d.order) : null,
        roe: 'free', radarOn: def.role === 'aew' || !def.linked,
        fuel: def.role === 'aew' ? 20000 : 6500, lastShot: -99, lastLaunch: -99,
        threats: [], defending: false, seen: {}, ai: d.side !== this.player,
        engage: null, anchor: null, cap: null, bingo: false,
      };
      if (u.order && u.order.type === 'orbit') u.cap = { x: u.order.x, y: u.order.y, r: u.order.r };
      if (u.order && u.order.type === 'strike') {
        const s = this.site(u.order.list[0].site);
        u.hdg = bearing(u, s);
      }
      this.units.push(u);
      return u;
    }
    addStatic(kind, d) {
      const u = {
        id: this.nextId++, kind, side: d.side, callsign: d.callsign, type: d.type,
        x: d.pos.x, y: d.pos.y, hdg: 0, spd: 0, linked: true, seen: {}, lastShot: -99,
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

    log(side, text, cls) {
      this.events.push({ t: this.t, side, text, cls: cls || 'info' });
      if (this.events.length > 400) this.events.shift();
    }
    clock(t) {
      const s = this.scn.start;
      const tot = s.h * 3600 + s.m * 60 + Math.floor(t == null ? this.t : t);
      const h = Math.floor(tot / 3600) % 24, m = Math.floor(tot / 60) % 60, sec = tot % 60;
      return `${String(h).padStart(2, '0')}${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}Z`;
    }

    // ---------- queries ----------
    isAlive(e) {
      if (!e) return false;
      if (e.kind === 'air') return e.state === 'air';
      if (e.kind === 'cruise') return !e.dead;
      return true;
    }
    isLinked(u) { return u.kind !== 'air' || u.def.linked; }
    emitting(u) {
      if (u.kind === 'air') return u.state === 'air' && (u.radarOn || u.def.role === 'aew');
      return u.kind === 'sam' || u.kind === 'radar';
    }
    sensorRange(u) {
      if (u.kind === 'air') return this.emitting(u) ? u.def.radar : 0;
      return u.range;
    }
    targetsOf(side) {
      const out = [];
      for (const u of this.units) if (u.side === side && u.kind === 'air' && u.state === 'air') out.push(u);
      for (const m of this.missiles) if (m.side === side && m.kind === 'cruise' && !m.dead) out.push(m);
      return out;
    }
    detects(s, t) {
      const R0 = this.sensorRange(s);
      if (!R0) return false;
      const rcs = t.kind === 'air' ? t.def.rcs : t.w.rcs;
      let f = Math.pow(rcs / 5, 0.25);
      if (t.kind === 'cruise' && t.w.low) f *= s.kind === 'air' ? (s.def.role === 'aew' ? 0.7 : 0.55) : 0.35;
      if (t.kind === 'air') f *= 1 - t.def.ew * 0.3;
      const d = dist(s, t);
      if (d > R0 * f) return false;
      if (s.kind === 'air' && s.def.role === 'fighter') {
        if (Math.abs(angNorm(bearing(s, t) - s.hdg)) > GIMBAL) return false;
      }
      return true;
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
      for (const k in u.wpn) if (u.wpn[k] && WEAPONS[k].kind === 'cruise') return k;
      return null;
    }
    hasAEW(side) {
      return this.units.some(u => u.side === side && u.kind === 'air' && u.state === 'air' && u.def.role === 'aew');
    }
    trackFor(side, e) { return this.tracks[side].get(e.id); }

    // ---------- sensors & picture ----------
    sweep() {
      const statics = this.units.filter(u => u.kind === 'sam' || u.kind === 'radar');
      for (const side of SIDES) {
        const enemy = ENEMY[side];
        const sensors = this.units.filter(u => u.side === side && (u.kind !== 'air' || (u.state === 'air' && this.emitting(u))));
        const table = this.tracks[side];
        for (const t of this.targetsOf(enemy)) {
          const by = [];
          let net = false;
          for (const s of sensors) {
            if (this.detects(s, t)) { by.push(s.id); if (this.isLinked(s)) net = true; }
          }
          t.seen[side] = { any: by.length > 0, net, by };
          if (by.length) this.updateTrack(side, t, 'radar');
        }
        // Passive detection (RWR / ESM) of enemy emitters.
        const listeners = this.units.filter(u => u.side === side && (u.kind !== 'air' || u.state === 'air'));
        for (const e of this.units) {
          if (e.side !== enemy || !this.emitting(e)) continue;
          const tr = table.get(e.id);
          if (tr && tr.q === 'radar' && this.t - tr.t < 2) continue;
          const R = Math.min(450, this.sensorRange(e) * 1.25);
          if (listeners.some(l => dist(l, e) <= R)) this.updateTrack(side, e, 'esm');
        }
        for (const [id, tr] of table) {
          if (tr.static) continue;
          if (!this.isAlive(tr.ref) || this.t - tr.t > 45) table.delete(id);
        }
      }
      void statics;
    }
    updateTrack(side, e, q) {
      const table = this.tracks[side];
      let tr = table.get(e.id);
      if (!tr) {
        tr = { id: e.id, ref: e, num: ++this.trackNo[side], first: this.t, kind: e.kind, static: e.kind === 'sam' || e.kind === 'radar' };
        table.set(e.id, tr);
        if (e.kind === 'air' && side === this.player) {
          this.log(side, `New track T${tr.num}: ${e.def.name}${e.count > 1 ? ' x' + e.count : ''} (${q === 'esm' ? 'passive' : 'radar'})`, 'info');
        }
      }
      if (q === 'esm' && tr.q === 'radar' && this.t - tr.t < 2) return;
      const err = q === 'esm' ? 6 : 0;
      tr.q = q;
      tr.t = this.t;
      tr.x = e.x + (this.rand() - 0.5) * err;
      tr.y = e.y + (this.rand() - 0.5) * err;
      const v = vel(e);
      tr.vx = q === 'esm' ? 0 : v.x;
      tr.vy = q === 'esm' ? 0 : v.y;
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
      for (const u of this.units) {
        if (u.kind === 'air') {
          if (u.state === 'air') this.updateFlight(u, dt);
          else if (u.state === 'ground' && u.readyAt && this.t >= u.readyAt) {
            u.readyAt = 0;
            this.log(u.side, `${u.callsign} re-armed and ready at ${u.base}`, 'info');
          }
        } else if (u.kind === 'sam') this.updateSam(u);
      }
      for (const m of this.missiles) {
        if (m.kind === 'cruise') this.updateCruise(m, dt); else this.updateMissile(m, dt);
      }
      this.missiles = this.missiles.filter(m => !m.dead);
      this.fx = this.fx.filter(f => this.t - f.t < 12);
      if (this.t >= this.duration) this.finish('Time limit reached');
    }

    // ---------- flights ----------
    updateFlight(u, dt) {
      const def = u.def;
      // Threat assessment.
      u.threats = u.threats.filter(m => !m.dead && m.target === u);
      u.defending = u.threats.some(m => m.lock || (m.warned && dist(m, u) < 40));

      // Fuel.
      u.fuel -= dt * (u.spd > def.speed * 1.1 ? 2.5 : 1);
      if (!u.bingo && u.order && u.order.type !== 'rtb') {
        const b = this.base(u.base);
        if (u.fuel < dist(u, b) / def.speed + 900) {
          u.bingo = true;
          this.log(u.side, `${u.callsign} BINGO fuel, returning to ${u.base}`, 'warn');
          this.orderRTB(u);
        }
      }
      if (u.fuel <= 0) { this.lose(u, u.count, 'ran out of fuel'); return; }

      if (u.ai) this.aiThink(u);
      else if (u.order && u.order.type === 'attack' && !this.fireQuality(u, u.order.target) && !u.radarOn &&
               this.isAlive(u.order.target) && dist(u, u.order.target) < def.radar) {
        u.radarOn = true;
        this.log(u.side, `${u.callsign} radar ON: no data-link track, using own radar for a firing solution`, 'info');
      }

      let want = u.hdg, spd = def.speed;
      if (u.defending) {
        const m = u.threats.reduce((a, b) => (dist(a, u) < dist(b, u) ? a : b));
        want = bearing(m, u);
        spd = def.dash;
        if (!u.chaffAt || this.t - u.chaffAt > 8) {
          if (dist(m, u) < 12) { u.chaffAt = this.t; this.fx.push({ kind: 'chaff', x: u.x, y: u.y, t: this.t }); }
        }
      } else {
        const r = this.steer(u);
        want = r.hdg; spd = r.spd;
      }
      if (u.state !== 'air') return; // landed during steer

      const maxTurn = def.turn * DEG * dt * (u.defending ? 1.3 : 1);
      u.hdg = angNorm(u.hdg + clamp(angNorm(want - u.hdg), -maxTurn, maxTurn));
      u.spd += clamp(spd - u.spd, -0.01 * dt, 0.01 * dt);
      u.x += Math.sin(u.hdg) * u.spd * dt;
      u.y -= Math.cos(u.hdg) * u.spd * dt;

      if (!u.defending && u.roe === 'free' && def.role === 'fighter') this.autoEngage(u);
    }

    steer(u) {
      const def = u.def, o = u.order;
      const tgt = u.engage || (o && o.type === 'attack' ? o.target : null);
      if (tgt) return this.steerAttack(u, tgt);
      if (!o) return this.orbitAt(u, { x: u.x, y: u.y, r: 15 });
      if (o.type === 'orbit') return this.orbitAt(u, o);
      if (o.type === 'rtb') {
        const b = this.base(u.base);
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
    // AI flights stay inside their own airspace (no deliberate border crossing).
    leash(u, h) {
      if (!u.ai) return h;
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
      if (!o.list.length) { this.strikeDone(u); return { hdg: u.hdg, spd: def.speed }; }
      const job = o.list[0];
      const site = this.site(job.site);
      const wk = this.strikeWeapon(u);
      if (!wk) { o.list.length = 0; this.strikeDone(u); return { hdg: u.hdg, spd: def.speed }; }
      const w = WEAPONS[wk];
      const d = dist(u, site);
      if (d <= w.range * 0.9) {
        if (this.t - u.lastLaunch > 2) {
          this.launchCruise(u, wk, site);
          u.lastLaunch = this.t;
          job.n--;
          if (job.n <= 0 || !u.wpn[wk]) o.list.shift();
          if (!o.list.length) this.strikeDone(u);
        }
        return { hdg: bearing(u, site), spd: def.speed };
      }
      return { hdg: bearing(u, site), spd: def.speed };
    }
    strikeDone(u) {
      this.log(u.side, `${u.callsign} weapons released, egressing`, 'info');
      if (u.ai) this.orderRTB(u);
      else u.order = { type: 'orbit', x: u.x + Math.sin(u.hdg + Math.PI) * 40, y: u.y - Math.cos(u.hdg + Math.PI) * 40, r: 15 };
    }
    orderRTB(u) {
      u.order = { type: 'rtb' };
      u.engage = null;
    }
    land(u, b) {
      u.state = 'ground';
      u.x = b.x; u.y = b.y;
      u.readyAt = this.t + 600;
      u.order = null; u.engage = null; u.threats = []; u.defending = false;
      u.wpn = { ...LOADOUTS[u.loadoutKey] };
      u.fuel = u.def.role === 'aew' ? 20000 : 9000;
      u.bingo = false;
      this.log(u.side, `${u.callsign} landed at ${b.id}, re-arming`, 'info');
      if (u.ai && u.cap) this.aiReplace(u);
    }

    autoEngage(u) {
      if (this.t - u.lastShot < 3) return;
      const enemies = this.targetsOf(ENEMY[u.side]);
      let best = null, bestScore = -1;
      for (const t of enemies) {
        if (!this.fireQuality(u, t)) continue;
        if (t.kind === 'cruise' && u.order && u.order.type === 'strike') continue;
        const want = t.kind === 'air' ? t.count : 1;
        if (this.inFlightAt(u.side, t) >= want) continue;
        const k = this.chooseWeapon(u, t, AI_FIRE);
        if (!k || !this.canGuide(u, t, k)) continue;
        const off = Math.abs(angNorm(bearing(u, t) - u.hdg));
        if (off > (WEAPONS[k].kind === 'srm' ? 90 : 60) * DEG) continue;
        const prio = t.kind === 'cruise' ? 0.5 : t.def.role === 'aew' ? 3 : this.strikeWeapon(t) ? 2 : 1;
        const sc = prio * 1000 - dist(u, t);
        if (sc > bestScore) { bestScore = sc; best = t; }
      }
      if (best) this.tryFire(u, best, AI_FIRE);
    }

    tryFire(u, t, factor) {
      if (this.t - u.lastShot < 3) return false;
      if (!this.fireQuality(u, t)) return false;
      const want = t.kind === 'air' ? (t.def.role === 'aew' ? 2 : t.count) : 1;
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
      return true;
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
      this.log(u.side, `${u.callsign} ${call} ${w.name} at ${tname}, ${dist(u, t).toFixed(0)} km${how}`, 'shot');
      // A radar-guided launch from an emitting shooter tripping the target's RWR.
      if (t.kind === 'air' && (u.kind === 'sam' || (u.radarOn && this.ownTrack(u, t)))) {
        m.warned = true;
        this.warn(t, m, 'LAUNCH WARNING');
      }
    }

    warn(t, m, what) {
      if (t.kind !== 'air') return;
      if (!t.threats.includes(m)) t.threats.push(m);
      this.log(t.side, `${t.callsign}: ${what}, ${m.w.name} ${dist(m, t).toFixed(0)} km`, 'warn');
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
        if (w.kind === 'aam' && this.isAlive(m.shooter)) this.log(m.side, `${m.shooter.callsign} ${w.name} PITBULL (seeker active)`, 'info');
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
        if (alive && m.lock) this.log(t.side, `${t.callsign} defeated a ${w.name} (out of energy)`, 'good');
        else if (alive) this.log(m.side, `${w.name} from ${m.shooter.callsign} lost its target`, 'info');
      }
    }
    trySeeker(m) {
      const t = m.target;
      const d = dist(m, t);
      const off = Math.abs(angNorm(bearing(m, t) - m.hdg));
      if (d <= m.w.seeker * 1.3 && off < 40 * DEG) {
        m.lock = true;
        if (t.kind === 'air') this.warn(t, m, m.warned ? 'MISSILE ACTIVE' : 'MISSILE WARNING (no prior launch warning!)');
        m.warned = true;
      }
    }
    resolve(m) {
      const t = m.target, w = m.w;
      m.dead = true;
      let pk = w.pk * clamp((m.spd - V_MIN) / (w.vMax * 0.5), 0.25, 1);
      if (t.kind === 'air') {
        pk *= 1 - t.def.ew * 0.4;
        if (t.defending) pk *= t.def.role === 'aew' ? 0.9 : 0.7;
      } else pk *= 0.6;
      if (this.rand() < pk) {
        this.fx.push({ kind: 'kill', x: t.x, y: t.y, t: this.t });
        if (t.kind === 'air') {
          this.score[m.side].kills++;
          this.score[m.side].killValue += t.def.value;
          this.log(m.side, `SPLASH ONE ${t.def.name} (${w.name} from ${m.shooter.callsign})`, 'kill');
          this.lose(t, 1, `shot down by ${w.name}`);
        } else {
          t.dead = true;
          this.score[m.side].intercepts++;
          this.log(m.side, `${t.w.name} intercepted by ${w.name}`, 'kill');
          this.log(t.side, `A ${t.w.name} was shot down`, 'warn');
        }
      } else {
        this.fx.push({ kind: 'miss', x: t.x, y: t.y, t: this.t });
        this.log(m.side, `${w.name} from ${m.shooter.callsign} missed`, 'info');
        if (t.kind === 'air') this.log(t.side, `${t.callsign} evaded a ${w.name}`, 'good');
      }
    }
    lose(u, n, why) {
      u.count -= n;
      this.score[u.side].lost += n;
      this.log(u.side, `${u.callsign}: ${u.def.name} LOST, ${why}`, 'loss');
      if (u.count <= 0) {
        u.state = 'dead';
        u.threats = [];
        if (u.ai && u.cap) this.aiReplace(u);
      }
    }

    launchCruise(u, k, site) {
      const w = WEAPONS[k];
      u.wpn[k]--;
      const m = {
        id: this.nextId++, kind: 'cruise', side: u.side, wkey: k, w, x: u.x, y: u.y,
        hdg: bearing(u, site), spd: w.speed, t: 0, site, shooter: u, count: 1, seen: {}, trail: [], trailT: 0,
      };
      this.missiles.push(m);
      this.log(u.side, `${u.callsign} launched ${w.name} at ${site.id} (${dist(u, site).toFixed(0)} km)`, 'shot');
    }
    updateCruise(m, dt) {
      m.t += dt;
      const want = bearing(m, m.site);
      m.hdg = angNorm(m.hdg + clamp(angNorm(want - m.hdg), -5 * DEG * dt, 5 * DEG * dt));
      const px = m.x, py = m.y;
      m.x += Math.sin(m.hdg) * m.spd * dt;
      m.y -= Math.cos(m.hdg) * m.spd * dt;
      m.trailT -= dt;
      if (m.trailT <= 0) { m.trail.push({ x: px, y: py }); if (m.trail.length > 24) m.trail.shift(); m.trailT = 4; }
      if (dist(m, m.site) < m.spd * dt + 0.3) {
        m.dead = true;
        if (this.rand() < 0.9) {
          m.site.hits++;
          this.score[m.side].hits++;
          this.fx.push({ kind: 'impact', x: m.site.x, y: m.site.y, t: this.t });
          this.log('ALL', `IMPACT: ${m.w.name} hit ${m.site.id}`, 'impact');
        } else this.log(m.side, `${m.w.name} failed to hit ${m.site.id}`, 'info');
      }
      if (m.t > m.w.range / m.w.speed * 1.2) m.dead = true;
    }

    // ---------- SAMs ----------
    updateSam(u) {
      if (u.ammo <= 0 || this.t - u.lastShot < 4) return;
      let best = null, bestScore = -Infinity;
      for (const t of this.targetsOf(ENEMY[u.side])) {
        if (!this.fireQuality(u, t)) continue;
        if (!this.chooseWeapon(u, t, AI_FIRE)) continue;
        const sc = (t.kind === 'air' ? 1000 : 0) - dist(u, t);
        if (sc > bestScore) { bestScore = sc; best = t; }
      }
      if (best) this.tryFire(u, best, AI_FIRE);
    }

    // ---------- AI ----------
    aiThink(u) {
      const def = u.def;
      if (def.role === 'aew') return;
      // Radar discipline: linked fighters stay silent while an AEW&C builds the picture.
      const aew = this.hasAEW(u.side);
      const tgt = u.engage || (u.order && u.order.type === 'attack' ? u.order.target : null);
      const near = tgt && this.isAlive(tgt) && dist(u, tgt) < def.radar;
      const guiding = this.missiles.some(m => m.shooter === u && !m.lock && !m.w.tp && m.kind !== 'cruise');
      if (def.linked) u.radarOn = !aew || guiding || (near && (!this.fireQuality(u, tgt) || !this.tpOnly(u)));
      else u.radarOn = !!tgt || this.nearestEnemy(u, 150) != null;

      if (!u.order || u.order.type !== 'orbit' || !u.cap) return;
      if (u.engage && !this.isAlive(u.engage)) u.engage = null;
      if (u.engage && dist(u, u.cap) > 140) u.engage = null;
      if (!u.engage && this.hasAAM(u)) {
        const commit = Math.max(60, this.bestRange(u) * 1.3);
        const e = this.nearestEnemy(u, commit, true);
        if (e) { u.engage = e; u.anchor = { x: u.cap.x, y: u.cap.y, r: u.cap.r }; }
      }
      if (!this.hasAAM(u)) { u.engage = null; this.orderRTB(u); }
    }
    nearestEnemy(u, range, airOnly) {
      let best = null, bd = range;
      for (const tr of this.tracks[u.side].values()) {
        const e = tr.ref;
        if (!this.isAlive(e) || (airOnly && e.kind !== 'air')) continue;
        if (e.kind !== 'air' && e.kind !== 'cruise') continue;
        const d = dist(u, this.trackPos(tr));
        if (d < bd) { bd = d; best = e; }
      }
      return best;
    }
    aiReplace(gone) {
      const cap = gone.cap;
      gone.cap = null;
      const ready = this.units.filter(r => r.side === gone.side && r.kind === 'air' && r.state === 'ground' && !r.readyAt && r.count > 0 && r.def.role === 'fighter');
      if (!ready.length) return;
      ready.sort((a, b) => dist(this.base(a.base), cap) - dist(this.base(b.base), cap));
      this.scramble(ready[0], { type: 'orbit', x: cap.x, y: cap.y, r: cap.r || 15 });
    }

    // ---------- player commands ----------
    scramble(u, order) {
      const b = this.base(u.base);
      u.state = 'air';
      u.x = b.x; u.y = b.y;
      u.spd = u.def.speed;
      u.order = order || { type: 'orbit', x: b.x, y: b.y, r: 15 };
      u.hdg = order ? bearing(b, order) : u.hdg;
      u.cap = u.order.type === 'orbit' ? { x: u.order.x, y: u.order.y, r: u.order.r } : null;
      u.anchor = null; u.engage = null;
      this.log(u.side, `${u.callsign} (${u.def.name} x${u.count}) scrambled from ${u.base}`, 'info');
    }
    cmdMove(u, x, y) {
      u.order = { type: 'orbit', x, y, r: 15 };
      u.engage = null; u.anchor = null;
      if (u.ai) u.cap = { x, y, r: 15 };
    }
    cmdAttack(u, t) {
      u.anchor = { x: u.x, y: u.y, r: 15 };
      u.order = { type: 'attack', target: t };
      u.engage = null;
    }
    cmdStrike(u, site) {
      const wk = this.strikeWeapon(u);
      if (!wk) return false;
      u.order = { type: 'strike', list: [{ site: site.id, n: Math.min(2, u.wpn[wk]) }] };
      u.engage = null;
      return true;
    }
    cmdRTB(u) { this.orderRTB(u); }

    finish(reason) {
      if (this.over) return;
      this.over = true;
      this.endReason = reason;
      this.log('ALL', `Simulation ended: ${reason}`, 'info');
    }
    summary() {
      const s = this.score;
      const sitesHit = this.sites.filter(x => x.hits > 0).length;
      return {
        IN: { ...s.IN, points: s.IN.hits * 8 + s.IN.killValue, sitesHit },
        PK: { ...s.PK, points: s.PK.intercepts * 3 + s.PK.killValue },
        sites: this.sites.map(x => ({ id: x.id, hits: x.hits })),
      };
    }
  }

  Object.assign(KC, { Sim, util: { clamp, angNorm, dist, bearing, vel, DEG, ENEMY } });
})(globalThis.KC);
