// Canvas renderer: vector map, units, tracks, missiles and effects.
(function (KC) {
  const { geo, WEAPONS } = KC;
  const { DEG } = KC.util;

  const COL = {
    bg: '#030608', grid: '#0a1820', river: '#0e2e44', coast: '#1d6f8e', other: '#1a3a4a',
    border: '#46d2ff', loc: '#46d2ff', city: '#3d5966', text: '#9fc4d3', dim: '#4b6b78',
    IN: '#ff9a3c', PK: '#33e58c', site: '#ff4d6d', white: '#e8f6ff',
  };
  KC.COL = COL;

  class Renderer {
    constructor(canvas) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.view = { cx: 0, cy: 0, scale: 1 };
      this.w = 0; this.h = 0; this.dpr = 1;
      this.showSensors = true;
    }
    resize() {
      const r = this.canvas.getBoundingClientRect();
      this.dpr = Math.min(2, window.devicePixelRatio || 1);
      this.w = r.width; this.h = r.height;
      this.canvas.width = Math.round(r.width * this.dpr);
      this.canvas.height = Math.round(r.height * this.dpr);
    }
    fit(lat0, lon0, lat1, lon1, pad) {
      const a = geo.project(lat0, lon0), b = geo.project(lat1, lon1);
      const wk = Math.abs(b.x - a.x), hk = Math.abs(b.y - a.y);
      this.view.cx = (a.x + b.x) / 2;
      this.view.cy = (a.y + b.y) / 2;
      this.view.scale = Math.min((this.w - pad * 2) / wk, (this.h - pad * 2) / hk);
    }
    toScreen(x, y) {
      const v = this.view;
      return { x: (x - v.cx) * v.scale + this.w / 2, y: (y - v.cy) * v.scale + this.h / 2 };
    }
    toWorld(sx, sy) {
      const v = this.view;
      return { x: (sx - this.w / 2) / v.scale + v.cx, y: (sy - this.h / 2) / v.scale + v.cy };
    }
    zoomAt(sx, sy, f) {
      const before = this.toWorld(sx, sy);
      this.view.scale = Math.max(0.15, Math.min(12, this.view.scale * f));
      const after = this.toWorld(sx, sy);
      this.view.cx += before.x - after.x;
      this.view.cy += before.y - after.y;
    }

    // ---------- drawing helpers ----------
    path(pts, close) {
      const c = this.ctx;
      c.beginPath();
      pts.forEach((p, i) => {
        const s = this.toScreen(p.x, p.y);
        if (i) c.lineTo(s.x, s.y); else c.moveTo(s.x, s.y);
      });
      if (close) c.closePath();
    }
    stroke(pts, color, width, dash, glow) {
      const c = this.ctx;
      this.path(pts);
      c.setLineDash(dash || []);
      if (glow) {
        c.strokeStyle = color; c.globalAlpha = 0.18; c.lineWidth = width + 5; c.stroke();
        c.globalAlpha = 1;
      }
      c.strokeStyle = color; c.lineWidth = width; c.stroke();
      c.setLineDash([]);
    }
    circle(x, y, rKm, color, alpha, dash, fill) {
      const c = this.ctx, s = this.toScreen(x, y);
      c.beginPath();
      c.arc(s.x, s.y, rKm * this.view.scale, 0, Math.PI * 2);
      if (fill) { c.globalAlpha = fill; c.fillStyle = color; c.fill(); }
      c.globalAlpha = alpha; c.strokeStyle = color; c.lineWidth = 1;
      c.setLineDash(dash || []); c.stroke(); c.setLineDash([]);
      c.globalAlpha = 1;
    }
    label(text, sx, sy, color, align, size) {
      const c = this.ctx;
      c.font = `${size || 10}px "Share Tech Mono", ui-monospace, Menlo, Consolas, monospace`;
      c.textAlign = align || 'left';
      c.textBaseline = 'middle';
      c.fillStyle = '#000'; c.globalAlpha = 0.6;
      c.fillText(text, sx + 1, sy + 1);
      c.globalAlpha = 1; c.fillStyle = color;
      c.fillText(text, sx, sy);
    }

    // ---------- frame ----------
    draw(sim, ui) {
      const c = this.ctx;
      c.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      c.fillStyle = COL.bg;
      c.fillRect(0, 0, this.w, this.h);
      this.drawMap();
      if (!sim) return;
      const side = ui.side;
      this.drawSites(sim, ui);
      this.drawBases(sim, ui);
      if (this.showSensors) this.drawSensors(sim, side, ui);
      this.drawOrders(sim, ui);
      this.drawMissiles(sim, side);
      this.drawUnits(sim, side, ui);
      this.drawTracks(sim, side, ui);
      this.drawFx(sim, ui);
    }

    drawMap() {
      const c = this.ctx;
      // Lat/lon grid
      c.lineWidth = 1;
      for (let lat = 22; lat <= 37; lat++) this.stroke([geo.project(lat, 64), geo.project(lat, 80)], COL.grid, 1);
      for (let lon = 64; lon <= 80; lon++) this.stroke([geo.project(21, lon), geo.project(38, lon)], COL.grid, 1);
      for (const r of geo.rivers) this.stroke(r, COL.river, 1.2);
      this.stroke(geo.coast, COL.coast, 1.4, null, true);
      this.stroke(geo.afghan, COL.other, 1.2, [6, 4]);
      this.stroke(geo.china, COL.other, 1.2, [6, 4]);
      this.stroke(geo.border, COL.border, 1.6, null, true);
      this.stroke(geo.loc, COL.loc, 1.4, [7, 5], true);
      const s = this.view.scale;
      if (s > 0.55) {
        for (const ct of geo.cities) {
          const p = this.toScreen(ct.x, ct.y);
          c.fillStyle = COL.city;
          c.fillRect(p.x - 1.5, p.y - 1.5, 3, 3);
          this.label(ct.name.toUpperCase(), p.x + 5, p.y, COL.city, 'left', 9);
        }
      }
      // Country labels
      const pk = geo.project(30.6, 70.4), ind = geo.project(28.6, 75.6);
      const a = this.toScreen(pk.x, pk.y), b = this.toScreen(ind.x, ind.y);
      c.globalAlpha = 0.35;
      this.label('PAKISTAN', a.x, a.y, COL.PK, 'center', 16);
      this.label('INDIA', b.x, b.y, COL.IN, 'center', 16);
      c.globalAlpha = 1;
    }

    drawSites(sim, ui) {
      const c = this.ctx;
      for (const s of sim.sites) {
        const p = this.toScreen(s.x, s.y);
        const sel = ui.sel && ui.sel.ref === s;
        c.strokeStyle = COL.site; c.lineWidth = sel ? 2 : 1.3;
        c.beginPath();
        c.moveTo(p.x, p.y - 6); c.lineTo(p.x + 6, p.y); c.lineTo(p.x, p.y + 6); c.lineTo(p.x - 6, p.y); c.closePath();
        c.stroke();
        if (s.hits) { c.fillStyle = COL.site; c.fill(); }
        this.label(`${s.id}${s.hits ? ' ×' + s.hits : ''}`, p.x + 9, p.y, COL.site, 'left', 9);
      }
    }

    drawBases(sim, ui) {
      const c = this.ctx;
      for (const b of sim.bases) {
        const own = b.side === ui.side;
        const p = this.toScreen(b.x, b.y);
        const col = COL[b.side];
        c.globalAlpha = own ? 0.9 : 0.45;
        c.strokeStyle = col; c.lineWidth = 1;
        c.strokeRect(p.x - 3.5, p.y - 3.5, 7, 7);
        const ready = own && sim.units.some(u => u.kind === 'air' && u.base === b.id && u.state === 'ground' && u.count > 0);
        if (ready) { c.fillStyle = col; c.fillRect(p.x - 2, p.y - 2, 4, 4); }
        if (this.view.scale > 0.7 || own) this.label(b.id, p.x, p.y + 11, col, 'center', 9);
        if (ui.sel && ui.sel.ref === b) { c.globalAlpha = 1; c.strokeRect(p.x - 7, p.y - 7, 14, 14); }
        c.globalAlpha = 1;
      }
    }

    drawSensors(sim, side, ui) {
      const c = this.ctx;
      for (const u of sim.units) {
        if (u.side !== side) continue;
        const col = COL[side];
        if (u.kind === 'radar') { if (ui.sel && ui.sel.ref === u) this.circle(u.x, u.y, u.range, col, 0.4, [2, 6], 0.03); }
        else if (u.kind === 'sam') {
          this.circle(u.x, u.y, WEAPONS[u.def.weapon].range * 0.7, COL.site, 0.25, [3, 5], 0.02);
        } else if (u.kind === 'air' && u.state === 'air') {
          if (u.def.role === 'aew') this.circle(u.x, u.y, u.def.radar, col, 0.3, [4, 4], 0.03);
          else if (u.radarOn) {
            const s = this.toScreen(u.x, u.y), r = u.def.radar * this.view.scale;
            const a0 = u.hdg - Math.PI / 2 - 60 * DEG, a1 = u.hdg - Math.PI / 2 + 60 * DEG;
            c.beginPath(); c.moveTo(s.x, s.y); c.arc(s.x, s.y, r, a0, a1); c.closePath();
            c.fillStyle = col; c.globalAlpha = 0.05; c.fill();
            c.globalAlpha = 0.35; c.strokeStyle = col; c.lineWidth = 1; c.stroke();
            c.globalAlpha = 1;
          }
        }
      }
      // Enemy SAM threat rings that we know about.
      for (const tr of sim.tracks[side].values()) {
        if (tr.kind === 'sam') this.circle(tr.x, tr.y, WEAPONS[tr.ref.def.weapon].range * 0.7, COL.site, 0.35, [3, 5], 0.03);
      }
    }

    drawOrders(sim, ui) {
      const sel = ui.sel && ui.sel.ref;
      if (!sel || sel.kind !== 'air' || sel.state !== 'air') return;
      const o = sel.order;
      const c = this.ctx, col = COL[sel.side];
      const from = this.toScreen(sel.x, sel.y);
      const dashTo = (x, y, color) => {
        const p = this.toScreen(x, y);
        c.setLineDash([4, 4]); c.strokeStyle = color || col; c.globalAlpha = 0.8; c.lineWidth = 1;
        c.beginPath(); c.moveTo(from.x, from.y); c.lineTo(p.x, p.y); c.stroke();
        c.setLineDash([]); c.globalAlpha = 1;
        return p;
      };
      const tgt = sel.engage || (o && o.type === 'attack' ? o.target : null);
      if (tgt) {
        const tr = sim.trackFor(sel.side, tgt);
        if (tr) { const p = sim.trackPos(tr); dashTo(p.x, p.y, COL.site); }
      } else if (o && o.type === 'orbit') {
        dashTo(o.x, o.y);
        this.circle(o.x, o.y, o.r, col, 0.5, [2, 3]);
      } else if (o && o.type === 'strike' && o.list.length) {
        const s = sim.site(o.list[0].site);
        dashTo(s.x, s.y, COL.site);
        const wk = sim.strikeWeapon(sel);
        if (wk) this.circle(s.x, s.y, WEAPONS[wk].range * 0.9, COL.site, 0.4, [6, 6]);
      } else if (o && o.type === 'rtb') {
        const b = sim.base(sel.base);
        dashTo(b.x, b.y);
      }
      // Weapon reach: best BVR missile's kinematic range and a rough no-escape range.
      let best = null;
      for (const k in sel.wpn) if (sel.wpn[k] && WEAPONS[k].kind === 'aam' && (!best || WEAPONS[k].range > best.range)) best = WEAPONS[k];
      if (best) {
        this.circle(sel.x, sel.y, best.range * 0.85, col, 0.35, [1, 5]);
        this.circle(sel.x, sel.y, best.range * 0.4, col, 0.5, [8, 4]);
      }
    }

    missileVisible(sim, m, side) {
      if (m.side === side) return true;
      if (m.kind === 'cruise') return !!(m.seen[side] && m.seen[side].any);
      return !!(m.target && m.target.side === side && m.warned);
    }
    drawMissiles(sim, side) {
      const c = this.ctx;
      for (const m of sim.missiles) {
        if (!this.missileVisible(sim, m, side)) continue;
        const col = COL[m.side];
        const p = this.toScreen(m.x, m.y);
        if (m.trail.length) {
          c.beginPath();
          const t0 = this.toScreen(m.trail[0].x, m.trail[0].y);
          c.moveTo(t0.x, t0.y);
          for (const q of m.trail) { const s = this.toScreen(q.x, q.y); c.lineTo(s.x, s.y); }
          c.lineTo(p.x, p.y);
          c.strokeStyle = col; c.globalAlpha = m.kind === 'cruise' ? 0.5 : 0.35; c.lineWidth = 1;
          c.setLineDash(m.kind === 'cruise' ? [2, 3] : []);
          c.stroke(); c.setLineDash([]); c.globalAlpha = 1;
        }
        c.fillStyle = m.kind === 'cruise' ? col : COL.white;
        c.beginPath(); c.arc(p.x, p.y, m.kind === 'cruise' ? 2.2 : 1.8, 0, Math.PI * 2); c.fill();
        if (m.kind !== 'cruise' && m.lock) {
          c.strokeStyle = col; c.globalAlpha = 0.7;
          c.beginPath(); c.arc(p.x, p.y, 4, 0, Math.PI * 2); c.stroke(); c.globalAlpha = 1;
        }
      }
    }

    glyph(kind, role, sx, sy, hdg, col, opts) {
      const c = this.ctx;
      c.save();
      c.translate(sx, sy);
      c.strokeStyle = col; c.fillStyle = col; c.lineWidth = 1.5;
      if (opts && opts.dashed) c.setLineDash([2, 2]);
      if (kind === 'air' && role === 'fighter') {
        c.rotate(hdg);
        c.beginPath(); c.moveTo(0, -8); c.lineTo(5.5, 6); c.lineTo(0, 3); c.lineTo(-5.5, 6); c.closePath();
        if (opts && opts.hollow) c.stroke(); else c.fill();
      } else if (kind === 'air') {
        c.beginPath(); c.arc(0, 0, 6, 0, Math.PI * 2); c.stroke();
        c.beginPath(); c.moveTo(-9, 0); c.lineTo(9, 0); c.stroke();
        c.rotate(hdg); c.beginPath(); c.moveTo(0, -6); c.lineTo(0, -11); c.stroke();
      } else if (kind === 'sam') {
        c.strokeRect(-5, -5, 10, 10);
        c.beginPath(); c.moveTo(-3, 3); c.lineTo(0, -3); c.lineTo(3, 3); c.stroke();
      } else if (kind === 'radar') {
        c.beginPath(); c.arc(0, 0, 4, Math.PI, 0); c.stroke();
        c.beginPath(); c.moveTo(0, 0); c.lineTo(0, 5); c.stroke();
      } else if (kind === 'cruise') {
        c.beginPath(); c.arc(0, 0, 2.5, 0, Math.PI * 2); c.fill();
      }
      c.restore();
    }

    drawUnits(sim, side, ui) {
      const c = this.ctx;
      const showLabels = this.view.scale > 0.45;
      for (const u of sim.units) {
        if (u.side !== side) continue;
        if (u.kind === 'air' && u.state !== 'air') continue;
        const p = this.toScreen(u.x, u.y);
        const col = COL[side];
        const sel = ui.sel && ui.sel.ref === u;
        if (u.kind === 'air' && u.defending && Math.floor(sim.t * 2) % 2 === 0) {
          c.strokeStyle = COL.site; c.lineWidth = 1.5;
          c.beginPath(); c.arc(p.x, p.y, 13, 0, Math.PI * 2); c.stroke();
        }
        this.glyph(u.kind, u.def.role, p.x, p.y, u.hdg, col, { hollow: u.kind === 'air' && !u.radarOn });
        if (sel) {
          c.strokeStyle = COL.white; c.lineWidth = 1;
          const r = 12;
          c.beginPath();
          for (const [dx, dy] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) {
            c.moveTo(p.x + dx * r, p.y + dy * (r - 5)); c.lineTo(p.x + dx * r, p.y + dy * r); c.lineTo(p.x + dx * (r - 5), p.y + dy * r);
          }
          c.stroke();
        }
        if (u.kind === 'air' && (showLabels || sel)) {
          const tag = `${u.callsign}${u.count > 1 ? ' ×' + u.count : ''}`;
          this.label(tag, p.x + 11, p.y - 5, col, 'left', 10);
          if (sel || this.view.scale > 1.1) this.label(u.def.name, p.x + 11, p.y + 6, COL.dim, 'left', 9);
        } else if (u.kind !== 'air' && (this.view.scale > 0.9 || sel)) {
          this.label(u.callsign, p.x + 9, p.y, COL.dim, 'left', 9);
        }
      }
    }

    drawTracks(sim, side, ui) {
      const c = this.ctx;
      const enemy = KC.util.ENEMY[side];
      const col = COL[enemy];
      for (const tr of sim.tracks[side].values()) {
        if (tr.kind === 'cruise') continue; // drawn as missiles
        const pos = tr.static ? tr : sim.trackPos(tr);
        const p = this.toScreen(pos.x, pos.y);
        const stale = sim.t - tr.t > 3 && !tr.static;
        const e = tr.ref;
        c.globalAlpha = stale ? 0.45 : 1;
        const esm = tr.q === 'esm';
        if (e.kind === 'air') {
          this.glyph('air', e.def.role, p.x, p.y, e.hdg, col, { hollow: true, dashed: esm });
          if (esm) { c.strokeStyle = col; c.beginPath(); c.arc(p.x, p.y, 10, 0, Math.PI * 2); c.setLineDash([2, 3]); c.stroke(); c.setLineDash([]); }
        } else this.glyph(e.kind, null, p.x, p.y, 0, col, { dashed: esm && e.kind === 'air' });
        const sel = ui.sel && ui.sel.ref === e;
        if (sel) { c.strokeStyle = COL.white; c.strokeRect(p.x - 11, p.y - 11, 22, 22); }
        if (e.kind === 'air') {
          const name = esm ? `${e.def.name}?` : `${e.def.name}${e.count > 1 ? ' ×' + e.count : ''}`;
          this.label(`T${tr.num} ${name}`, p.x + 11, p.y - 5, col, 'left', 10);
        } else if (this.view.scale > 0.9 || sel) this.label(e.callsign, p.x + 9, p.y, COL.dim, 'left', 9);
        // Incoming-missile line to our unit.
        c.globalAlpha = 1;
      }
      void enemy;
    }

    drawFx(sim, ui) {
      const c = this.ctx;
      for (const f of sim.fx) {
        const age = sim.t - f.t;
        const p = this.toScreen(f.x, f.y);
        if (f.kind === 'kill' || f.kind === 'impact') {
          const r = 4 + age * 2.2;
          c.strokeStyle = f.kind === 'impact' ? COL.site : COL.white;
          c.globalAlpha = Math.max(0, 1 - age / 12);
          c.lineWidth = 1.5;
          c.beginPath(); c.arc(p.x, p.y, r, 0, Math.PI * 2); c.stroke();
          c.beginPath(); c.arc(p.x, p.y, r * 0.5, 0, Math.PI * 2); c.stroke();
          c.globalAlpha = 1;
        } else if (f.kind === 'miss' && age < 3) {
          c.strokeStyle = COL.dim; c.globalAlpha = 1 - age / 3;
          c.beginPath(); c.arc(p.x, p.y, 3 + age * 3, 0, Math.PI * 2); c.stroke(); c.globalAlpha = 1;
        } else if (f.kind === 'chaff' && age < 4) {
          c.fillStyle = COL.white; c.globalAlpha = 1 - age / 4;
          for (let i = 0; i < 5; i++) c.fillRect(p.x - 6 + i * 3, p.y + 4 + (i % 2) * 3, 1.5, 1.5);
          c.globalAlpha = 1;
        }
      }
      for (const f of ui.marks || []) {
        const age = performance.now() / 1000 - f.t;
        if (age > 1) continue;
        const p = this.toScreen(f.x, f.y);
        c.strokeStyle = COL.white; c.globalAlpha = 1 - age;
        c.beginPath(); c.arc(p.x, p.y, 4 + age * 14, 0, Math.PI * 2); c.stroke(); c.globalAlpha = 1;
      }
    }
  }

  KC.Renderer = Renderer;
})(globalThis.KC);
