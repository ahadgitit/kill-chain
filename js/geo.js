// Map geometry. Natural Earth outlines (js/mapdata.js) projected with a stereographic
// projection centred on 30N 73E: conformal (shapes stay true) and within ~2.5% on
// distance out to 2000 km, so the game can treat projected km as real km.
(function (KC) {
  const R = 6371;
  const D2R = Math.PI / 180;
  const LAT0 = 30 * D2R, LON0 = 73 * D2R;
  const sin0 = Math.sin(LAT0), cos0 = Math.cos(LAT0);

  function project(lat, lon) {
    const p = lat * D2R, l = lon * D2R - LON0;
    const cp = Math.cos(p), sp = Math.sin(p), cl = Math.cos(l);
    const k = 2 * R / (1 + sin0 * sp + cos0 * cp * cl);
    return { x: k * cp * Math.sin(l), y: -k * (cos0 * sp - sin0 * cp * cl) };
  }
  function unproject(x, y) {
    y = -y;
    const rho = Math.hypot(x, y);
    if (rho < 1e-9) return { lat: 30, lon: 73 };
    const c = 2 * Math.atan(rho / (2 * R));
    const sc = Math.sin(c), cc = Math.cos(c);
    const lat = Math.asin(cc * sin0 + y * sc * cos0 / rho);
    const lon = LON0 + Math.atan2(x * sc, rho * cos0 * cc - y * sin0 * sc);
    return { lat: lat / D2R, lon: lon / D2R };
  }
  // Flat [lon, lat, ...] -> Float32Array [x, y, ...]
  function projFlat(a) {
    const out = new Float32Array(a.length);
    for (let i = 0; i < a.length; i += 2) {
      const p = project(a[i + 1], a[i]);
      out[i] = p.x; out[i + 1] = p.y;
    }
    return out;
  }
  const toPts = f => { const o = []; for (let i = 0; i < f.length; i += 2) o.push({ x: f[i], y: f[i + 1] }); return o; };

  const M = KC.MAPDATA;
  const frontierFlat = projFlat(M.frontier);
  const geo = {
    project, unproject, projFlat,
    bbox: M.bbox,
    countries: M.countries.map(c => ({ name: c.n, rings: c.r.map(projFlat) })),
    borders: M.borders.map(projFlat),
    coasts: M.coasts.map(projFlat),
    rivers: M.rivers.map(projFlat),
    lakes: M.lakes.map(projFlat),
    // International border / working boundary, then the Line of Control.
    ib: frontierFlat.slice(0, (M.locStart + 1) * 2),
    loc: frontierFlat.slice(M.locStart * 2),
    // Coarser copy for game logic (side-of-border tests run often).
    frontier: toPts(projFlat(M.frontierCoarse)),
  };

  const CITIES = [
    // Pakistan
    ['Lahore', 31.55, 74.34, 'PK', 1], ['Islamabad', 33.69, 73.05, 'PK', 1], ['Karachi', 24.86, 67.01, 'PK', 1],
    ['Multan', 30.20, 71.47, 'PK', 2], ['Faisalabad', 31.42, 73.08, 'PK', 2], ['Peshawar', 34.01, 71.58, 'PK', 2],
    ['Quetta', 30.18, 66.98, 'PK', 2], ['Hyderabad', 25.40, 68.37, 'PK', 3], ['Gujranwala', 32.16, 74.19, 'PK', 3],
    ['Sialkot', 32.49, 74.53, 'PK', 3], ['Bahawalpur', 29.40, 71.68, 'PK', 3], ['Sukkur', 27.70, 68.86, 'PK', 3],
    ['Gwadar', 25.13, 62.32, 'PK', 3], ['Gilgit', 35.92, 74.31, 'PK', 3],
    // India
    ['New Delhi', 28.61, 77.21, 'IN', 1], ['Mumbai', 19.08, 72.88, 'IN', 1], ['Kolkata', 22.57, 88.36, 'IN', 1],
    ['Chennai', 13.08, 80.27, 'IN', 1], ['Bengaluru', 12.97, 77.59, 'IN', 1], ['Hyderabad', 17.39, 78.49, 'IN', 2],
    ['Ahmedabad', 23.02, 72.57, 'IN', 2], ['Pune', 18.52, 73.86, 'IN', 2], ['Jaipur', 26.91, 75.79, 'IN', 2],
    ['Lucknow', 26.85, 80.95, 'IN', 2], ['Nagpur', 21.15, 79.09, 'IN', 2], ['Bhopal', 23.26, 77.41, 'IN', 3],
    ['Amritsar', 31.63, 74.87, 'IN', 3], ['Jammu', 32.73, 74.86, 'IN', 3], ['Srinagar', 34.08, 74.80, 'IN', 2],
    ['Chandigarh', 30.73, 76.78, 'IN', 3], ['Jodhpur', 26.24, 73.02, 'IN', 3], ['Bikaner', 28.02, 73.31, 'IN', 3],
    ['Ludhiana', 30.90, 75.85, 'IN', 3], ['Leh', 34.15, 77.58, 'IN', 3], ['Guwahati', 26.14, 91.74, 'IN', 2],
    ['Patna', 25.59, 85.14, 'IN', 3], ['Kochi', 9.93, 76.27, 'IN', 3], ['Visakhapatnam', 17.69, 83.22, 'IN', 3],
    ['Bhubaneswar', 20.30, 85.82, 'IN', 3], ['Goa', 15.50, 73.83, 'IN', 3], ['Thiruvananthapuram', 8.52, 76.94, 'IN', 3],
    // Neighbours
    ['Kabul', 34.53, 69.17, 'X', 1], ['Kandahar', 31.61, 65.71, 'X', 3], ['Kathmandu', 27.72, 85.32, 'X', 1],
    ['Dhaka', 23.81, 90.41, 'X', 1], ['Thimphu', 27.47, 89.64, 'X', 2], ['Colombo', 6.93, 79.86, 'X', 1],
    ['Lhasa', 29.65, 91.11, 'X', 2], ['Kashgar', 39.47, 75.99, 'X', 2], ['Zahedan', 29.50, 60.86, 'X', 3],
    ['Dushanbe', 38.56, 68.79, 'X', 2], ['Muscat', 23.59, 58.41, 'X', 2], ['Yangon', 16.84, 96.17, 'X', 2],
    ['Mandalay', 21.96, 96.09, 'X', 3], ['Male', 4.18, 73.51, 'X', 3],
  ];
  geo.cities = CITIES.map(([name, la, lo, side, rank]) => ({ name, side, rank, ...project(la, lo) }));

  const LABELS = [
    ['PAKISTAN', 28.6, 68.6, 'PK', 1], ['INDIA', 22.5, 78.5, 'IN', 1],
    ['AFGHANISTAN', 33.6, 65.6, 'X', 2], ['IRAN', 31.0, 57.5, 'X', 2], ['CHINA', 34.5, 86.0, 'X', 2],
    ['NEPAL', 28.25, 83.9, 'X', 3], ['BHUTAN', 27.7, 90.4, 'X', 3], ['BANGLADESH', 24.0, 90.2, 'X', 3],
    ['MYANMAR', 21.5, 95.6, 'X', 3], ['SRI LANKA', 7.6, 80.7, 'X', 3], ['TAJIKISTAN', 38.7, 71.0, 'X', 3],
    ['OMAN', 21.0, 56.8, 'X', 3],
    ['ARABIAN SEA', 16.0, 65.0, 'SEA', 2], ['BAY OF BENGAL', 15.0, 88.0, 'SEA', 2], ['INDIAN OCEAN', 4.0, 80.0, 'SEA', 2],
    ['TIBETAN PLATEAU', 32.4, 84.0, 'TER', 3], ['THAR DESERT', 27.0, 71.6, 'TER', 3], ['HIMALAYA', 30.2, 80.6, 'TER', 3],
    ['HINDU KUSH', 35.6, 70.6, 'TER', 3], ['KARAKORAM', 35.9, 76.4, 'TER', 3], ['DECCAN PLATEAU', 17.0, 77.0, 'TER', 3],
    ['LINE OF CONTROL', 34.2, 73.55, 'LOC', 4],
  ];
  geo.labels = LABELS.map(([text, la, lo, kind, rank]) => ({ text, kind, rank, ...project(la, lo) }));

  // Which side of the India-Pakistan frontier a point is on: 'PK' (west) or 'IN' (east),
  // decided by the nearest frontier segment.
  geo.sideOf = function (x, y) {
    const f = geo.frontier;
    let best = Infinity, cross = 0;
    for (let i = 0; i < f.length - 1; i++) {
      const a = f[i], b = f[i + 1];
      const dx = b.x - a.x, dy = b.y - a.y;
      let t = ((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy);
      t = Math.max(0, Math.min(1, t));
      const d = (x - a.x - t * dx) ** 2 + (y - a.y - t * dy) ** 2;
      if (d < best) { best = d; cross = dx * (y - a.y) - dy * (x - a.x); }
    }
    return cross < 0 ? 'PK' : 'IN';
  };
  // Distance (km) from a point to the frontier.
  geo.frontierDist = function (x, y) {
    const f = geo.frontier;
    let best = Infinity;
    for (let i = 0; i < f.length - 1; i++) {
      const a = f[i], b = f[i + 1];
      const dx = b.x - a.x, dy = b.y - a.y;
      let t = ((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy);
      t = Math.max(0, Math.min(1, t));
      best = Math.min(best, Math.hypot(x - a.x - t * dx, y - a.y - t * dy));
    }
    return best;
  };

  KC.geo = geo;
})(globalThis.KC = globalThis.KC || {});
