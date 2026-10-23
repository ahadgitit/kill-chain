# KILL CHAIN

A web-based interactive simulation of realistic modern air wars. It's a DEFCON-style 2D game inspired by the India–Pakistan air war of 6–10 May 2025, played over a full map of the subcontinent.
It works on desktop and mobile (touch), with no install and no build step.

## Modes

- **Campaign (4 nights):** opening strikes, drone night, retaliation, airbase strikes. Aircraft losses, weapon stocks, base damage, destroyed air defences and escalation carry over from night to night. The highest total wins.
- **Single night:** play any one of the four, as either side.
- **Training:** four lessons showing how jets evade missiles and how the kill chain works.

## How a night works

- **Air bases.** Each base hosts one aircraft type, using real names and locations (India: Ambala Rafale, Halwara/Sirsa/Jodhpur Su-30MKI, Adampur/Srinagar MiG-29, Pathankot Mirage 2000, Bathinda Netra, Agra Phalcon, Amritsar Harop; Pakistan: Minhas J-10CE, Rafiqui/Peshawar JF-17, Mushaf/Jacobabad F-16, Nur Khan Erieye, Masroor ZDK-03, Sialkot drones). Other fields are divert bases and targets.
- **Your job: three missions.** Tap one of your air bases, choose how many aircraft to send, and pick:
  - **CHASE**: tap an enemy aircraft. Each fighter carries 4 air-to-air missiles and fires 2 per enemy. An aircraft goes down after 2 hits (1 hit damages it).
  - **ESCORT**: tap one of your ground-attack flights. The escort flies ahead of it and fights off interceptors. A striker given an escort holds in safe airspace until the escort catches up.
  - **GROUND ATTACK**: tap an enemy site, radar, SAM or air base, and choose 0, 1 or 2 escorts from the same base. Each striker carries 3 air-to-surface weapons and 2 short-range missiles. A target is destroyed by 3 hits. The striker presses in as close as it can and fires early only if enemy fighters close in. Closer launches hit more often and spend less time over enemy ground defences, so clearing the way with escorts pays off. Drone bases send one-way drones.

  You can also select an airborne flight and give it a new mission, or simply tap an enemy with the flight selected.
- **Early warning.** Enemy strike jets are flagged as soon as they're detected (⚠ STRIKER INBOUND, a pulsing ring and a line to the likely target) and again when they're within 100 km of it. Stand-off weapon ranges are about half the real figures (SCALP 140 km, BrahMos 200 km, CM-400 120 km, H-4 60 km), so strike jets must come within reach of defenders.
- **The AI flies everything else.** It dodges missiles (break or notch), manages radar, returns at bingo fuel, refuels and re-arms. AEW&C aircraft take off and orbit on their own, and ready fighters scramble automatically when enemies come near their base.
- **Limits.**
  - **Weapons:** the decisive ones (Meteor, SCALP-EG, BrahMos-A, PL-15E, CM-400AKG) come from limited national stocks.
  - **Fuel:** limited. At BINGO a flight returns to base.
  - **Landing:** refuels and re-arms (about 7 minutes).
  - **Airborne cap:** at most 24 Indian and 20 Pakistani aircraft in the air at once.
- **Strikes.** Hits on a base close its runway at 60% damage, so jets divert, and can destroy aircraft parked on it. SAM sites and radars can be destroyed.
- **Drones.** Harop hunts radars by their emissions (switch a SAM's radar off to hide it). Swarm drones are cheap and soak up SAM missiles.
- **Decision card.** At 80% escalation you choose: stand down strikes, or press on.
- **Fog of war.** Kills are only *claimed* during the night. The debrief shows claimed against confirmed.
- **Escalation.** Strikes and kills raise a shared meter. At 100% both sides lose. Night 4 ends in a ceasefire.
- **After-action replay.** Watch the whole night from both sides.
- **Scoring.**
  - Every night, both sides have priority targets to attack and assets to defend. A priority target is worth 10 when destroyed (3 hits), scored per hit; the defender keeps 8 per target for the share left intact.
  - Up to 6 per other base or air defence (by hits).
  - Kills score the aircraft's value, and ground kills half its value.
  - 3 per strike weapon intercepted, 1 per drone.

## Play

- Open `index.html` in any modern browser, or
- Turn on GitHub Pages (Settings → Pages → Deploy from branch → `main` / root) and open the link on your phone.

## What it models (simplified)

| Concept | In the game |
|---|---|
| **Detection** | Radar range scales with target radar cross-section (RCS^¼). Fighter radars look ±60°. AEW&C and ground radars look all around. Low-flying cruise missiles are hard for ground radars to see. |
| **Data link** | Linked units share tracks. A linked fighter can fire with its radar **off**, using an AEW&C or ground-radar track. The F-16 is not on the national Link-17 net in this sim, so it must use its own radar. |
| **Warnings** | A shooter emitting a radar lock trips the target's RWR at launch. A silent, data-link-cued shot is only detected when the missile's own seeker goes active. |
| **Missile kinematics** | Boost, then drag. A missile slows as it flies, so turning away (dragging) can defeat long shots. Effective range depends on aspect. |
| **Mid-course guidance** | PL-15E can be updated by third-party link tracks. Meteor, AIM-120, R-77 and MICA need the shooter's own radar until the seeker goes active, so the shooter has to "crank" to keep supporting the missile. |
| **Survivability** | EW suites (e.g. SPECTRA) and defensive manoeuvres lower the probability of kill. |
| **SAMs** | S-400, MRSAM, Akash, HQ-9/P and LY-80 engage on their own or via networked tracks. |

All figures are rough open-source estimates in `js/data.js`. Real performance is classified and disputed. This is a game, not a historical record or a claim about what happened.

## Controls

- Tap a base or a flight, pick CHASE, ESCORT or GROUND ATTACK, then tap the target.
- With a flight selected, tap an enemy aircraft to chase it or an enemy target to attack it.
- Drag to pan; pinch or scroll to zoom. Space pauses; keys 1–5 set speed; Esc cancels. ▤ lists missions, ≡ shows the event log.

New players: start with **Training: Missile Evasion** on the main menu (four short lessons).

## Code

```
index.html, style.css
js/mapdata.js  generated map data (Natural Earth), see tools/build_map.py
js/geo.js      stereographic projection, frontier tests, labels
js/data.js     aircraft, weapons, SAMs, scenario
js/sim.js      simulation core: bases, sorties, sensors, link, missiles, strikes, drones, EW, escalation, replay
js/ai.js       side-level AI commander (CAPs, strikes, drones, EW)
js/render.js   canvas renderer
js/ui.js       input, HUD, menus, attract mode, game loop
js/training.js missile-evasion training mission
tools/build_map.py  rebuild js/mapdata.js from the world-atlas + sane-topojson npm packages
tools/headless.js   run nights AI-vs-AI headless: node tools/headless.js <runs> <night>
```

## Map data

Coastlines, country borders, rivers and lakes come from [Natural Earth](https://www.naturalearthdata.com/) (public domain), via the `world-atlas` and `sane-topojson` npm packages. Borders are Natural Earth's de facto lines, and Kashmir is shown divided by the Line of Control. The map uses a stereographic projection centred on 30°N 73°E, so shapes stay true and in-game kilometres stay within ~2.5% of real distances across the theatre.
