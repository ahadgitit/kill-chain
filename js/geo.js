// Map geometry. Simplified outlines in [lat, lon], projected to a flat km grid
// (x east, y south) centred on 30N 73E. Accurate to a few km: good enough for a
// theatre-level game, not for navigation.
(function (KC) {
  const LAT0 = 30, LON0 = 73;
  const KX = 111.32 * Math.cos(LAT0 * Math.PI / 180);
  const KY = 110.57;

  function project(lat, lon) {
    return { x: (lon - LON0) * KX, y: -(lat - LAT0) * KY };
  }
  function unproject(x, y) {
    return { lat: LAT0 - y / KY, lon: LON0 + x / KX };
  }
  const line = pts => pts.map(([la, lo]) => project(la, lo));

  // International border + Line of Control, ordered south -> north.
  // Pakistan lies to the west/left of this line.
  const BORDER = [
    [23.68, 68.15], [23.95, 68.60], [24.28, 68.80], [24.25, 69.50], [24.30, 70.10],
    [24.40, 70.60], [24.65, 71.10], [25.10, 70.85], [25.40, 70.60], [25.70, 70.30],
    [26.10, 70.15], [26.60, 70.00], [26.90, 69.50], [27.20, 69.55], [27.60, 69.95],
    [28.00, 70.45], [28.40, 70.95], [28.70, 71.70], [29.00, 72.50], [29.25, 73.05],
    [29.60, 73.40], [29.95, 73.65], [30.20, 73.85], [30.45, 74.05], [30.75, 74.35],
    [31.00, 74.55], [31.25, 74.55], [31.60, 74.57], [31.85, 74.68], [32.05, 75.00],
    [32.30, 75.35], [32.45, 75.15], [32.58, 74.90], [32.72, 74.66], [32.92, 74.62],
  ];
  const LOC = [
    [32.92, 74.62], [33.05, 74.30], [33.25, 74.15], [33.50, 74.08], [33.80, 73.98],
    [34.08, 73.95], [34.30, 73.85], [34.50, 73.90], [34.65, 74.20], [34.72, 74.55],
    [34.72, 74.90], [34.78, 75.30], [34.72, 75.70], [34.65, 76.15], [34.90, 76.55],
    [35.10, 76.95], [35.30, 77.35], [35.50, 77.80],
  ];
  const AFGHAN = [
    [29.60, 66.50], [30.92, 66.45], [31.30, 67.50], [31.55, 68.40], [31.80, 69.10],
    [32.40, 69.40], [33.00, 69.90], [33.90, 69.90], [34.05, 70.40], [34.15, 71.10],
    [34.60, 71.10], [35.00, 71.50], [35.60, 71.60], [36.10, 71.60], [36.50, 72.30],
    [36.90, 73.20], [37.00, 74.60],
  ];
  const CHINA = [
    [37.00, 74.60], [36.85, 75.42], [36.50, 76.00], [36.10, 76.60], [35.70, 77.30], [35.50, 77.80],
  ];
  const COAST = [
    [25.45, 66.40], [25.30, 66.75], [24.85, 66.98], [24.75, 67.20], [24.40, 67.30],
    [24.10, 67.40], [23.90, 67.70], [23.68, 68.15], [23.45, 68.45], [23.22, 68.72],
    [22.95, 69.05], [22.83, 69.35], [22.84, 69.72], [23.03, 70.22], [22.95, 70.45],
    [22.70, 70.20], [22.47, 70.07], [22.40, 69.60],
  ];
  const RIVERS = [
    // Indus
    [[34.15, 77.60], [34.60, 76.30], [35.10, 75.90], [35.30, 75.60], [35.70, 74.60], [35.40, 73.20],
     [34.90, 72.90], [34.10, 72.70], [33.90, 72.25], [32.95, 71.55], [31.80, 71.20], [30.90, 70.90],
     [29.80, 70.75], [28.95, 70.37], [27.70, 68.85], [26.40, 68.00], [25.40, 68.35], [24.75, 67.90],
     [24.00, 67.40]],
    // Jhelum
    [[33.70, 75.15], [34.08, 74.80], [34.20, 74.35], [34.08, 74.00], [34.37, 73.47], [34.10, 73.50],
     [33.15, 73.65], [32.93, 73.73], [32.30, 73.00], [31.15, 72.15]],
    // Chenab
    [[33.15, 75.80], [33.10, 75.20], [32.90, 74.73], [32.67, 74.47], [32.44, 74.12], [31.72, 72.98],
     [31.15, 72.15], [30.40, 71.60], [29.35, 71.00], [28.95, 70.37]],
    // Ravi
    [[32.37, 75.60], [32.05, 75.05], [31.80, 74.60], [31.60, 74.30], [31.00, 73.30], [30.60, 72.40], [30.55, 71.90]],
    // Sutlej (with Beas)
    [[31.40, 76.40], [30.97, 76.50], [31.15, 74.95], [30.95, 74.60], [30.40, 73.90], [29.98, 73.25],
     [29.40, 71.70], [29.35, 71.00]],
    [[32.05, 76.10], [31.55, 75.40], [31.15, 74.95]],
  ];

  const CITIES = [
    ['Lahore', 31.55, 74.34, 'PK'], ['Islamabad', 33.69, 73.05, 'PK'], ['Karachi', 24.86, 67.01, 'PK'],
    ['Multan', 30.20, 71.47, 'PK'], ['Faisalabad', 31.42, 73.08, 'PK'], ['Peshawar', 34.01, 71.58, 'PK'],
    ['Quetta', 30.18, 66.98, 'PK'], ['Hyderabad', 25.40, 68.37, 'PK'], ['Gujranwala', 32.16, 74.19, 'PK'],
    ['Delhi', 28.61, 77.21, 'IN'], ['Amritsar', 31.63, 74.87, 'IN'], ['Jammu', 32.73, 74.86, 'IN'],
    ['Srinagar', 34.08, 74.80, 'IN'], ['Chandigarh', 30.73, 76.78, 'IN'], ['Jaipur', 26.91, 75.79, 'IN'],
    ['Jodhpur', 26.24, 73.02, 'IN'], ['Bikaner', 28.02, 73.31, 'IN'], ['Ludhiana', 30.90, 75.85, 'IN'],
    ['Leh', 34.15, 77.58, 'IN'],
  ];

  const geo = {
    project, unproject,
    border: line(BORDER),
    loc: line(LOC),
    afghan: line(AFGHAN),
    china: line(CHINA),
    coast: line(COAST),
    rivers: RIVERS.map(line),
    cities: CITIES.map(([name, la, lo, side]) => ({ name, side, ...project(la, lo) })),
  };
  geo.frontier = geo.border.concat(geo.loc.slice(1));

  // Which side of the frontier a point is on: 'PK' (west) or 'IN' (east),
  // decided by the nearest frontier segment.
  geo.sideOf = function (x, y) {
    const f = geo.frontier;
    let best = Infinity, cross = 0;
    for (let i = 0; i < f.length - 1; i++) {
      const a = f[i], b = f[i + 1];
      const dx = b.x - a.x, dy = b.y - a.y;
      const L2 = dx * dx + dy * dy;
      let t = ((x - a.x) * dx + (y - a.y) * dy) / L2;
      t = Math.max(0, Math.min(1, t));
      const px = a.x + t * dx, py = a.y + t * dy;
      const d = (x - px) ** 2 + (y - py) ** 2;
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
