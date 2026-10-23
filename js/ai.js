// Side-level AI: runs one side's war from its air bases. It keeps AEW&C orbits and
// combat air patrols manned (replacing flights that go home), launches the night's
// strike packages and drone waves on schedule, uses EW, protects threatened bases and
// stands down if escalation gets dangerous. Individual flights are flown by Sim.aiThink.
(function (KC) {
  const { AIRCRAFT, PRESETS, geo } = KC;
  const { dist } = KC.util;
  const P = (la, lo) => geo.project(la, lo);

  class Commander {
    // opts.oneShot: launch the AEW&C and CAPs once and hand them to the player.
    constructor(sim, side, plan, opts) {
      this.sim = sim;
      this.side = side;
      this.opts = opts || {};
      plan = plan || {};
      this.aews = (plan.aew || []).map(([base, la, lo]) => ({ base, at: P(la, lo), unit: null, launched: false }));
      this.caps = (plan.caps || []).map(([base, n, la, lo, preset]) => ({ base, n, at: P(la, lo), preset, unit: null, launched: false }));
      this.strikes = this.opts.oneShot ? [] : (plan.strikes || []).map(s => ({ ...s, done: false }));
      this.escorts = this.opts.oneShot ? [] : (plan.escorts || []).map(s => ({ ...s, done: false }));
      this.drones = this.opts.oneShot ? [] : (plan.drones || []).map(s => ({ ...s, done: false }));
      this.timer = 0;
      this.lastStrikeAt = -999;
      this.lastStrikeTarget = null;
      this.noStrikeUntil = 0;
      this.stood = false;
      this.scrambled = {};
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
    fighterBaseNear(p) {
      const sim = this.sim;
      let best = null, bd = Infinity;
      for (const b of sim.bases) {
        if (b.side !== this.side || !sim.baseOpen(b)) continue;
        for (const [ty, pool] of Object.entries(b.pools)) {
          if (AIRCRAFT[ty].role !== 'fighter' || !PRESETS[ty].AIR || pool.ready < 2) continue;
          const d = dist(b, p);
          if (d < bd) { bd = d; best = { base: b.id, type: ty }; }
        }
      }
      return best;
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
      // Strike packages
      for (const s of this.strikes) {
        if (s.done || t < s.t || t < this.noStrikeUntil) continue;
        if (t > s.t + 900) { s.done = true; continue; }
        const b = sim.base(s.base);
        const type = b && b.home;
        if (!type) { s.done = true; continue; }
        const per = PRESETS[type][s.preset] || {};
        const cruise = Object.entries(per).filter(([k]) => KC.WEAPONS[k].kind === 'cruise').reduce((a, [, c]) => a + c, 0) * s.n;
        const each = Math.max(1, Math.floor(cruise / s.list.length));
        const order = { type: 'strike', list: s.list.map(x => ({ ...x, n: each })) };
        const u = this.launch(s.base, s.n, s.preset, order);
        if (u) {
          s.done = true;
          this.lastStrikeAt = t;
          this.lastStrikeTarget = sim.refFor(s.list[0].kind, s.list[0].id);
          // Strike jets carry no air-to-air missiles, so give them an escort.
          const eb = this.fighterBaseNear(b);
          if (eb) sim.createMission({ side: this.side, kind: 'escort', base: eb.base, type: eb.type, n: 2, preset: 'AIR', escortUnit: u });
        }
      }
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
