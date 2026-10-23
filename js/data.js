// Platform, weapon, base and campaign data.
// Figures are simplified estimates from open sources. Real performance is
// classified, varies with altitude/aspect, and is often disputed. Basing is
// simplified to one aircraft type per base for playability.
(function (KC) {
  const P = KC.geo.project;
  const at = (la, lo) => P(la, lo);

  // radar: detection range (km) vs a 5 m2 target. rcs: m2 (frontal, rough).
  // ew: self-protection jamming quality 0..1. speed/dash: km/s. turn: deg/s.
  // linked: on its side's tactical data-link network. fuel: seconds at cruise
  // (dash burns 2.5x). turnaround: seconds to refuel and re-arm after landing.
  const AIRCRAFT = {
    RAFALE: { name: 'Rafale', role: 'fighter', radar: 150, rcs: 1.5, ew: 0.35, speed: 0.26, dash: 0.42, turn: 10, linked: true, value: 15, fuel: 7500, turnaround: 480,
      note: 'RBE2-AA AESA radar, SPECTRA EW suite. Meteor ramjet BVR missile, SCALP-EG cruise missile, HAMMER precision bombs.' },
    SU30: { name: 'Su-30MKI', role: 'fighter', radar: 160, rcs: 12, ew: 0.2, speed: 0.26, dash: 0.42, turn: 8, linked: true, value: 12, fuel: 9000, turnaround: 540,
      note: 'Long legs and a big N011M Bars radar, but a large radar signature. R-77 missiles or one BrahMos-A cruise missile.' },
    MIG29: { name: 'MiG-29UPG', role: 'fighter', radar: 110, rcs: 6, ew: 0.15, speed: 0.26, dash: 0.43, turn: 10, linked: true, value: 9, fuel: 5400, turnaround: 420,
      note: 'Zhuk-ME radar, R-77 missiles. Agile, short-ranged interceptor.' },
    M2000: { name: 'Mirage 2000I', role: 'fighter', radar: 110, rcs: 2.5, ew: 0.25, speed: 0.25, dash: 0.40, turn: 10, linked: true, value: 10, fuel: 6300, turnaround: 420,
      note: 'RDY-2 radar, MICA missiles, SPICE-2000 precision glide bombs (short range: must fly close).' },
    J10C: { name: 'J-10CE', role: 'fighter', radar: 170, rcs: 3, ew: 0.25, speed: 0.26, dash: 0.42, turn: 10, linked: true, value: 12, fuel: 6300, turnaround: 420,
      note: 'KLJ-7A AESA radar, PL-15E long-range missile with two-way data link (third-party targeting).' },
    JF17B3: { name: 'JF-17 Blk III', role: 'fighter', radar: 150, rcs: 3.5, ew: 0.2, speed: 0.25, dash: 0.38, turn: 9, linked: true, value: 8, fuel: 5800, turnaround: 400,
      note: 'KLJ-7A AESA radar, PL-15E capable, Link-17. Can carry the CM-400AKG supersonic strike missile.' },
    JF17B2: { name: 'JF-17 Blk II', role: 'fighter', radar: 105, rcs: 3.5, ew: 0.15, speed: 0.25, dash: 0.38, turn: 9, linked: true, value: 8, fuel: 5800, turnaround: 400,
      note: 'KLJ-7 radar, SD-10A missiles, Link-17. Can carry the CM-400AKG.' },
    F16: { name: 'F-16 Blk 52', role: 'fighter', radar: 110, rcs: 3, ew: 0.2, speed: 0.26, dash: 0.42, turn: 11, linked: false, value: 12, fuel: 6300, turnaround: 420,
      note: 'APG-68(V)9 radar, AIM-120C-5, H-4 stand-off glide weapon. Not on the national Link-17 net in this sim.' },
    NETRA: { name: 'Netra AEW&C', role: 'aew', radar: 250, rcs: 60, ew: 0.1, speed: 0.17, dash: 0.2, turn: 3, linked: true, value: 30, fuel: 22000, turnaround: 900,
      note: 'Embraer 145 with DRDO AESA radar. Builds the air picture and shares it over the link.' },
    PHALCON: { name: 'A-50EI Phalcon', role: 'aew', radar: 380, rcs: 80, ew: 0.1, speed: 0.2, dash: 0.22, turn: 3, linked: true, value: 30, fuel: 26000, turnaround: 900,
      note: 'Il-76 with EL/W-2090 radar. Very long range. Keep it far from the border.' },
    ERIEYE: { name: 'Saab 2000 Erieye', role: 'aew', radar: 330, rcs: 50, ew: 0.1, speed: 0.17, dash: 0.2, turn: 3, linked: true, value: 30, fuel: 22000, turnaround: 900,
      note: 'Erieye AESA radar. Feeds the Link-17 picture to fighters flying radar-silent.' },
    ZDK03: { name: 'ZDK-03', role: 'aew', radar: 420, rcs: 80, ew: 0.1, speed: 0.17, dash: 0.2, turn: 3, linked: true, value: 30, fuel: 26000, turnaround: 900,
      note: 'Karakoram Eagle (Y-8 based) AEW&C with a rotodome radar. Covers the southern sector from Karachi.' },
    // Drones: slow, small, low. They don't count against the airborne limit and never land.
    HAROP: { name: 'Harop', role: 'drone', mission: 'sead', radar: 0, rcs: 0.15, low: true, ew: 0, speed: 0.11, dash: 0.13, turn: 6, linked: false, value: 1, fuel: 14000, turnaround: 0,
      note: 'Loitering munition. Hunts enemy radars by their emissions, then dives on them. A SAM that switches its radar off is safe from it.' },
    SWARM: { name: 'Loitering drone', role: 'drone', mission: 'attack', radar: 0, rcs: 0.2, low: true, ew: 0, speed: 0.08, dash: 0.09, turn: 6, linked: false, value: 1, fuel: 9000, turnaround: 0,
      note: 'Cheap one-way attack drones, flown in swarms. Weak alone; in numbers they soak up expensive SAM missiles.' },
  };

  // kind: aam (beyond visual range), srm (short range), sam, cruise (incl. glide weapons).
  // range: kinematic distance (km) flown before the missile is too slow to manoeuvre.
  //   Useful launch range is much shorter, especially against a target that turns away.
  // vMax km/s, burn s, seeker: active seeker range km, tp: can be guided by
  // third-party data-link tracks (e.g. from an AEW&C), not just the shooter.
  // Cruise weapons: dmg = damage to a base per hit (runway closes at 60). Stand-off ranges are
  // deliberately about half the real figures so strike jets must come within reach of defenders.
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
    SCALP: { name: 'SCALP-EG', kind: 'cruise', range: 140, speed: 0.27, rcs: 0.1, low: true, dmg: 35 },
    BRAHMOS: { name: 'BrahMos-A', kind: 'cruise', range: 200, speed: 0.85, rcs: 0.3, low: false, dmg: 45 },
    HAMMER: { name: 'HAMMER', kind: 'cruise', range: 70, speed: 0.3, rcs: 0.1, low: false, dmg: 20 },
    SPICE: { name: 'SPICE-2000', kind: 'cruise', range: 60, speed: 0.22, rcs: 0.2, low: false, dmg: 30 },
    CM400: { name: 'CM-400AKG', kind: 'cruise', range: 120, speed: 1.0, rcs: 0.3, low: false, dmg: 40 },
    H4: { name: 'H-4 SOW', kind: 'cruise', range: 60, speed: 0.22, rcs: 0.15, low: false, dmg: 30 },
    LS6: { name: 'LS-6 glide bomb', kind: 'cruise', range: 60, speed: 0.2, rcs: 0.15, low: false, dmg: 20 },
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

  // Loadouts, per jet, set by the mission. CHASE and ESCORT fly AIR: 4 air-to-air
  // missiles, fired 2 at a time. GROUND ATTACK flies STRIKE: 3 air-to-surface weapons
  // (3 hits destroy a target) plus 2 short-range missiles for self-defence.
  const PRESETS = {
    RAFALE: { AIR: { METEOR: 2, MICA: 2 }, STRIKE: { SCALP: 3, MICAIR: 2 } },
    SU30: { AIR: { R77: 4 }, STRIKE: { BRAHMOS: 3, R73: 2 } },
    MIG29: { AIR: { R77: 2, R73: 2 } },
    M2000: { AIR: { MICA: 2, MICAIR: 2 }, STRIKE: { SPICE: 3, MICAIR: 2 } },
    J10C: { AIR: { PL15: 2, PL10: 2 }, STRIKE: { LS6: 3, PL10: 2 } },
    JF17B3: { AIR: { PL15: 2, PL5: 2 }, STRIKE: { CM400: 3, PL5: 2 } },
    JF17B2: { AIR: { SD10: 2, PL5: 2 }, STRIKE: { CM400: 3, PL5: 2 } },
    F16: { AIR: { AIM120: 2, AIM9X: 2 }, STRIKE: { H4: 3, AIM9X: 2 } },
    NETRA: { AEW: {} }, PHALCON: { AEW: {} }, ERIEYE: { AEW: {} }, ZDK03: { AEW: {} },
    HAROP: { SEAD: {} }, SWARM: { ATTACK: {} },
  };
  const PRESET_INFO = {
    AIR: 'Air-to-air: maximum missiles for fighting other jets.',
    STRIKE: 'Ground attack: 3 air-to-surface weapons and 2 short-range missiles for self-defence.',
    AEW: 'Airborne early warning: no weapons.',
    SEAD: 'Hunts enemy radars.', ATTACK: 'One-way attack on a ground target.',
  };

  // National stocks of the decisive weapons only (everything else is plentiful).
  // Jets draw from these when they take off; unused weapons go back when they land.
  // In a campaign, stocks carry over between nights.
  const STOCKS = {
    IN: { METEOR: 40, SCALP: 36, BRAHMOS: 24 },
    PK: { PL15: 48, CM400: 24 },
  };

  // Maximum manned aircraft airborne at once (sortie generation / airspace control).
  const AIR_CAP = { IN: 24, PK: 20 };

  // Airbases. type/count: the aircraft based there at the start of the campaign.
  // preset: default loadout. Bases without a type are divert fields (and targets).
  const BASES = [
    // India
    ['AMBALA', 'IN', 30.37, 76.82, 'RAFALE', 12, 'STRIKE'],
    ['HALWARA', 'IN', 30.75, 75.63, 'SU30', 12, 'AIR'],
    ['SIRSA', 'IN', 29.56, 75.01, 'SU30', 8, 'AIR'],
    ['JODHPUR', 'IN', 26.25, 73.05, 'SU30', 8, 'AIR'],
    ['ADAMPUR', 'IN', 31.43, 75.76, 'MIG29', 10, 'AIR'],
    ['SRINAGAR', 'IN', 33.99, 74.77, 'MIG29', 6, 'AIR'],
    ['PATHANKOT', 'IN', 32.23, 75.63, 'M2000', 10, 'STRIKE'],
    ['BATHINDA', 'IN', 30.27, 74.76, 'NETRA', 2, 'AEW'],
    ['AGRA', 'IN', 27.16, 77.96, 'PHALCON', 2, 'AEW'],
    ['AMRITSAR', 'IN', 31.71, 74.80, 'HAROP', 18, 'SEAD'],
    ['UDHAMPUR', 'IN', 32.93, 75.13], ['SURATGARH', 'IN', 29.39, 73.90], ['UTTARLAI', 'IN', 25.81, 71.48],
    ['BHUJ', 'IN', 23.29, 69.67], ['GWALIOR', 'IN', 26.29, 78.23],
    // Pakistan
    ['MINHAS', 'PK', 33.87, 72.40, 'J10C', 12, 'AIR'],
    ['RAFIQUI', 'PK', 30.76, 72.28, 'JF17B3', 12, 'AIR'],
    ['PESHAWAR', 'PK', 34.01, 71.51, 'JF17B2', 12, 'AIR'],
    ['MUSHAF', 'PK', 32.05, 72.67, 'F16', 10, 'AIR'],
    ['JACOBABAD', 'PK', 28.28, 68.45, 'F16', 8, 'AIR'],
    ['NUR KHAN', 'PK', 33.62, 73.10, 'ERIEYE', 3, 'AEW'],
    ['MASROOR', 'PK', 24.89, 66.94, 'ZDK03', 2, 'AEW'],
    ['SIALKOT', 'PK', 32.52, 74.36, 'SWARM', 24, 'ATTACK'],
    ['MURID', 'PK', 32.91, 72.79], ['SUKKUR', 'PK', 27.72, 68.79], ['RAHIM YAR KHAN', 'PK', 28.38, 70.28],
    ['BHOLARI', 'PK', 25.24, 67.98], ['SKARDU', 'PK', 35.34, 75.54],
  ].map(([id, side, la, lo, type, count, preset]) => ({ id, side, ...at(la, lo), type: type || null, count: count || 0, preset: preset || null }));

  // Strike targets (alleged militant infrastructure sites named in public reporting).
  const SITES = [
    ['BAHAWALPUR', 29.38, 71.68], ['MURIDKE', 31.80, 74.25], ['SIALKOT (MJ)', 32.48, 74.62],
    ['KOTLI', 33.50, 73.90], ['MUZAFFARABAD', 34.37, 73.47],
  ].map(([id, la, lo]) => ({ id, side: 'PK', ...at(la, lo) }));

  const SAM_SITES = [
    ['IN', 'SUDARSHAN', 'S400', 31.30, 75.60], ['IN', 'BARAK-1', 'MRSAM', 31.60, 75.05],
    ['IN', 'AKASH-1', 'AKASH', 32.20, 75.60], ['IN', 'AKASH-2', 'AKASH', 33.95, 74.80], ['IN', 'AKASH-3', 'AKASH', 29.60, 75.05],
    ['PK', 'HQ9-LHR', 'HQ9', 31.45, 74.15], ['PK', 'HQ9-ISB', 'HQ9', 33.55, 73.15],
    ['PK', 'LY80-SKT', 'LY80', 32.45, 74.40], ['PK', 'LY80-BWP', 'LY80', 29.42, 71.75],
    ['PK', 'LY80-MZD', 'LY80', 34.30, 73.45], ['PK', 'LY80-RFQ', 'LY80', 30.80, 72.35],
  ].map(([side, callsign, type, la, lo]) => ({ side, callsign, type, pos: at(la, lo) }));
  const RADAR_SITES = [
    ['IN', 'GR-AMRITSAR', 31.55, 75.15, 230], ['IN', 'GR-SURATGARH', 29.40, 73.95, 230],
    ['IN', 'GR-SRINAGAR', 33.95, 74.95, 200], ['IN', 'GR-JAISALMER', 26.90, 71.00, 230],
    ['PK', 'GR-LAHORE', 31.35, 73.95, 230], ['PK', 'GR-SAKESAR', 32.53, 71.94, 300],
    ['PK', 'GR-BAHAWALPUR', 29.45, 71.90, 230], ['PK', 'GR-TOPA', 33.90, 73.40, 230],
  ].map(([side, callsign, la, lo, range]) => ({ side, callsign, range, pos: at(la, lo) }));

  // ---------- campaign nights ----------
  // targets: each side's priority targets for the night ({kind, id}); 10 points
  // each to the attacker, 8 per untouched target to the defender.
  // ai: the plan the computer follows for that side (also offered to the player as
  // an "auto-deploy" of AEW&C and CAPs).
  const T = (kind, id) => ({ kind, id });
  const NIGHTS = [
    {
      n: 1, title: 'OPENING STRIKES', date: '6–7 MAY 2025', start: { h: 19, m: 15 }, duration: 50 * 60, sites: true,
      summary: 'India strikes five sites across the border. Pakistan defends and hits back at forward Indian positions.',
      targets: {
        IN: [T('site', 'BAHAWALPUR'), T('site', 'MURIDKE'), T('site', 'SIALKOT (MJ)'), T('site', 'KOTLI'), T('site', 'MUZAFFARABAD')],
        PK: [T('base', 'UDHAMPUR'), T('radar', 'GR-AMRITSAR')],
      },
      ai: {
        IN: {
          aew: [['BATHINDA', 30.60, 75.85], ['AGRA', 29.30, 76.60]],
          caps: [['HALWARA', 2, 31.35, 75.15], ['SIRSA', 2, 29.90, 74.55], ['SRINAGAR', 2, 33.75, 74.85], ['ADAMPUR', 2, 32.10, 75.35]],
          strikes: [
            { t: 240, base: 'AMBALA', n: 2, preset: 'STRIKE', list: [T('site', 'MURIDKE'), T('site', 'MUZAFFARABAD')] },
            { t: 300, base: 'AMBALA', n: 2, preset: 'STRIKE', list: [T('site', 'BAHAWALPUR'), T('site', 'BAHAWALPUR')] },
            { t: 420, base: 'PATHANKOT', n: 2, preset: 'STRIKE', list: [T('site', 'KOTLI'), T('site', 'SIALKOT (MJ)')] },
            { t: 900, base: 'AMBALA', n: 2, preset: 'STRIKE', list: [T('site', 'MUZAFFARABAD'), T('site', 'MURIDKE')] },
            { t: 960, base: 'PATHANKOT', n: 2, preset: 'STRIKE', list: [T('site', 'SIALKOT (MJ)'), T('site', 'KOTLI')] },
          ],
          escorts: [{ t: 200, base: 'AMBALA', n: 2, preset: 'AIR', at: [32.0, 75.2] }],
        },
        PK: {
          aew: [['NUR KHAN', 32.75, 72.95], ['MASROOR', 26.2, 69.0]],
          caps: [['MINHAS', 2, 32.70, 73.95], ['MINHAS', 2, 31.75, 73.85], ['RAFIQUI', 2, 30.40, 73.10], ['RAFIQUI', 2, 29.30, 72.10], ['MUSHAF', 2, 33.40, 73.55]],
          drones: [{ t: 900, base: 'SIALKOT', n: 8, mode: 'attack', list: [T('base', 'UDHAMPUR'), T('radar', 'GR-AMRITSAR')] }],
        },
      },
    },
    {
      n: 2, title: 'DRONE NIGHT', date: '7–8 MAY 2025', start: { h: 18, m: 30 }, duration: 50 * 60,
      summary: 'Pakistan sends drone swarms at Indian bases and radars. India hunts Pakistani air defences with Harop.',
      targets: {
        IN: [T('sam', 'HQ9-LHR'), T('radar', 'GR-LAHORE'), T('sam', 'LY80-SKT'), T('radar', 'GR-TOPA')],
        PK: [T('base', 'ADAMPUR'), T('base', 'PATHANKOT'), T('base', 'AMRITSAR'), T('radar', 'GR-AMRITSAR'), T('sam', 'AKASH-1')],
      },
      ai: {
        IN: {
          aew: [['BATHINDA', 30.75, 75.90]],
          caps: [['ADAMPUR', 2, 31.80, 75.20], ['HALWARA', 2, 31.10, 75.00], ['SRINAGAR', 2, 33.60, 74.90]],
          drones: [{ t: 180, base: 'AMRITSAR', n: 6, mode: 'sead', at: [31.50, 74.25] }, { t: 600, base: 'AMRITSAR', n: 6, mode: 'sead', at: [32.40, 74.30] }, { t: 1200, base: 'AMRITSAR', n: 6, mode: 'sead', at: [31.40, 74.00] }],
        },
        PK: {
          aew: [['NUR KHAN', 32.75, 72.95]],
          caps: [['MINHAS', 2, 32.60, 73.90], ['RAFIQUI', 2, 31.20, 73.40], ['MUSHAF', 2, 33.30, 73.60]],
          drones: [
            { t: 120, base: 'SIALKOT', n: 8, mode: 'attack', list: [T('base', 'PATHANKOT'), T('sam', 'AKASH-1')] },
            { t: 300, base: 'SIALKOT', n: 8, mode: 'attack', list: [T('base', 'AMRITSAR'), T('radar', 'GR-AMRITSAR')] },
            { t: 540, base: 'SIALKOT', n: 8, mode: 'attack', list: [T('base', 'ADAMPUR')] },
          ],
        },
      },
    },
    {
      n: 3, title: 'RETALIATION', date: '9–10 MAY 2025', start: { h: 22, m: 0 }, duration: 50 * 60,
      summary: 'Pakistan goes on the offensive against Indian air bases and the S-400. India defends and counter-strikes.',
      targets: {
        IN: [T('base', 'SIALKOT'), T('radar', 'GR-LAHORE'), T('base', 'MURID')],
        PK: [T('base', 'ADAMPUR'), T('base', 'UDHAMPUR'), T('base', 'PATHANKOT'), T('base', 'BATHINDA'), T('base', 'SIRSA'), T('sam', 'SUDARSHAN')],
      },
      ai: {
        IN: {
          aew: [['BATHINDA', 30.60, 75.85], ['AGRA', 29.30, 76.60]],
          caps: [['ADAMPUR', 2, 31.90, 75.30], ['HALWARA', 2, 31.20, 75.00], ['SIRSA', 2, 29.90, 74.50], ['SRINAGAR', 2, 33.50, 74.90], ['AMBALA', 2, 32.30, 75.40, 'AIR']],
          strikes: [{ t: 900, base: 'AMBALA', n: 2, preset: 'STRIKE', list: [T('base', 'SIALKOT'), T('base', 'MURID')] }],
          drones: [{ t: 300, base: 'AMRITSAR', n: 6, mode: 'sead', at: [31.40, 74.10] }],
        },
        PK: {
          aew: [['NUR KHAN', 32.75, 72.95], ['MASROOR', 26.2, 69.0]],
          caps: [['MINHAS', 2, 32.30, 73.80], ['MINHAS', 2, 31.60, 73.70]],
          strikes: [
            { t: 240, base: 'RAFIQUI', n: 2, preset: 'STRIKE', list: [T('sam', 'SUDARSHAN'), T('base', 'ADAMPUR')] },
            { t: 300, base: 'PESHAWAR', n: 2, preset: 'STRIKE', list: [T('base', 'UDHAMPUR'), T('base', 'PATHANKOT')] },
            { t: 420, base: 'MUSHAF', n: 2, preset: 'STRIKE', list: [T('base', 'PATHANKOT'), T('base', 'PATHANKOT')] },
            { t: 480, base: 'RAFIQUI', n: 2, preset: 'STRIKE', list: [T('base', 'BATHINDA'), T('base', 'SIRSA')] },
          ],
          drones: [{ t: 150, base: 'SIALKOT', n: 8, mode: 'attack', list: [T('base', 'PATHANKOT'), T('base', 'UDHAMPUR')] }],
        },
      },
    },
    {
      n: 4, title: 'AIRBASE STRIKES', date: '10 MAY 2025', start: { h: 0, m: 0 }, duration: 50 * 60, ceasefireAt: 45 * 60,
      summary: 'India strikes Pakistani air bases with BrahMos and SCALP; Pakistan hits back at Indian bases. A ceasefire is coming.',
      targets: {
        IN: [T('base', 'NUR KHAN'), T('base', 'RAFIQUI'), T('base', 'MURID'), T('base', 'MUSHAF'), T('base', 'JACOBABAD'), T('base', 'SUKKUR'), T('base', 'BHOLARI')],
        PK: [T('base', 'ADAMPUR'), T('base', 'PATHANKOT'), T('sam', 'SUDARSHAN')],
      },
      ai: {
        IN: {
          aew: [['BATHINDA', 30.60, 75.85], ['AGRA', 28.80, 75.90]],
          caps: [['ADAMPUR', 2, 31.90, 75.30], ['SRINAGAR', 2, 33.60, 74.90], ['AMBALA', 2, 32.20, 75.30, 'AIR']],
          strikes: [
            { t: 180, base: 'HALWARA', n: 4, preset: 'STRIKE', list: [T('base', 'NUR KHAN'), T('base', 'MURID'), T('base', 'MUSHAF'), T('base', 'RAFIQUI')] },
            { t: 240, base: 'JODHPUR', n: 4, preset: 'STRIKE', list: [T('base', 'JACOBABAD'), T('base', 'SUKKUR'), T('base', 'BHOLARI'), T('base', 'JACOBABAD')] },
            { t: 300, base: 'AMBALA', n: 2, preset: 'STRIKE', list: [T('base', 'RAFIQUI'), T('base', 'MUSHAF')] },
          ],
          drones: [{ t: 120, base: 'AMRITSAR', n: 6, mode: 'sead', at: [31.50, 74.20] }],
        },
        PK: {
          aew: [['NUR KHAN', 32.75, 72.95], ['MASROOR', 26.2, 69.0]],
          caps: [['MINHAS', 2, 32.60, 73.90], ['MINHAS', 2, 31.70, 73.80], ['RAFIQUI', 2, 30.40, 73.10], ['JACOBABAD', 2, 28.20, 70.00], ['MUSHAF', 2, 33.30, 73.60]],
          strikes: [{ t: 600, base: 'PESHAWAR', n: 2, preset: 'STRIKE', list: [T('base', 'ADAMPUR'), T('sam', 'SUDARSHAN')] }],
          drones: [{ t: 300, base: 'SIALKOT', n: 8, mode: 'attack', list: [T('base', 'PATHANKOT')] }],
        },
      },
    },
  ];

  // A hypothetical scenario (not part of the campaign): India's pre-emptive deep strike.
  NIGHTS.push({
    n: 5, tag: 'SCENARIO', scenario: true, title: 'OPERATION DEEP STRIKE', date: 'HYPOTHETICAL', start: { h: 1, m: 0 }, duration: 100 * 60,
    summary: 'India plans a pre-emptive strike on Mushaf air base at Sargodha, deep inside Pakistan behind layered air defences. Drain the SAMs with drone swarms, pull Pakistan\'s fighters away with a diversion, slip in low, hit the base and get home.',
    targets: { IN: [T('base', 'MUSHAF')], PK: [T('base', 'AMRITSAR'), T('base', 'ADAMPUR')] },
    // Scenario-only forces: a big Indian drone swarm stock, thinner Pakistani fighter bases, and
    // layered SAMs along the road from the border to Sargodha.
    pools: { AMRITSAR: { SWARM: 150 } },
    counts: { MUSHAF: 6, RAFIQUI: 8, MINHAS: 8 },
    sams: [['PK', 'LY80-GJW', 'LY80', 32.16, 74.19], ['PK', 'LY80-HFZ', 'LY80', 32.07, 73.69], ['PK', 'HQ9-SGD', 'HQ9', 32.10, 72.55]],
    ai: {
      IN: {
        aew: [['BATHINDA', 30.70, 75.40]],
        caps: [['ADAMPUR', 2, 31.80, 75.20], ['HALWARA', 2, 31.00, 74.90]],
        drones: [{ t: 120, base: 'AMRITSAR', type: 'SWARM', n: 100, mode: 'attack', list: [T('base', 'MUSHAF')] }],
        strikes: [{ t: 2100, base: 'AMBALA', n: 2, preset: 'STRIKE', list: [T('base', 'MUSHAF'), T('base', 'MUSHAF')] }],
      },
      PK: {
        aew: [['NUR KHAN', 32.75, 72.95]],
        caps: [['MUSHAF', 2, 32.25, 73.00], ['RAFIQUI', 2, 31.40, 73.70], ['MINHAS', 2, 33.00, 73.60]],
      },
    },
  });

  // Legacy per-flight loadouts (used by the training mission's pre-placed flights).
  const LOADOUTS = {
    RAFALE_CAP: { METEOR: 4, MICA: 4, MICAIR: 4 },
    J10C: { PL15: 6, PL10: 4 },
    AEW: {},
  };

  Object.assign(KC, { AIRCRAFT, WEAPONS, SAMS, PRESETS, PRESET_INFO, STOCKS, AIR_CAP, BASES, SITES, SAM_SITES, RADAR_SITES, NIGHTS, LOADOUTS, V_MIN });
})(globalThis.KC);
