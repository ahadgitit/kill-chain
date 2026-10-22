// Platform, weapon and scenario data.
// Figures are simplified estimates from open sources. Real performance is
// classified, varies with altitude/aspect, and is often disputed.
(function (KC) {
  const P = KC.geo.project;

  // radar: detection range (km) vs a 5 m2 target. rcs: m2 (frontal, rough).
  // ew: self-protection jamming quality 0..1. speed/dash: km/s. turn: deg/s.
  // linked: on its side's tactical data-link network.
  const AIRCRAFT = {
    RAFALE: { name: 'Rafale', role: 'fighter', radar: 150, rcs: 1.5, ew: 0.35, speed: 0.26, dash: 0.42, turn: 10, linked: true, value: 15,
      note: 'RBE2-AA AESA radar, SPECTRA EW suite. Meteor ramjet BVR missile, SCALP-EG cruise missile.' },
    SU30: { name: 'Su-30MKI', role: 'fighter', radar: 160, rcs: 12, ew: 0.2, speed: 0.26, dash: 0.42, turn: 8, linked: true, value: 12,
      note: 'Big N011M Bars PESA radar but a large radar signature. R-77 BVR missiles.' },
    MIG29: { name: 'MiG-29UPG', role: 'fighter', radar: 110, rcs: 6, ew: 0.15, speed: 0.26, dash: 0.43, turn: 10, linked: true, value: 9,
      note: 'Zhuk-ME radar, R-77 missiles. Agile but shorter reach.' },
    M2000: { name: 'Mirage 2000I', role: 'fighter', radar: 110, rcs: 2.5, ew: 0.25, speed: 0.25, dash: 0.40, turn: 10, linked: true, value: 10,
      note: 'RDY-2 radar, MICA missiles, SPICE-2000 precision glide bombs.' },
    J10C: { name: 'J-10CE', role: 'fighter', radar: 170, rcs: 3, ew: 0.25, speed: 0.26, dash: 0.42, turn: 10, linked: true, value: 12,
      note: 'KLJ-7A AESA radar, PL-15E long-range missile with two-way data link (third-party targeting).' },
    JF17B3: { name: 'JF-17 Blk III', role: 'fighter', radar: 150, rcs: 3.5, ew: 0.2, speed: 0.25, dash: 0.38, turn: 9, linked: true, value: 8,
      note: 'KLJ-7A AESA radar, PL-15E capable, Link-17 data link.' },
    JF17B2: { name: 'JF-17 Blk II', role: 'fighter', radar: 105, rcs: 3.5, ew: 0.15, speed: 0.25, dash: 0.38, turn: 9, linked: true, value: 8,
      note: 'KLJ-7 radar, SD-10A missiles, Link-17 data link.' },
    F16: { name: 'F-16 Blk 52', role: 'fighter', radar: 110, rcs: 3, ew: 0.2, speed: 0.26, dash: 0.42, turn: 11, linked: false, value: 12,
      note: 'APG-68(V)9 radar, AIM-120C-5. Not on the national Link-17 net in this sim: needs its own radar to shoot.' },
    NETRA: { name: 'Netra AEW&C', role: 'aew', radar: 250, rcs: 60, ew: 0.1, speed: 0.15, dash: 0.2, turn: 3, linked: true, value: 30,
      note: 'Embraer 145 with DRDO AESA radar. Builds the air picture and shares it over the link.' },
    PHALCON: { name: 'A-50EI Phalcon', role: 'aew', radar: 380, rcs: 80, ew: 0.1, speed: 0.15, dash: 0.2, turn: 3, linked: true, value: 30,
      note: 'Il-76 with EL/W-2090 radar. Very long range, kept far from the border.' },
    ERIEYE: { name: 'Saab 2000 Erieye', role: 'aew', radar: 330, rcs: 50, ew: 0.1, speed: 0.15, dash: 0.2, turn: 3, linked: true, value: 30,
      note: 'Erieye AESA radar. Feeds the Link-17 picture to fighters flying radar-silent.' },
    ZDK03: { name: 'ZDK-03', role: 'aew', radar: 420, rcs: 80, ew: 0.1, speed: 0.15, dash: 0.2, turn: 3, linked: true, value: 30,
      note: 'Karakoram Eagle (Y-8 based) AEW&C with a rotodome radar.' },
  };

  // kind: aam (beyond visual range), srm (short range), sam, cruise.
  // range: kinematic distance (km) flown before the missile is too slow to manoeuvre.
  //   Useful launch range is much shorter, especially against a target that turns away.
  // vMax km/s, burn s, seeker: active seeker range km, tp: can be guided by
  // third-party data-link tracks (e.g. from an AEW&C), not just the shooter.
  const WEAPONS = {
    PL15: { name: 'PL-15E', kind: 'aam', range: 150, vMax: 1.5, burn: 12, seeker: 25, pk: 0.6, tp: true },
    METEOR: { name: 'Meteor', kind: 'aam', range: 170, vMax: 1.2, burn: 70, seeker: 20, pk: 0.7, tp: false },
    AIM120: { name: 'AIM-120C-5', kind: 'aam', range: 100, vMax: 1.3, burn: 8, seeker: 16, pk: 0.55, tp: false },
    R77: { name: 'R-77', kind: 'aam', range: 80, vMax: 1.2, burn: 7, seeker: 15, pk: 0.45, tp: false },
    MICA: { name: 'MICA-EM', kind: 'aam', range: 70, vMax: 1.1, burn: 5, seeker: 12, pk: 0.55, tp: false },
    SD10: { name: 'SD-10A', kind: 'aam', range: 75, vMax: 1.2, burn: 7, seeker: 15, pk: 0.45, tp: false },
    PL10: { name: 'PL-10', kind: 'srm', range: 14, vMax: 0.9, burn: 4, seeker: 14, pk: 0.7 },
    PL5: { name: 'PL-5EII', kind: 'srm', range: 12, vMax: 0.85, burn: 4, seeker: 12, pk: 0.55 },
    AIM9X: { name: 'AIM-9X', kind: 'srm', range: 14, vMax: 0.9, burn: 4, seeker: 14, pk: 0.7 },
    R73: { name: 'R-73', kind: 'srm', range: 12, vMax: 0.85, burn: 4, seeker: 12, pk: 0.6 },
    MICAIR: { name: 'MICA-IR', kind: 'srm', range: 14, vMax: 0.9, burn: 4, seeker: 14, pk: 0.65 },
    S400: { name: '48N6 (S-400)', kind: 'sam', range: 250, vMax: 2.0, burn: 12, seeker: 20, pk: 0.55, tp: true },
    HQ9: { name: 'HQ-9/P', kind: 'sam', range: 130, vMax: 1.4, burn: 10, seeker: 15, pk: 0.5, tp: true },
    MRSAM: { name: 'MRSAM', kind: 'sam', range: 90, vMax: 1.1, burn: 8, seeker: 15, pk: 0.55, tp: true },
    AKASH: { name: 'Akash', kind: 'sam', range: 30, vMax: 0.85, burn: 5, seeker: 10, pk: 0.5, tp: true },
    LY80: { name: 'LY-80', kind: 'sam', range: 50, vMax: 1.0, burn: 6, seeker: 10, pk: 0.5, tp: true },
    SCALP: { name: 'SCALP-EG', kind: 'cruise', range: 280, speed: 0.27, rcs: 0.1, low: true },
    SPICE: { name: 'SPICE-2000', kind: 'cruise', range: 60, speed: 0.22, rcs: 0.2, low: false },
  };
  const V_MIN = 0.35;
  for (const w of Object.values(WEAPONS)) {
    if (w.kind === 'cruise') continue;
    const burnDist = w.vMax * w.burn;
    const coast = Math.max(5, w.range - burnDist);
    w.drag = (w.vMax - V_MIN) / coast;                       // v' = -drag * v after burn-out
    w.tMax = w.burn + Math.log(w.vMax / V_MIN) / w.drag;
  }

  const SAMS = {
    S400: { name: 'S-400', radar: 250, weapon: 'S400', ammo: 8 },
    HQ9: { name: 'HQ-9/P', radar: 150, weapon: 'HQ9', ammo: 8 },
    MRSAM: { name: 'MRSAM', radar: 100, weapon: 'MRSAM', ammo: 8 },
    AKASH: { name: 'Akash', radar: 60, weapon: 'AKASH', ammo: 6 },
    LY80: { name: 'LY-80', radar: 80, weapon: 'LY80', ammo: 6 },
  };

  const LOADOUTS = {
    RAFALE_STRIKE: { SCALP: 4, MICA: 4, MICAIR: 2 },
    RAFALE_CAP: { METEOR: 4, MICA: 4, MICAIR: 4 },
    SU30: { R77: 8, R73: 4 },
    MIG29: { R77: 6, R73: 4 },
    M2000_STRIKE: { SPICE: 4, MICA: 4, MICAIR: 4 },
    J10C: { PL15: 6, PL10: 4 },
    JF17B3: { PL15: 4, PL5: 4 },
    JF17B2: { SD10: 4, PL5: 4 },
    F16: { AIM120: 6, AIM9X: 4 },
    AEW: {},
  };

  const at = (la, lo) => P(la, lo);

  const BASES = [
    // India
    ['AMBALA', 'IN', 30.37, 76.82], ['HALWARA', 'IN', 30.75, 75.63], ['ADAMPUR', 'IN', 31.43, 75.76],
    ['PATHANKOT', 'IN', 32.23, 75.63], ['SRINAGAR', 'IN', 33.99, 74.77], ['BATHINDA', 'IN', 30.27, 74.76],
    ['SIRSA', 'IN', 29.56, 75.01], ['SURATGARH', 'IN', 29.39, 73.90], ['JODHPUR', 'IN', 26.25, 73.05],
    ['AGRA', 'IN', 27.16, 77.96], ['GWALIOR', 'IN', 26.29, 78.23], ['UTTARLAI', 'IN', 25.81, 71.48],
    ['BHUJ', 'IN', 23.29, 69.67],
    // Pakistan
    ['MINHAS', 'PK', 33.87, 72.40], ['MUSHAF', 'PK', 32.05, 72.67], ['RAFIQUI', 'PK', 30.76, 72.28],
    ['PESHAWAR', 'PK', 34.01, 71.51], ['NUR KHAN', 'PK', 33.62, 73.10], ['MURID', 'PK', 32.91, 72.79],
    ['JACOBABAD', 'PK', 28.28, 68.45], ['SKARDU', 'PK', 35.34, 75.54], ['MASROOR', 'PK', 24.89, 66.94],
    ['RAHIM YAR KHAN', 'PK', 28.38, 70.28], ['SUKKUR', 'PK', 27.72, 68.79],
  ].map(([id, side, la, lo]) => ({ id, side, ...at(la, lo) }));

  // Strike targets (alleged militant infrastructure sites named in public reporting).
  const SITES = [
    ['BAHAWALPUR', 29.38, 71.68], ['MURIDKE', 31.80, 74.25], ['SIALKOT', 32.52, 74.56],
    ['KOTLI', 33.50, 73.90], ['MUZAFFARABAD', 34.37, 73.47],
  ].map(([id, la, lo]) => ({ id, side: 'PK', ...at(la, lo) }));

  // ---- Scenario: night of 6-7 May 2025 ----
  const f = (side, callsign, type, count, loadout, base, la, lo, order, extra) =>
    ({ side, callsign, type, count, loadout, base, pos: at(la, lo), order, ...extra });
  const orbit = (la, lo, r) => ({ type: 'orbit', ...at(la, lo), r: r || 15 });

  const SCENARIO = {
    name: 'Night of 7 May',
    start: { h: 19, m: 25 }, // Zulu (00:55 IST / 00:25 PKT)
    duration: 45 * 60,
    flights: [
      // India
      f('IN', 'EYE-1', 'NETRA', 1, 'AEW', 'BATHINDA', 30.55, 75.15, orbit(30.55, 75.15, 25)),
      f('IN', 'EYE-2', 'PHALCON', 1, 'AEW', 'AGRA', 29.60, 76.90, orbit(29.60, 76.90, 25)),
      f('IN', 'VAJRA-1', 'RAFALE', 2, 'RAFALE_STRIKE', 'AMBALA', 30.85, 76.35,
        { type: 'strike', list: [{ site: 'MURIDKE', n: 2 }, { site: 'MUZAFFARABAD', n: 2 }] }),
      f('IN', 'VAJRA-2', 'RAFALE', 2, 'RAFALE_STRIKE', 'AMBALA', 29.70, 75.30,
        { type: 'strike', list: [{ site: 'BAHAWALPUR', n: 4 }] }),
      f('IN', 'TRISHUL-1', 'M2000', 2, 'M2000_STRIKE', 'PATHANKOT', 32.30, 75.75,
        { type: 'strike', list: [{ site: 'KOTLI', n: 2 }, { site: 'SIALKOT', n: 2 }] }),
      f('IN', 'RUDRA-1', 'RAFALE', 2, 'RAFALE_CAP', 'AMBALA', 32.35, 75.30, orbit(32.35, 75.30)),
      f('IN', 'SHAKTI-1', 'SU30', 2, 'SU30', 'HALWARA', 31.35, 75.15, orbit(31.35, 75.15)),
      f('IN', 'SHAKTI-2', 'SU30', 2, 'SU30', 'SIRSA', 29.90, 74.55, orbit(29.90, 74.55)),
      f('IN', 'BAAZ-1', 'MIG29', 2, 'MIG29', 'SRINAGAR', 33.75, 74.85, orbit(33.75, 74.85)),
      // Pakistan
      f('PK', 'SKYEYE-1', 'ERIEYE', 1, 'AEW', 'RAFIQUI', 31.25, 73.00, orbit(31.25, 73.00, 25)),
      f('PK', 'SKYEYE-2', 'ZDK03', 1, 'AEW', 'MINHAS', 32.75, 72.60, orbit(32.75, 72.60, 25)),
      f('PK', 'COBRA-1', 'J10C', 2, 'J10C', 'MINHAS', 31.75, 73.95, orbit(31.75, 73.95)),
      f('PK', 'COBRA-2', 'J10C', 2, 'J10C', 'MINHAS', 32.85, 73.95, orbit(32.85, 73.95)),
      f('PK', 'THUNDER-1', 'JF17B3', 2, 'JF17B3', 'RAFIQUI', 30.40, 73.15, orbit(30.40, 73.15)),
      f('PK', 'THUNDER-2', 'JF17B2', 2, 'JF17B2', 'RAFIQUI', 29.30, 72.20, orbit(29.30, 72.20)),
      f('PK', 'FALCON-1', 'F16', 2, 'F16', 'MUSHAF', 33.60, 73.55, orbit(33.60, 73.55)),
    ],
    reserves: [
      f('IN', 'RUDRA-2', 'RAFALE', 2, 'RAFALE_CAP', 'AMBALA'),
      f('IN', 'SHAKTI-3', 'SU30', 2, 'SU30', 'HALWARA'),
      f('IN', 'BAAZ-2', 'MIG29', 2, 'MIG29', 'ADAMPUR'),
      f('IN', 'SHAKTI-4', 'SU30', 2, 'SU30', 'SIRSA'),
      f('PK', 'COBRA-3', 'J10C', 2, 'J10C', 'MINHAS'),
      f('PK', 'THUNDER-3', 'JF17B3', 2, 'JF17B3', 'RAFIQUI'),
      f('PK', 'FALCON-2', 'F16', 2, 'F16', 'MUSHAF'),
      f('PK', 'THUNDER-4', 'JF17B2', 2, 'JF17B2', 'PESHAWAR'),
    ],
    sams: [
      ['IN', 'SUDARSHAN', 'S400', 31.30, 75.60], ['IN', 'BARAK-1', 'MRSAM', 31.60, 75.05],
      ['IN', 'AKASH-1', 'AKASH', 32.20, 75.60], ['IN', 'AKASH-2', 'AKASH', 33.95, 74.80],
      ['PK', 'HQ9-LHR', 'HQ9', 31.45, 74.15], ['PK', 'HQ9-ISB', 'HQ9', 33.55, 73.15],
      ['PK', 'LY80-SKT', 'LY80', 32.45, 74.40], ['PK', 'LY80-BWP', 'LY80', 29.42, 71.75],
      ['PK', 'LY80-MZD', 'LY80', 34.30, 73.45],
    ].map(([side, callsign, type, la, lo]) => ({ side, callsign, type, pos: at(la, lo) })),
    radars: [
      ['IN', 'GR-AMRITSAR', 31.55, 75.15, 230], ['IN', 'GR-SURATGARH', 29.40, 73.95, 230],
      ['IN', 'GR-SRINAGAR', 33.95, 74.95, 200], ['IN', 'GR-JAISALMER', 26.90, 71.00, 230],
      ['PK', 'GR-LAHORE', 31.35, 73.95, 230], ['PK', 'GR-SAKESAR', 32.53, 71.94, 300],
      ['PK', 'GR-BAHAWALPUR', 29.45, 71.90, 230], ['PK', 'GR-TOPA', 33.90, 73.40, 230],
    ].map(([side, callsign, la, lo, range]) => ({ side, callsign, range, pos: at(la, lo) })),
  };

  Object.assign(KC, { AIRCRAFT, WEAPONS, SAMS, LOADOUTS, BASES, SITES, SCENARIO, V_MIN });
})(globalThis.KC);
