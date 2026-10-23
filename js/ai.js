// Side-level AI: the enemy commander. It runs one side's war from its air bases with
// a clear doctrine, in three layers, re-thought every 4 seconds:
//   1. Picture: AEW&C orbits and combat air patrols stay manned (relief flights replace
//      flights that go home).
//   2. Fighter control (GCI): every intruder in its airspace, and every strike jet
//      heading for it, gets a fighter response with numbers on its side (2 v 1, strikers first),
//      from patrols in the area or scrambled from the nearest base.
//   3. Operations: strikes fly as packages. A fighter sweep goes in first to clear the
//      corridor, then the strike jets follow with escorts. It feints, pounces when the
//      defence is thin, learns which targets are costing it aircraft, and changes phase
//      when the night turns against it (massed waves, raids on your AEW&C, a final push).
// The player gets "tells" (◆ INTEL) before the big moves, so the AI can be read and beaten.
// Individual flights are flown by Sim.aiThink.
(function (KC) {
  const { AIRCRAFT, PRESETS, WEAPONS, AIR_CAP, geo } = KC;
  const { dist, ENEMY } = KC.util;
  const P = (la, lo) => geo.project(la, lo);

  class Commander {
    // opts.oneShot: launch the AEW&C and CAPs once and hand them to the player.
    constructor(sim, side, plan, opts) {
      this.sim = sim;
      this.side = side;
      this.enemy = ENEMY[side];
      this.opts = opts || {};
      plan = plan || {};
      const live = !this.opts.oneShot;
      this.aews = (plan.aew || []).map(([base, la, lo]) => ({ base, at: P(la, lo), unit: null, launched: false }));
      this.caps = (plan.caps || []).map(([base, n, la, lo, preset]) => ({ base, n, at: P(la, lo), preset, unit: null, launched: false }));
      this.packages = live ? (plan.strikes || []).map(s => ({ ...s, list: s.list.map(x => ({ ...x })), stage: 'planned', sweepN: 2, escN: 2 })) : [];
      this.escorts = live ? (plan.escorts || []).map(s => ({ ...s, done: false })) : [];
      this.drones = live ? (plan.drones || []).map(s => ({ ...s, done: false })) : [];
      this.timer = 0;
      this.noStrikeUntil = 0;
      this.stood = false;
      this.scrambled = {};
      this.phase = 1;
      this.losses = {};          // strike flights lost, per target id
      this.lostStrikes = 0;
      this.gciCd = {};           // scramble cooldown per base
      this.sweeps = [];
      this.hvaaAt = -999;        // last raid on the enemy AEW&C
      this.generated = 0;        // packages planned on the fly
      this.feint = live && this.packages.length ? { stage: 'planned' } : null;
      // A side with no strikes on the schedule still hits back once the night is under way.
      this.counterAt = live && !this.packages.length ? (sim.duration || 3000) * (0.3 + sim.rand() * 0.1) : null;
    }

    live(u) { return u && u.state === 'air' && !(u.order && u.order.type === 'rtb'); }

    launch(baseId, n, preset, order, typeHint) {
      const sim = this.sim;
      const b = sim.base(baseId);
      if (!b) return null;
      const type = typeHint || b.home || Object.keys(b.pools)[0];
      if (!type || !b.pools[type]) return null;
      const pr = preset && PRESETS[type][preset] ? preset : Object.keys(PRESETS[type])[0];
      const u = sim.launchFlight(baseId, type, n, { order, preset: pr, ai: !this.opts.oneShot });
      return typeof u === 'string' ? null : u;
    }
    // Any base with ready fighters that can fly air-to-air, nearest to a point.
    fighterBaseNear(p, min, maxD) {
      const sim = this.sim;
      let best = null, bd = maxD || Infinity;
      for (const b of sim.bases) {
        if (b.side !== this.side || !sim.baseOpen(b)) continue;
        for (const [ty, pool] of Object.entries(b.pools)) {
          if (AIRCRAFT[ty].role !== 'fighter' || !PRESETS[ty].AIR || pool.ready < (min || 2)) continue;
          const d = dist(b, p);
          if (d < bd) { bd = d; best = { base: b.id, type: ty, ready: pool.ready, b }; }
        }
      }
      return best;
    }
    room(n) { return this.sim.airborne(this.side) + n <= AIR_CAP[this.side]; }
    // A warning to the human player about what this commander is up to.
    tell(text, kind) {
      const sim = this.sim;
      if (sim.player !== this.enemy) return;
      // One tell of a kind per minute, so a wave reads as one warning.
      this.told = this.told || {};
      if (kind && (this.told[kind] || -999) > sim.t - 60) return;
      if (kind) this.told[kind] = sim.t;
      sim.log(this.enemy, `◆ INTEL: ${text}`, 'intel');
    }
    // The player's nearest named place to a point, for tells.
    placeNear(p) {
      const sim = this.sim;
      let best = null, bd = Infinity;
      const named = [...sim.bases.filter(b => b.side === this.enemy), ...sim.sites.filter(s => s.side === this.enemy),
        ...sim.units.filter(u => (u.kind === 'sam' || u.kind === 'radar') && u.side === this.enemy)];
      for (const x of named) { const d = dist(x, p); if (d < bd) { bd = d; best = x; } }
      return best ? (best.callsign || best.id) : 'the frontier';
    }
    // A point on the line from a base to a target, `depth` km inside enemy airspace.
    corridor(from, to, depth) {
      for (let f = 0; f <= 1; f += 0.02) {
        const x = from.x + (to.x - from.x) * f, y = from.y + (to.y - from.y) * f;
        if (geo.sideOf(x, y) !== this.side && geo.frontierDist(x, y) >= depth) return { x, y };
      }
      return { x: to.x, y: to.y };
    }
    enemyFightersNear(p, R) {
      const sim = this.sim;
      let n = 0;
      for (const tr of sim.tracks[this.side].values()) {
        const e = tr.ref;
        if (e.kind !== 'air' || e.ghost || e.def.role !== 'fighter' || !sim.isAlive(e) || sim.t - tr.t > 30) continue;
        if (dist(sim.trackPos(tr), p) < R) n += e.count;
      }
      return n;
    }

    tick(dt) {
      this.timer -= dt;
      if (this.timer > 0) return;
      this.timer = 4;
      const sim = this.sim, t = sim.t;

      // AEW&C orbits
      for (const a of this.aews) {
        if (this.live(a.unit) || (this.opts.oneShot && a.launched)) continue;
        const u = this.launch(a.base, 1, 'AEW', { type: 'orbit', x: a.at.x, y: a.at.y, r: 25 });
        if (u) { a.unit = u; a.launched = true; }
      }
      // Combat air patrols
      for (const c of this.caps) {
        if (this.live(c.unit) || (this.opts.oneShot && c.launched)) continue;
        const order = { type: 'orbit', x: c.at.x, y: c.at.y, r: 15 };
        let u = this.launch(c.base, c.n, c.preset || 'AIR', order);
        if (!u && !this.opts.oneShot) {
          const alt = this.fighterBaseNear(c.at);
          if (alt) u = this.launch(alt.base, c.n, 'AIR', order, alt.type);
        }
        if (u) { c.unit = u; c.launched = true; }
      }
      if (this.opts.oneShot) return;

      // Escalation: stand down before the crisis spirals.
      if (!this.stood && sim.esc >= 88) {
        this.stood = true;
        this.noStrikeUntil = t + 300;
        sim.standDown(this.side);
      }
      this.gci();
      this.assess();
      this.runFeint();
      this.operations();
      this.hvaaRaid();
      for (const s of this.sweeps) if (this.live(s.unit) && t > s.until) sim.orderRTB(s.unit);
      this.sweeps = this.sweeps.filter(s => this.live(s.unit));

      for (const s of this.escorts) {
        if (s.done || t < s.t) continue;
        const p = P(s.at[0], s.at[1]);
        if (this.launch(s.base, s.n, s.preset, { type: 'orbit', x: p.x, y: p.y, r: 15 })) s.done = true;
      }
      for (const s of this.drones) {
        if (s.done || t < s.t) continue;
        if (t > s.t + 900) { s.done = true; continue; }
        let order;
        if (s.mode === 'sead') { const p = P(s.at[0], s.at[1]); order = { type: 'sead', x: p.x, y: p.y, r: 20 }; }
        else order = { type: 'drone', list: s.list.map(x => ({ ...x })) };
        if (this.launch(s.base, s.n, null, order)) s.done = true;
      }
    }

    // ---------- fighter control: never let an intruder go unanswered ----------
    committed(e) {
      let n = 0;
      for (const u of this.sim.units) {
        if (u.side !== this.side || u.kind !== 'air' || u.state !== 'air') continue;
        if (u.engage === e || (u.order && u.order.type === 'attack' && u.order.target === e)) n += u.count;
      }
      return n;
    }
    idleFighterNear(p, R) {
      const sim = this.sim;
      let best = null, bd = R;
      for (const u of sim.units) {
        if (u.side !== this.side || u.kind !== 'air' || u.state !== 'air' || u.ghost || u.def.role !== 'fighter') continue;
        if (u.engage || u.bingo || !u.cap || !u.order || u.order.type !== 'orbit' || !sim.hasAAM(u)) continue;
        if (u.mission && u.mission.kind === 'escort') continue;
        const d = dist(u, p);
        if (d < bd) { bd = d; best = u; }
      }
      return best;
    }
    gci() {
      const sim = this.sim, t = sim.t;
      const threats = [];
      for (const tr of sim.tracks[this.side].values()) {
        const e = tr.ref;
        if (e.kind !== 'air' || !sim.isAlive(e) || !sim.manned(e) || e.def.role === 'aew' || t - tr.t > 15) continue;
        const p = sim.trackPos(tr);
        const inside = geo.sideOf(p.x, p.y) === this.side;
        const striker = !!tr.striker;
        if (!inside && !(striker && geo.frontierDist(p.x, p.y) < 70)) continue;
        threats.push({ e, p, striker, prio: striker ? 3 : inside ? 1 + Math.min(1, geo.frontierDist(p.x, p.y) / 100) : 0.5 });
      }
      threats.sort((a, b) => b.prio - a.prio);
      for (const th of threats) {
        // Numbers: a pair (or more) for every strike flight, two of ours for each intruding fighter.
        const want = th.striker ? Math.min(4, Math.max(2, th.e.count + 1)) : Math.min(4, th.e.count * 2);
        let have = this.committed(th.e);
        while (have < want) {
          const u = this.idleFighterNear(th.p, 180);
          if (!u) break;
          u.engage = th.e;
          u.anchor = { x: u.cap.x, y: u.cap.y, r: u.cap.r };
          have += u.count;
        }
        if (have >= want || !(th.striker || have === 0) || !this.room(2)) continue;
        const fb = this.fighterBaseNear(th.p, 2, 260);
        if (!fb || (this.gciCd[fb.base] || 0) > t) continue;
        this.gciCd[fb.base] = t + 120;
        sim.createMission({ side: this.side, kind: 'chase', base: fb.base, type: fb.type, n: Math.min(want - have >= 3 ? 4 : 2, fb.ready), preset: 'AIR', prey: th.e });
      }
    }

    // ---------- phases: the commander changes its approach as the night goes ----------
    assess() {
      const sim = this.sim, t = sim.t;
      const behind = sim.points(this.side) < sim.points(this.enemy);
      if (this.phase === 1 && (this.lostStrikes >= 1 || sim.score[this.side].lost >= 4 || (t > sim.duration * 0.4 && behind))) {
        this.phase = 2;
        this.tell('enemy air activity is surging. Expect a massed wave with fighter cover.');
        // Bring the remaining packages forward into one heavy wave.
        const left = this.packages.filter(p => p.stage === 'planned').sort((a, b) => a.t - b.t);
        left.forEach((p, i) => { p.t = Math.min(p.t, t + 150 + i * 40); p.sweepN = 4; });
        if (!left.length) this.plan(t + 180, 2);
      }
      if (this.counterAt != null && t > this.counterAt) {
        this.counterAt = null;
        if (this.plan(t + 150, 2)) this.tell('enemy strike jets are being armed. Expect a counter-raid with a fighter sweep ahead of it.');
      }
      if (this.phase === 2 && t > sim.duration * 0.72) {
        this.phase = 3;
        if (this.plan(t + 150, 4)) this.tell('enemy strike aircraft are arming for a final push.');
      }
    }
    // Plan a new strike package against the night's targets (returns true if it could).
    plan(at, sweepN) {
      const sim = this.sim;
      if (this.stood || this.generated >= 2) return false;
      const objs = sim.objectives[this.side].filter(o => o.ref && sim.isAlive(o.ref) && (o.ref.hits || 0) < 3);
      if (!objs.length) return false;
      let best = null, bs = Infinity;
      for (const b of sim.bases) {
        if (b.side !== this.side || !sim.baseOpen(b) || !b.home || !PRESETS[b.home].STRIKE) continue;
        const pool = b.pools[b.home];
        if (!pool || pool.ready < 2) continue;
        for (const o of objs) {
          const s = dist(b, o.ref) + (this.losses[o.id] || 0) * 150;
          if (s < bs) { bs = s; best = { b, o }; }
        }
      }
      if (!best) return false;
      this.generated++;
      const two = objs.find(o => o !== best.o && dist(o.ref, best.o.ref) < 120) || best.o;
      this.packages.push({ t: at, base: best.b.id, n: 2, preset: 'STRIKE', list: [{ kind: best.o.kind, id: best.o.id }, { kind: two.kind, id: two.id }], stage: 'planned', sweepN, escN: 2 });
      return true;
    }

    // ---------- feint: show fighters at one target, then strike another ----------
    runFeint() {
      const f = this.feint, sim = this.sim, t = sim.t;
      if (!f || f.stage !== 'planned') return;
      const real = this.packages.find(p => p.stage === 'planned');
      if (!real) { f.stage = 'done'; return; }
      if (t < real.t - 330) return;
      f.stage = 'done';
      if (sim.rand() < 0.4 || !this.room(2)) return;
      const tgt = sim.refFor(real.list[0].kind, real.list[0].id);
      const other = sim.objectives[this.side].filter(o => o.ref && tgt).sort((a, b) => dist(b.ref, tgt) - dist(a.ref, tgt))[0];
      if (!other || dist(other.ref, tgt) < 120) return;
      const fb = this.fighterBaseNear(other.ref);
      if (!fb) return;
      const at = this.corridor(fb.b, other.ref, 15);
      const u = this.launch(fb.base, 2, 'AIR', { type: 'orbit', x: at.x, y: at.y, r: 20 }, fb.type);
      if (!u) return;
      u.offensive = true;
      this.sweeps.push({ unit: u, until: t + 600 });
      this.tell(`enemy fighters are massing toward ${this.placeNear(at)}.`);
    }

    // ---------- strike packages: sweep, then strike with escorts ----------
    retarget(list, base) {
      const sim = this.sim;
      const ok = x => {
        const r = sim.refFor(x.kind, x.id);
        return r && sim.isAlive(r) && (r.hits || 0) < 3 && (this.losses[x.id] || 0) < 2;
      };
      const alts = sim.objectives[this.side].filter(o => ok(o)).sort((a, b) =>
        ((this.losses[a.id] || 0) - (this.losses[b.id] || 0)) || (dist(base, a.ref) - dist(base, b.ref)));
      return list.map(x => (ok(x) || !alts.length ? x : { kind: alts[0].kind, id: alts[0].id }));
    }
    operations() {
      const sim = this.sim, t = sim.t;
      for (const pk of this.packages) {
        if (pk.stage === 'done') continue;
        if (pk.stage === 'inbound') {
          const u = pk.unit;
          if (u.state === 'dead') {
            pk.stage = 'done';
            this.lostStrikes++;
            for (const x of pk.list) this.losses[x.id] = (this.losses[x.id] || 0) + 1;
          } else if (u.state !== 'air' || !u.order || u.order.type !== 'strike') pk.stage = 'done';
          continue;
        }
        if (t < this.noStrikeUntil || this.stood) continue;
        if (t > pk.t + 900) { pk.stage = 'done'; continue; }
        const b = sim.base(pk.base);
        if (!b || !b.home) { pk.stage = 'done'; continue; }
        const tgt = sim.refFor(pk.list[0].kind, pk.list[0].id);
        if (pk.stage === 'planned') {
          if (t < pk.t - 120) {
            // Pounce: if the defence around the target is thin right now, go early.
            if (t > 120 && pk.t - t < 420 && tgt && this.enemyFightersNear(tgt, 170) === 0) pk.t = t + 120;
            else continue;
          }
          pk.list = this.retarget(pk.list, b);
          const aim = sim.refFor(pk.list[0].kind, pk.list[0].id) || tgt;
          // Escorts and sweep size grow with what this target has already cost us.
          const lost = Math.max(...pk.list.map(x => this.losses[x.id] || 0));
          if (lost) { pk.sweepN = 4; pk.escN = 2; }
          const fb = this.fighterBaseNear(b);
          if (fb && aim && this.room(pk.sweepN + pk.n + pk.escN)) {
            const at = this.corridor(b, aim, 30);
            const u = this.launch(fb.base, pk.sweepN, 'AIR', { type: 'orbit', x: at.x, y: at.y, r: 25 }, fb.type);
            if (u) {
              u.offensive = true;
              this.sweeps.push({ unit: u, until: t + 900 });
              this.tell(`enemy fighter sweep (${1 + (u.wing || []).length} ${u.def.name}) heading for ${this.placeNear(at)}. A strike usually follows.`, 'sweep');
            }
          }
          pk.stage = 'sweep';
          continue;
        }
        if (pk.stage === 'sweep' && t >= pk.t) {
          const type = b.home;
          const per = PRESETS[type][pk.preset] || {};
          const cruise = Object.entries(per).filter(([k]) => WEAPONS[k].kind === 'cruise').reduce((a, [, c]) => a + c, 0) * pk.n;
          const each = Math.max(1, Math.floor(cruise / pk.list.length));
          const u = this.launch(pk.base, pk.n, pk.preset, { type: 'strike', list: pk.list.map(x => ({ ...x, n: each })) });
          if (!u) continue;
          pk.stage = 'inbound';
          pk.unit = u;
          // Either a low-level run under the radar while the sweep draws the defence's eyes
          // (no escort: it would give the strikers away), or a high run with an escort.
          if (sim.rand() < (this.phase >= 2 ? 0.7 : 0.45)) {
            this.goLow(u);
            this.tell('enemy strike jets may be coming in at low level, under your radar. Only your fighters will see them.', 'low');
          } else {
            const eb = this.fighterBaseNear(b);
            if (eb) sim.createMission({ side: this.side, kind: 'escort', base: eb.base, type: eb.type, n: Math.min(pk.escN, eb.ready), preset: 'AIR', escortUnit: u });
          }
        }
      }
    }

    // ---------- high-value raid: go for the enemy AEW&C when it comes close ----------
    hvaaRaid() {
      const sim = this.sim, t = sim.t;
      if (t - this.hvaaAt < 900 || t < 300 || !this.room(2)) return;
      for (const tr of sim.tracks[this.side].values()) {
        const e = tr.ref;
        if (e.kind !== 'air' || e.def.role !== 'aew' || !sim.isAlive(e) || t - tr.t > 10) continue;
        const p = sim.trackPos(tr);
        if (geo.frontierDist(p.x, p.y) > 140 || this.committed(e)) continue;
        // Long-range missiles only: this is a dash into defended airspace.
        let best = null, bd = 330;
        for (const b of sim.bases) {
          if (b.side !== this.side || !sim.baseOpen(b)) continue;
          for (const [ty, pool] of Object.entries(b.pools)) {
            const air = PRESETS[ty].AIR;
            if (AIRCRAFT[ty].role !== 'fighter' || !air || pool.ready < 2) continue;
            if (!Object.keys(air).some(k => WEAPONS[k].range >= 100)) continue;
            const d = dist(b, p);
            if (d < bd) { bd = d; best = { base: b.id, type: ty }; }
          }
        }
        if (!best) return;
        this.hvaaAt = t;
        const ms = sim.createMission({ side: this.side, kind: 'chase', base: best.base, type: best.type, n: 2, preset: 'AIR', prey: e });
        if (ms.unit) {
          ms.unit.offensive = true;
          this.goLow(ms.unit);
          this.tell(`enemy fighters are pushing toward your ${e.def.name}. Cover it or pull it back.`);
        }
        return;
      }
    }

    // Terrain hugging, radar off, for a flight (lead and wingmen).
    goLow(u) {
      for (const x of [u, ...(u.wing || [])]) { x.radarMode = 'low'; x.low = true; x.radarOn = false; }
    }

    // Called by the sim when enemy weapons are heading for one of our bases.
    baseThreat(b) {
      const t = this.sim.t;
      if ((this.scrambled[b.id] || -999) > t - 300) return;
      this.scrambled[b.id] = t;
      this.sim.scrambleAll(b, true);
    }
  }

  KC.Commander = Commander;
})(globalThis.KC);
