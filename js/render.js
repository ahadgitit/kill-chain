// Canvas renderer: cached vector map layer, units, tracks, missiles, sweeps and effects.
(function (KC) {
  const { geo, WEAPONS } = KC;
  const { DEG } = KC.util;

  const COL = {
    bg: '#01050a', sea0: '#03101a', sea1: '#01050a',
    land: '#06121a', landIN: '#140e07', landPK: '#06150f', landX: '#08151c',
    dots: 'rgba(90, 170, 200, 0.2)', dotsIN: 'rgba(255, 154, 60, 0.13)', dotsPK: 'rgba(51, 229, 140, 0.12)',
    grid: 'rgba(70, 150, 190, 0.10)', gridText: 'rgba(110, 170, 200, 0.45)',
    river: '#0c3a55', coast: '#2c9cc4', border: '#2b5a6e', front: '#5fe0ff',
    city: '#4f7383', text: '#9fc4d3', dim: '#4b6b78',
    IN: '#ff9a3c', PK: '#33e58c', site: '#ff4d6d', white: '#e8f6ff', hot: '#ffd166',
  };
  KC.COL = COL;
  const FONT = '"Share Tech Mono", ui-monospace, Menlo, Consolas, monospace';
  const ease = t => (t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);

  class Renderer {
    constructor(canvas) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.view = { cx: 0, cy: 0, scale: 1 };
      this.w = 0; this.h = 0; this.dpr = 1;
      this.showSensors = true;
      this.map = document.createElement('canvas');
      this.mapKey = '';
      this.flight = null;
      this.cursor = null;
      // World-space bounds of the map data, for clamping the camera.
      const [lo0, la0, lo1, la1] = geo.bbox;
      const pts = [];
      for (let la = la0; la <= la1; la += 2) for (const lo of [lo0, lo1]) pts.push(geo.project(la, lo));
      for (let lo = lo0; lo <= lo1; lo += 2) for (const la of [la0, la1]) pts.push(geo.project(la, lo));
      this.bounds = {
        x0: Math.min(...pts.map(p => p.x)), x1: Math.max(...pts.map(p => p.x)),
        y0: Math.min(...pts.map(p => p.y)), y1: Math.max(...pts.map(p => p.y)),
      };
      this.dotPatterns = {};
    }
    resize() {
      const r = this.canvas.getBoundingClientRect();
      this.dpr = Math.min(2, window.devicePixelRatio || 1);
      this.w = r.width; this.h = r.height;
      this.canvas.width = Math.round(r.width * this.dpr);
      this.canvas.height = Math.round(r.height * this.dpr);
      this.map.width = this.canvas.width;
      this.map.height = this.canvas.height;
      this.mapKey = '';
    }
    viewFor(lat0, lon0, lat1, lon1, pad) {
      const a = geo.project(lat0, lon0), b = geo.project(lat1, lon1);
      const wk = Math.abs(b.x - a.x), hk = Math.abs(b.y - a.y);
      return {
        cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2,
        scale: Math.min((this.w - pad * 2) / wk, (this.h - pad * 2) / hk),
      };
    }
    fit(lat0, lon0, lat1, lon1, pad) {
      this.flight = null;
      Object.assign(this.view, this.viewFor(lat0, lon0, lat1, lon1, pad));
    }
    // Smooth camera move (zooms out a little mid-flight, like a map fly-to).
    flyTo(target, ms) {
      this.flight = { from: { ...this.view }, to: target, t0: performance.now(), ms: ms || 2000 };
    }
    stepFlight() {
      const f = this.flight;
      if (!f) return;
      const k = Math.min(1, (performance.now() - f.t0) / f.ms), e = ease(k);
      const ls = Math.log(f.from.scale) + (Math.log(f.to.scale) - Math.log(f.from.scale)) * e;
      this.view.cx = f.from.cx + (f.to.cx - f.from.cx) * e;
      this.view.cy = f.from.cy + (f.to.cy - f.from.cy) * e;
      this.view.scale = Math.exp(ls);
      if (k >= 1) this.flight = null;
    }
    clampView() {
      const v = this.view, b = this.bounds;
      const minScale = Math.min(this.w / (b.x1 - b.x0), this.h / (b.y1 - b.y0)) * 0.95;
      v.scale = Math.max(minScale, Math.min(14, v.scale));
      const hw = this.w / 2 / v.scale, hh = this.h / 2 / v.scale;
      v.cx = (b.x1 - b.x0) < hw * 2 ? (b.x0 + b.x1) / 2 : Math.max(b.x0 + hw, Math.min(b.x1 - hw, v.cx));
      v.cy = (b.y1 - b.y0) < hh * 2 ? (b.y0 + b.y1) / 2 : Math.max(b.y0 + hh, Math.min(b.y1 - hh, v.cy));
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
      this.flight = null;
      const before = this.toWorld(sx, sy);
      this.view.scale = Math.max(0.03, Math.min(14, this.view.scale * f));
      const after = this.toWorld(sx, sy);
      this.view.cx += before.x - after.x;
      this.view.cy += before.y - after.y;
    }

    // ---------- drawing helpers ----------
    flatPath(c, a, close) {
      const v = this.view, s = v.scale, ox = this.w / 2 - v.cx * s, oy = this.h / 2 - v.cy * s;
      c.moveTo(a[0] * s + ox, a[1] * s + oy);
      for (let i = 2; i < a.length; i += 2) c.lineTo(a[i] * s + ox, a[i + 1] * s + oy);
      if (close) c.closePath();
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
    label(text, sx, sy, color, align, size, ctx) {
      const c = ctx || this.ctx;
      c.font = `${size || 10}px ${FONT}`;
      c.textAlign = align || 'left';
      c.textBaseline = 'middle';
      const a = c.globalAlpha;
      c.fillStyle = '#000'; c.globalAlpha = a * 0.65;
      c.fillText(text, sx + 1, sy + 1);
      c.globalAlpha = a; c.fillStyle = color;
      c.fillText(text, sx, sy);
    }
    spaced(c, text, x, y, size, spacing, color, alpha) {
      c.font = `${size}px ${FONT}`;
      c.textBaseline = 'middle';
      c.textAlign = 'left';
      const widths = [...text].map(ch => c.measureText(ch).width);
      const total = widths.reduce((a, b) => a + b, 0) + spacing * (text.length - 1);
      let px = x - total / 2;
      c.globalAlpha = alpha; c.fillStyle = color;
      [...text].forEach((ch, i) => { c.fillText(ch, px, y); px += widths[i] + spacing; });
      c.globalAlpha = 1;
    }
    dotPattern(c, color) {
      const key = color + this.dpr;
      if (!this.dotPatterns[key]) {
        const t = document.createElement('canvas');
        const n = Math.round(5 * this.dpr);
        t.width = t.height = n;
        const g = t.getContext('2d');
        g.fillStyle = color;
        g.beginPath(); g.arc(n / 2, n / 2, 0.7 * this.dpr, 0, Math.PI * 2); g.fill();
        this.dotPatterns[key] = c.createPattern(t, 'repeat');
      }
      return this.dotPatterns[key];
    }

    // ---------- frame ----------
    draw(sim, ui) {
      this.stepFlight();
      this.clampView();
      const c = this.ctx;
      const key = `${this.view.cx.toFixed(2)},${this.view.cy.toFixed(2)},${this.view.scale.toFixed(5)},${this.w},${this.h}`;
      // While the camera moves, redraw a light version of the map; full quality once it settles.
      const now = performance.now();
      if (key !== this.mapKey) {
        this.drawMap(true);
        this.mapKey = key; this.mapFast = true; this.mapMovedAt = now;
      } else if (this.mapFast && now - this.mapMovedAt > 140) {
        this.drawMap(false);
        this.mapFast = false;
      }
      c.setTransform(1, 0, 0, 1, 0, 0);
      c.drawImage(this.map, 0, 0);
      c.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      if (!sim) { this.drawHud(ui); return; }
      const side = ui.side;
      const spectator = side === 'ALL';
      this.drawSites(sim, ui);
      this.drawBases(sim, ui);
      this.drawJamming(sim);
      if (!spectator) this.drawObjectives(sim, ui);
      if (this.showSensors) {
        for (const s of spectator ? ['IN', 'PK'] : [side]) this.drawSensors(sim, s, ui, spectator);
      }
      this.drawSweeps(sim, spectator ? null : side);
      if (!spectator) this.drawOrders(sim, ui);
      this.drawMissiles(sim, side);
      for (const s of spectator ? ['IN', 'PK'] : [side]) this.drawUnits(sim, s, ui);
      if (!spectator) this.drawTracks(sim, side, ui);
      this.drawFx(sim, ui);
      this.drawHud(ui);
    }

    // The static map, re-rendered only when the camera moves.
    drawMap(fast) {
      const c = this.map.getContext('2d');
      const v = this.view, s = v.scale;
      c.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      // Sea: dark radial falloff from the theatre.
      const ctr = this.toScreen(0, 0);
      const g = c.createRadialGradient(ctr.x, ctr.y, 0, ctr.x, ctr.y, Math.max(this.w, this.h) * 1.1 + 2500 * s);
      g.addColorStop(0, COL.sea0); g.addColorStop(1, COL.sea1);
      c.fillStyle = g;
      c.fillRect(0, 0, this.w, this.h);

      // Land fills, tinted for the two belligerents, with a dot-matrix texture.
      const n = Math.round(5 * this.dpr);
      const origin = this.toScreen(0, 0);
      const offX = ((origin.x * this.dpr) % n + n) % n, offY = ((origin.y * this.dpr) % n + n) % n;
      for (const ct of geo.countries) {
        const tint = ct.name === 'India' ? 'IN' : ct.name === 'Pakistan' ? 'PK' : 'X';
        c.beginPath();
        for (const r of ct.rings) this.flatPath(c, r, true);
        c.fillStyle = tint === 'IN' ? COL.landIN : tint === 'PK' ? COL.landPK : COL.landX;
        c.fill('evenodd');
        if (fast) continue;
        const pat = this.dotPattern(c, tint === 'IN' ? COL.dotsIN : tint === 'PK' ? COL.dotsPK : COL.dots);
        if (pat.setTransform) pat.setTransform(new DOMMatrix([1 / this.dpr, 0, 0, 1 / this.dpr, offX / this.dpr, offY / this.dpr]));
        c.fillStyle = pat;
        c.globalAlpha = tint === 'X' ? 0.8 : 1;
        c.fill('evenodd');
        c.globalAlpha = 1;
      }
      // Lakes
      c.beginPath();
      for (const l of geo.lakes) this.flatPath(c, l, true);
      c.fillStyle = COL.sea0; c.fill();

      // Graticule: curved under the stereographic projection.
      const step = s > 1.2 ? 1 : s > 0.35 ? 2 : 5;
      c.strokeStyle = COL.grid; c.lineWidth = 1;
      c.beginPath();
      const [lo0, la0, lo1, la1] = geo.bbox;
      for (let la = Math.ceil(la0 / step) * step; la <= la1; la += step) {
        for (let lo = lo0; lo <= lo1; lo += 1) {
          const p = this.toScreen(...Object.values(geo.project(la, lo)));
          if (lo === lo0) c.moveTo(p.x, p.y); else c.lineTo(p.x, p.y);
        }
      }
      for (let lo = Math.ceil(lo0 / step) * step; lo <= lo1; lo += step) {
        for (let la = la0; la <= la1; la += 1) {
          const p = this.toScreen(...Object.values(geo.project(la, lo)));
          if (la === la0) c.moveTo(p.x, p.y); else c.lineTo(p.x, p.y);
        }
      }
      c.stroke();

      // Rivers
      c.strokeStyle = COL.river; c.lineWidth = s > 0.8 ? 1.3 : 0.9;
      c.beginPath();
      for (const r of geo.rivers) this.flatPath(c, r);
      c.stroke();

      // Coastlines with a soft glow into the sea.
      c.save();
      c.strokeStyle = COL.coast;
      c.shadowColor = 'rgba(60, 190, 255, 0.55)';
      c.shadowBlur = fast ? 0 : 10;
      c.lineWidth = 1.2;
      c.beginPath();
      for (const r of geo.coasts) this.flatPath(c, r);
      c.stroke();
      c.restore();

      // Other land borders
      c.strokeStyle = COL.border; c.lineWidth = 1; c.setLineDash([5, 4]);
      c.beginPath();
      for (const r of geo.borders) this.flatPath(c, r);
      c.stroke();
      c.setLineDash([]);

      // The India-Pakistan frontier: international border solid, Line of Control dashed.
      c.save();
      c.shadowColor = 'rgba(95, 224, 255, 0.9)';
      c.shadowBlur = fast ? 0 : 12;
      c.strokeStyle = COL.front;
      c.lineWidth = 1.8;
      c.beginPath(); this.flatPath(c, geo.ib); c.stroke();
      c.setLineDash([8, 5]);
      c.beginPath(); this.flatPath(c, geo.loc); c.stroke();
      c.restore();
      c.setLineDash([]);

      // Labels: countries, seas, terrain, cities, graticule.
      for (const L of geo.labels) {
        const p = this.toScreen(L.x, L.y);
        if (p.x < -200 || p.x > this.w + 200 || p.y < -50 || p.y > this.h + 50) continue;
        if (L.kind === 'IN' || L.kind === 'PK') {
          const size = Math.max(14, Math.min(34, 9 + s * 12));
          this.spaced(c, L.text, p.x, p.y, size, size * 0.55, COL[L.kind], 0.3);
        } else if (L.kind === 'X') {
          if (L.rank === 3 && s < 0.18) continue;
          this.spaced(c, L.text, p.x, p.y, Math.max(10, Math.min(18, 7 + s * 7)), 4, '#5d8494', 0.45);
        } else if (L.kind === 'SEA') {
          this.spaced(c, L.text, p.x, p.y, Math.max(11, Math.min(20, 8 + s * 8)), 6, '#2c7fa3', 0.5);
        } else if (L.kind === 'TER') {
          if (s < 0.14 || s > 1.6) continue;
          this.spaced(c, L.text, p.x, p.y, 10, 5, '#3b5f6c', 0.4);
        } else if (L.kind === 'LOC' && s > 0.9) {
          this.spaced(c, L.text, p.x, p.y, 9, 3, COL.front, 0.55);
        }
      }
      const minRank = s > 0.9 ? 3 : s > 0.28 ? 2 : 1;
      for (const ct of geo.cities) {
        if (ct.rank > minRank) continue;
        const p = this.toScreen(ct.x, ct.y);
        if (p.x < -50 || p.x > this.w + 50 || p.y < -20 || p.y > this.h + 20) continue;
        c.fillStyle = COL.city;
        c.fillRect(p.x - 1.5, p.y - 1.5, 3, 3);
        if (ct.rank === 1) { c.strokeStyle = COL.city; c.strokeRect(p.x - 3.5, p.y - 3.5, 7, 7); }
        this.label(ct.name.toUpperCase(), p.x + 6, p.y, COL.city, 'left', ct.rank === 1 ? 10 : 9, c);
      }
      // Graticule labels along the left and top edges.
      c.globalAlpha = 1;
      for (let la = Math.ceil(la0 / step) * step; la <= la1; la += step) {
        const w = this.toWorld(14, 0);
        const ll = geo.unproject(w.x, w.y);
        const p = this.toScreen(...Object.values(geo.project(la, ll.lon)));
        if (p.y > 60 && p.y < this.h - 10) this.label(`${la}°N`, 6, p.y - 7, COL.gridText, 'left', 9, c);
      }
      for (let lo = Math.ceil(lo0 / step) * step; lo <= lo1; lo += step) {
        const w = this.toWorld(0, this.h - 14);
        const ll = geo.unproject(w.x, w.y);
        const p = this.toScreen(...Object.values(geo.project(ll.lat, lo)));
        if (p.x > 40 && p.x < this.w - 40) this.label(`${lo}°E`, p.x + 3, this.h - 9, COL.gridText, 'left', 9, c);
      }
    }

    drawSites(sim, ui) {
      const c = this.ctx;
      const pulse = 0.5 + 0.5 * Math.sin(performance.now() / 400);
      for (const s of sim.sites) {
        const p = this.toScreen(s.x, s.y);
        const sel = ui.sel && ui.sel.ref === s;
        c.strokeStyle = COL.site; c.lineWidth = sel ? 2 : 1.3;
        c.beginPath();
        c.moveTo(p.x, p.y - 6); c.lineTo(p.x + 6, p.y); c.lineTo(p.x, p.y + 6); c.lineTo(p.x - 6, p.y); c.closePath();
        c.stroke();
        if (s.hits) {
          c.fillStyle = COL.site; c.fill();
          c.globalAlpha = 0.25 + 0.25 * pulse;
          c.beginPath(); c.arc(p.x, p.y, 9 + pulse * 3, 0, Math.PI * 2); c.stroke();
          c.globalAlpha = 1;
        }
        if (this.view.scale > 0.25 || sel) this.label(`${s.id}${s.hits ? ' ×' + s.hits : ''}`, p.x + 9, p.y, COL.site, 'left', 9);
      }
    }

    drawBases(sim, ui) {
      const c = this.ctx;
      for (const b of sim.bases) {
        const own = b.side === ui.side || ui.side === 'ALL';
        const p = this.toScreen(b.x, b.y);
        if (p.x < -20 || p.x > this.w + 20 || p.y < -20 || p.y > this.h + 20) continue;
        const col = COL[b.side];
        c.globalAlpha = own ? 0.9 : 0.45;
        c.strokeStyle = col; c.lineWidth = 1;
        c.strokeRect(p.x - 3.5, p.y - 3.5, 7, 7);
        const pools = Object.entries(b.pools || {});
        const ready = pools.reduce((a, [, q]) => a + q.ready, 0);
        if (own && ready) { c.fillStyle = col; c.fillRect(p.x - 2, p.y - 2, 4, 4); }
        // Damage: a red arc that fills toward closure; a cross when the runway is closed.
        if (b.damage > 0) {
          c.globalAlpha = 0.9; c.strokeStyle = COL.site; c.lineWidth = 2;
          c.beginPath(); c.arc(p.x, p.y, 8, -Math.PI / 2, -Math.PI / 2 + Math.min(1, b.damage / 60) * Math.PI * 2); c.stroke();
          if (b.damage >= 60) {
            c.beginPath(); c.moveTo(p.x - 6, p.y - 6); c.lineTo(p.x + 6, p.y + 6); c.moveTo(p.x + 6, p.y - 6); c.lineTo(p.x - 6, p.y + 6); c.stroke();
          }
          c.lineWidth = 1; c.globalAlpha = own ? 0.9 : 0.6;
        }
        if (this.view.scale > 0.7 || (own && this.view.scale > 0.3)) {
          this.label(b.id + (b.damage >= 60 ? ' · CLOSED' : ''), p.x, p.y + 12, b.damage >= 60 ? COL.site : col, 'center', 9);
          if (own && pools.length && ui.side !== 'ALL') {
            const txt = pools.map(([ty, q]) => `${KC.AIRCRAFT[ty].name.split(' ')[0]} ${q.ready}${q.rearm.length ? '+' + q.rearm.length : ''}`).join(' ');
            this.label(txt, p.x + 9, p.y - 1, COL.dim, 'left', 9);
          }
        }
        if (ui.sel && ui.sel.ref === b) { c.globalAlpha = 1; c.strokeStyle = COL.white; c.strokeRect(p.x - 7, p.y - 7, 14, 14); }
        c.globalAlpha = 1;
      }
    }

    drawSensors(sim, side, ui, spectator) {
      const c = this.ctx;
      for (const u of sim.units) {
        if (u.side !== side) continue;
        const col = COL[side];
        if (u.kind === 'radar') { if (ui.sel && ui.sel.ref === u) this.circle(u.x, u.y, u.range, col, 0.4, [2, 6], 0.03); }
        else if (u.kind === 'sam') {
          if (!spectator && !u.dead && u.radarOn) this.circle(u.x, u.y, WEAPONS[u.def.weapon].range * 0.7, COL.site, 0.22, [3, 5], 0.018);
        } else if (u.kind === 'air' && u.state === 'air') {
          if (u.def.role === 'aew') {
            const s = this.toScreen(u.x, u.y), r = u.def.radar * this.view.scale;
            const g = c.createRadialGradient(s.x, s.y, r * 0.2, s.x, s.y, r);
            g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, col);
            c.globalAlpha = 0.06; c.fillStyle = g;
            c.beginPath(); c.arc(s.x, s.y, r, 0, Math.PI * 2); c.fill();
            c.globalAlpha = 0.35; c.strokeStyle = col; c.setLineDash([4, 4]); c.stroke(); c.setLineDash([]);
            c.globalAlpha = 1;
          } else if (u.radarOn) {
            const s = this.toScreen(u.x, u.y), r = u.def.radar * this.view.scale;
            const a0 = u.hdg - Math.PI / 2 - 60 * DEG, a1 = u.hdg - Math.PI / 2 + 60 * DEG;
            const g = c.createRadialGradient(s.x, s.y, 0, s.x, s.y, r);
            g.addColorStop(0, col); g.addColorStop(1, 'rgba(0,0,0,0)');
            c.beginPath(); c.moveTo(s.x, s.y); c.arc(s.x, s.y, r, a0, a1); c.closePath();
            c.fillStyle = g; c.globalAlpha = 0.12; c.fill();
            c.globalAlpha = 0.3; c.strokeStyle = col; c.lineWidth = 1; c.stroke();
            c.globalAlpha = 1;
          }
        }
      }
      if (spectator) return;
      // Enemy SAM threat rings that we know about.
      for (const tr of sim.tracks[side].values()) {
        if (tr.kind === 'sam' && !tr.ref.dead) this.circle(tr.x, tr.y, WEAPONS[tr.ref.def.weapon].range * 0.7, COL.site, 0.35, [3, 5], 0.03);
      }
    }

    // Rotating radar sweeps on AEW&C and ground radars.
    drawSweeps(sim, side) {
      const c = this.ctx;
      const now = performance.now() / 1000;
      for (const u of sim.units) {
        if (side && u.side !== side) continue;
        let R, period;
        if (u.kind === 'radar') { if (u.dead || !u.radarOn) continue; R = u.range; period = 6; }
        else if (u.kind === 'air' && u.state === 'air' && u.def.role === 'aew') { R = u.def.radar; period = 5; }
        else continue;
        const s = this.toScreen(u.x, u.y), r = R * this.view.scale;
        if (r < 8 || s.x < -r || s.x > this.w + r || s.y < -r || s.y > this.h + r) continue;
        const a = ((now + u.id * 0.37) / period) * Math.PI * 2;
        const col = COL[u.side];
        c.save();
        c.globalCompositeOperation = 'lighter';
        const slices = 10, span = 0.7;
        for (let i = 0; i < slices; i++) {
          const a1 = a - (i / slices) * span, a0 = a - ((i + 1) / slices) * span;
          c.beginPath(); c.moveTo(s.x, s.y); c.arc(s.x, s.y, r, a0, a1); c.closePath();
          c.fillStyle = col;
          c.globalAlpha = (u.kind === 'radar' ? 0.05 : 0.08) * (1 - i / slices);
          c.fill();
        }
        c.globalAlpha = u.kind === 'radar' ? 0.25 : 0.4;
        c.strokeStyle = col; c.lineWidth = 1;
        c.beginPath(); c.moveTo(s.x, s.y); c.lineTo(s.x + Math.cos(a) * r, s.y + Math.sin(a) * r); c.stroke();
        c.restore();
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
        c.setLineDash([4, 4]); c.lineDashOffset = -performance.now() / 60;
        c.strokeStyle = color || col; c.globalAlpha = 0.8; c.lineWidth = 1;
        c.beginPath(); c.moveTo(from.x, from.y); c.lineTo(p.x, p.y); c.stroke();
        c.setLineDash([]); c.lineDashOffset = 0; c.globalAlpha = 1;
        return p;
      };
      const tgt = sel.engage || (o && o.type === 'attack' ? o.target : null);
      if (tgt) {
        const tr = sim.trackFor(sel.side, tgt);
        if (tr) { const p = sim.trackPos(tr); dashTo(p.x, p.y, COL.site); }
      } else if (o && o.type === 'orbit') {
        dashTo(o.x, o.y);
        this.circle(o.x, o.y, o.r, col, 0.5, [2, 3]);
      } else if (o && (o.type === 'strike' || o.type === 'drone') && o.list.length) {
        const s = sim.refFor(o.list[0].kind, o.list[0].id);
        if (s) {
          dashTo(s.x, s.y, COL.site);
          const wk = sim.strikeWeapon(sel);
          if (wk) this.circle(s.x, s.y, WEAPONS[wk].range * 0.9, COL.site, 0.4, [6, 6]);
        }
      } else if (o && o.type === 'sead') {
        dashTo(o.x, o.y);
        this.circle(o.x, o.y, 45, COL.site, 0.35, [2, 4]);
      } else if (o && o.type === 'rtb') {
        const b = sim.base(o.base || sel.base);
        if (b) dashTo(b.x, b.y);
      }
      // Weapon reach: best BVR missile's kinematic range and a rough no-escape range.
      let best = null;
      for (const k in sel.wpn) if (sel.wpn[k] && WEAPONS[k].kind === 'aam' && (!best || WEAPONS[k].range > best.range)) best = WEAPONS[k];
      if (best) {
        this.circle(sel.x, sel.y, best.range * 0.85, col, 0.35, [1, 5]);
        this.circle(sel.x, sel.y, best.range * 0.4, col, 0.5, [8, 4]);
        const p = this.toScreen(sel.x, sel.y - best.range * 0.4);
        if (this.view.scale > 0.4) this.label('NO-ESCAPE', p.x, p.y - 6, col, 'center', 8);
      }
    }

    missileVisible(sim, m, side) {
      if (side === 'ALL' || m.side === side) return true;
      if (m.kind === 'cruise') return !!(m.seen[side] && m.seen[side].any);
      return !!(m.target && m.target.side === side && m.warned);
    }
    drawMissiles(sim, side) {
      const c = this.ctx;
      for (const m of sim.missiles) {
        if (!this.missileVisible(sim, m, side)) continue;
        const col = COL[m.side];
        const p = this.toScreen(m.x, m.y);
        const cruise = m.kind === 'cruise';
        // Fading trail
        const tr = m.trail;
        if (tr.length) {
          c.lineWidth = cruise ? 1 : 1.4;
          c.strokeStyle = col;
          let prev = this.toScreen(tr[0].x, tr[0].y);
          for (let i = 1; i <= tr.length; i++) {
            const q = i < tr.length ? this.toScreen(tr[i].x, tr[i].y) : p;
            c.globalAlpha = (cruise ? 0.5 : 0.7) * (i / tr.length);
            c.beginPath(); c.moveTo(prev.x, prev.y); c.lineTo(q.x, q.y); c.stroke();
            prev = q;
          }
          c.globalAlpha = 1;
        }
        if (!cruise && sim.isAlive(m.target) && side !== 'ALL') this.drawThreatLine(sim, m, side, p);
        // Glowing head
        c.save();
        c.globalCompositeOperation = 'lighter';
        const g = c.createRadialGradient(p.x, p.y, 0, p.x, p.y, cruise ? 6 : 8);
        g.addColorStop(0, cruise ? col : COL.white); g.addColorStop(1, 'rgba(0,0,0,0)');
        c.fillStyle = g;
        c.beginPath(); c.arc(p.x, p.y, cruise ? 6 : 8, 0, Math.PI * 2); c.fill();
        c.restore();
        c.fillStyle = cruise ? col : COL.white;
        c.beginPath(); c.arc(p.x, p.y, cruise ? 2 : 1.7, 0, Math.PI * 2); c.fill();
        if (!cruise && m.lock) {
          c.strokeStyle = col; c.globalAlpha = 0.8;
          const r = 4 + (performance.now() / 120) % 4;
          c.beginPath(); c.arc(p.x, p.y, r, 0, Math.PI * 2); c.stroke(); c.globalAlpha = 1;
        }
      }
    }

    // Incoming missiles: a line to their target, red while the missile still has the
    // energy to reach it, grey once it is too slow. Own missiles: a faint line to the target.
    drawThreatLine(sim, m, side, p) {
      const c = this.ctx, t = m.target;
      const q = this.toScreen(t.x, t.y);
      if (m.side === side) {
        c.strokeStyle = COL[side]; c.globalAlpha = 0.25; c.setLineDash([2, 4]);
        c.beginPath(); c.moveTo(p.x, p.y); c.lineTo(q.x, q.y); c.stroke();
        c.setLineDash([]); c.globalAlpha = 1;
        return;
      }
      const th = sim.missileThreat(m, false);
      const col = th.lethal ? COL.site : COL.dim;
      c.strokeStyle = col; c.lineWidth = th.lethal ? 1.5 : 1; c.globalAlpha = 0.9;
      c.setLineDash(th.lethal ? [] : [3, 4]);
      c.beginPath(); c.moveTo(p.x, p.y); c.lineTo(q.x, q.y); c.stroke();
      c.setLineDash([]); c.globalAlpha = 1; c.lineWidth = 1;
      const txt = `${m.w.name} M${th.mach.toFixed(1)} ${th.d.toFixed(0)}km ${th.lethal ? (m.lock ? 'LOCKED' : 'CLOSING') : 'LOSING ENERGY'}`;
      this.label(txt, p.x + 6, p.y + 10, col, 'left', 10);
    }

    glyph(kind, role, sx, sy, hdg, col, opts) {
      const c = this.ctx;
      c.save();
      c.translate(sx, sy);
      c.strokeStyle = col; c.fillStyle = col; c.lineWidth = 1.5;
      if (opts && opts.glow) { c.shadowColor = col; c.shadowBlur = 8; }
      if (opts && opts.dashed) c.setLineDash([2, 2]);
      if (kind === 'air' && role === 'drone') {
        c.rotate(hdg);
        c.beginPath(); c.moveTo(-4, -4); c.lineTo(4, 4); c.moveTo(4, -4); c.lineTo(-4, 4); c.stroke();
        c.beginPath(); c.moveTo(0, -6); c.lineTo(0, -3); c.stroke();
      } else if (kind === 'air' && role === 'fighter') {
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
        if (p.x < -40 || p.x > this.w + 40 || p.y < -40 || p.y > this.h + 40) continue;
        const col = COL[side];
        const sel = ui.sel && ui.sel.ref === u;
        if (u.kind === 'air' && u.defending && Math.floor(sim.t * 2) % 2 === 0) {
          c.strokeStyle = COL.site; c.lineWidth = 1.5;
          c.beginPath(); c.arc(p.x, p.y, 13, 0, Math.PI * 2); c.stroke();
        }
        // Short velocity leader for aircraft
        if (u.kind === 'air') {
          c.strokeStyle = col; c.globalAlpha = 0.45; c.lineWidth = 1;
          const L = 10 + u.spd * 40;
          c.beginPath(); c.moveTo(p.x + Math.sin(u.hdg) * 9, p.y - Math.cos(u.hdg) * 9);
          c.lineTo(p.x + Math.sin(u.hdg) * (9 + L), p.y - Math.cos(u.hdg) * (9 + L)); c.stroke();
          c.globalAlpha = 1;
        }
        if (u.dead) { this.wreck(p, u.callsign); continue; }
        this.glyph(u.kind, u.def.role, p.x, p.y, u.hdg, col, { hollow: u.kind === 'air' && !u.radarOn, glow: u.kind === 'air' && !u.ghost, dashed: u.ghost || (u.kind !== 'air' && !u.radarOn) });
        if (u.kind === 'air' && u.defending) this.label(u.defMode === 'notch' ? 'NOTCH' : 'DRAG', p.x - 11, p.y + 14, COL.site, 'right', 9);
        if (sel) {
          c.strokeStyle = COL.white; c.lineWidth = 1;
          const r = 12 + Math.sin(performance.now() / 250) * 1.5;
          c.beginPath();
          for (const [dx, dy] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) {
            c.moveTo(p.x + dx * r, p.y + dy * (r - 5)); c.lineTo(p.x + dx * r, p.y + dy * r); c.lineTo(p.x + dx * (r - 5), p.y + dy * r);
          }
          c.stroke();
        }
        if (u.kind === 'air' && (showLabels || sel)) {
          const tag = `${u.callsign}${u.count > 1 ? ' ×' + u.count : ''}`;
          this.label(tag, p.x + 11, p.y - 5, u.ghost ? COL.dim : col, 'left', 10);
          if (sel || this.view.scale > 1.1) this.label(u.def.name, p.x + 11, p.y + 6, COL.dim, 'left', 9);
        } else if (u.kind !== 'air' && (this.view.scale > 0.9 || sel)) {
          this.label(u.callsign, p.x + 9, p.y, COL.dim, 'left', 9);
        }
      }
    }

    wreck(p, name) {
      const c = this.ctx;
      c.strokeStyle = COL.dim; c.lineWidth = 1.5;
      c.beginPath(); c.moveTo(p.x - 5, p.y - 5); c.lineTo(p.x + 5, p.y + 5); c.moveTo(p.x + 5, p.y - 5); c.lineTo(p.x - 5, p.y + 5); c.stroke();
      if (this.view.scale > 0.6) this.label(name + ' (destroyed)', p.x + 9, p.y, COL.dim, 'left', 9);
    }
    // Active barrage jamming areas (both sides can see the noise).
    drawJamming(sim) {
      const c = this.ctx;
      if (!sim.ew) return;
      for (const side of ['IN', 'PK']) {
        const e = sim.ew[side].barrage;
        if (!(e.until > sim.t)) continue;
        const p = this.toScreen(e.x, e.y), r = KC.EW.barrage.r * this.view.scale;
        const now = performance.now() / 1000;
        c.save();
        c.strokeStyle = COL[side];
        c.globalAlpha = 0.5;
        c.setLineDash([3, 6]); c.lineDashOffset = -now * 30;
        c.beginPath(); c.arc(p.x, p.y, r, 0, Math.PI * 2); c.stroke();
        c.globalAlpha = 0.08; c.fillStyle = COL[side];
        c.beginPath(); c.arc(p.x, p.y, r, 0, Math.PI * 2); c.fill();
        c.globalAlpha = 0.35;
        for (let i = 0; i < 40; i++) {
          const a = (i * 2.4 + now * 3) % (Math.PI * 2), d = r * (((i * 37) % 100) / 100);
          c.fillRect(p.x + Math.cos(a) * d, p.y + Math.sin(a) * d, 2, 1);
        }
        c.restore();
        this.label('JAMMING', p.x, p.y - r - 8, COL[side], 'center', 9);
      }
    }
    // Priority targets: red reticles on ours to hit, amber rings on our assets to defend.
    drawObjectives(sim, ui) {
      const c = this.ctx;
      const pulse = 0.5 + 0.5 * Math.sin(performance.now() / 350);
      for (const o of sim.objectives[ui.side]) {
        if (!o.ref) continue;
        const p = this.toScreen(o.ref.x, o.ref.y);
        const r = 13 + pulse * 2;
        c.strokeStyle = o.hit ? COL.dim : COL.site; c.lineWidth = 1.3; c.globalAlpha = 0.9;
        c.beginPath(); c.arc(p.x, p.y, r, 0, Math.PI * 2); c.stroke();
        c.beginPath();
        for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { c.moveTo(p.x + dx * (r - 4), p.y + dy * (r - 4)); c.lineTo(p.x + dx * (r + 5), p.y + dy * (r + 5)); }
        c.stroke();
        c.globalAlpha = 1;
        this.hitPips(o.ref, p.x, p.y - r - 9, COL.site);
      }
      for (const o of sim.objectives[KC.util.ENEMY[ui.side]]) {
        if (!o.ref) continue;
        const p = this.toScreen(o.ref.x, o.ref.y);
        c.strokeStyle = o.hit ? COL.site : COL.hot; c.globalAlpha = 0.7; c.setLineDash([4, 3]);
        c.beginPath(); c.arc(p.x, p.y, 15, 0, Math.PI * 2); c.stroke();
        c.setLineDash([]); c.globalAlpha = 1;
        if (this.view.scale > 0.5) this.label('DEFEND', p.x, p.y + 24, COL.hot, 'center', 8);
        if (o.ref.hits) this.hitPips(o.ref, p.x, p.y - 22, COL.hot);
      }
    }
    // Three boxes over a ground target: filled for each hit taken (3 = destroyed).
    hitPips(ref, x, y, col) {
      const c = this.ctx, h = Math.min(3, ref.hits || 0);
      for (let i = 0; i < 3; i++) {
        const px = x - 10 + i * 7;
        c.strokeStyle = col; c.lineWidth = 1;
        c.strokeRect(px, y - 2.5, 5, 5);
        if (i < h) { c.fillStyle = col; c.fillRect(px, y - 2.5, 5, 5); }
      }
    }

    drawTracks(sim, side, ui) {
      const c = this.ctx;
      const col = COL[KC.util.ENEMY[side]];
      for (const tr of sim.tracks[side].values()) {
        if (tr.kind === 'cruise') continue; // drawn as missiles
        const pos = tr.static ? tr : sim.trackPos(tr);
        const p = this.toScreen(pos.x, pos.y);
        if (p.x < -40 || p.x > this.w + 40 || p.y < -40 || p.y > this.h + 40) continue;
        const stale = sim.t - tr.t > 3 && !tr.static;
        const e = tr.ref;
        c.globalAlpha = stale ? 0.45 : 1;
        const esm = tr.q === 'esm';
        if (e.kind === 'air') {
          this.glyph('air', e.def.role, p.x, p.y, e.hdg, col, { hollow: true, dashed: esm, glow: !esm && !stale });
          if (esm) { c.strokeStyle = col; c.beginPath(); c.arc(p.x, p.y, 10, 0, Math.PI * 2); c.setLineDash([2, 3]); c.stroke(); c.setLineDash([]); }
        } else if (e.dead) { this.wreck(p, e.callsign); c.globalAlpha = 1; continue; }
        else this.glyph(e.kind, null, p.x, p.y, 0, col, {});
        const sel = ui.sel && ui.sel.ref === e;
        if (sel) { c.strokeStyle = COL.white; c.strokeRect(p.x - 11, p.y - 11, 22, 22); }
        if (e.kind === 'air') {
          const n = tr.count || e.count;
          const name = esm ? `${e.def.name}?` : `${e.def.name}${n > 1 ? ' ×' + n : ''}`;
          if (this.view.scale > 0.3 || sel) this.label(`T${tr.num} ${name}`, p.x + 11, p.y - 5, col, 'left', 10);
        } else if (this.view.scale > 0.9 || sel) this.label(e.callsign, p.x + 9, p.y, COL.dim, 'left', 9);
        c.globalAlpha = 1;
      }
    }

    drawFx(sim, ui) {
      const c = this.ctx;
      for (const f of sim.fx) {
        const age = sim.t - f.t;
        const p = this.toScreen(f.x, f.y);
        if (f.kind === 'kill' || f.kind === 'impact') {
          const impact = f.kind === 'impact';
          const col = impact ? COL.hot : COL.white;
          c.save();
          c.globalCompositeOperation = 'lighter';
          // Flash
          if (age < 2.5) {
            const fr = 26 * (1 - age / 2.5) + 6;
            const g = c.createRadialGradient(p.x, p.y, 0, p.x, p.y, fr);
            g.addColorStop(0, impact ? 'rgba(255,210,120,0.95)' : 'rgba(255,255,255,0.95)');
            g.addColorStop(0.4, impact ? 'rgba(255,77,109,0.5)' : 'rgba(120,220,255,0.4)');
            g.addColorStop(1, 'rgba(0,0,0,0)');
            c.fillStyle = g;
            c.beginPath(); c.arc(p.x, p.y, fr, 0, Math.PI * 2); c.fill();
          }
          // Shock rings
          c.strokeStyle = col; c.lineWidth = 1.4;
          for (const k of [1, 0.55]) {
            c.globalAlpha = Math.max(0, 1 - age / 12) * k;
            c.beginPath(); c.arc(p.x, p.y, (4 + age * 3.2) * k + 2, 0, Math.PI * 2); c.stroke();
          }
          // Debris
          c.fillStyle = col;
          for (let i = 0; i < 10; i++) {
            const a = (i / 10) * Math.PI * 2 + (f.x % 1);
            const d = age * (2 + (i % 3)) * 2.2;
            c.globalAlpha = Math.max(0, 1 - age / 6);
            c.fillRect(p.x + Math.cos(a) * d, p.y + Math.sin(a) * d, 1.6, 1.6);
          }
          c.restore();
          if (age < 5) {
            c.globalAlpha = Math.max(0, 1 - age / 5);
            this.label(impact ? 'IMPACT' : 'SPLASH', p.x, p.y - 18 - age * 3, col, 'center', 11);
            c.globalAlpha = 1;
          }
        } else if (f.kind === 'miss' && age < 3) {
          c.strokeStyle = COL.dim; c.globalAlpha = 1 - age / 3;
          c.beginPath(); c.arc(p.x, p.y, 3 + age * 3, 0, Math.PI * 2); c.stroke(); c.globalAlpha = 1;
        } else if (f.kind === 'chaff' && age < 4) {
          c.fillStyle = COL.white;
          for (let i = 0; i < 7; i++) {
            c.globalAlpha = (1 - age / 4) * (0.5 + 0.5 * ((i * 7) % 3) / 2);
            c.fillRect(p.x - 8 + i * 2.6 + age * (i - 3), p.y + 4 + ((i * 5) % 4) + age * 2, 1.5, 1.5);
          }
          c.globalAlpha = 1;
        }
      }
      for (const f of ui.marks || []) {
        const age = performance.now() / 1000 - f.t;
        if (age > 1) continue;
        const p = this.toScreen(f.x, f.y);
        c.strokeStyle = COL.white; c.globalAlpha = 1 - age;
        c.beginPath(); c.arc(p.x, p.y, 4 + age * 14, 0, Math.PI * 2); c.stroke();
        c.beginPath(); c.moveTo(p.x - 5, p.y); c.lineTo(p.x + 5, p.y); c.moveTo(p.x, p.y - 5); c.lineTo(p.x, p.y + 5); c.stroke();
        c.globalAlpha = 1;
      }
    }

    // Scale bar, north arrow and cursor coordinates.
    drawHud(ui) {
      const c = this.ctx;
      const bottom = this.h - 26 - (ui.hudInset || 0);
      const right = this.w - 14;
      const nice = [10, 20, 50, 100, 200, 500, 1000];
      const km = nice.find(k => k * this.view.scale >= 70) || 1000;
      const px = km * this.view.scale;
      c.strokeStyle = COL.gridText; c.lineWidth = 1;
      c.beginPath();
      c.moveTo(right - px, bottom - 4); c.lineTo(right - px, bottom); c.lineTo(right, bottom); c.lineTo(right, bottom - 4);
      c.moveTo(right - px / 2, bottom - 2); c.lineTo(right - px / 2, bottom);
      c.stroke();
      this.label(`${km} KM`, right - px / 2, bottom - 10, COL.gridText, 'center', 9);
      if (this.cursor) {
        const w = this.toWorld(this.cursor.x, this.cursor.y);
        const ll = geo.unproject(w.x, w.y);
        this.label(`${Math.abs(ll.lat).toFixed(2)}°${ll.lat >= 0 ? 'N' : 'S'} ${ll.lon.toFixed(2)}°E`, right, bottom - 24, COL.gridText, 'right', 9);
      }
    }
  }

  KC.Renderer = Renderer;
})(globalThis.KC);
