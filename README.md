# KILL CHAIN

A web-based interactive simulation of realistic modern air wars. It's a DEFCON-style 2D game inspired by the India–Pakistan air war of 6–10 May 2025, played over a full map of the subcontinent.
It works on desktop and mobile (touch), with no install and no build step.

## Modes

- **Campaign (4 nights):** opening strikes, drone night, retaliation, airbase strikes. Aircraft losses, weapon stocks, base damage, destroyed air defences and escalation carry over from night to night. The highest total wins.
- **Single night:** play any one of the four, as either side.
- **Training:** four lessons on missile evasion and the kill chain.

## How a night works

- **Air bases.** Each base hosts one aircraft type, using real names and locations (India: Ambala Rafale, Halwara/Sirsa/Jodhpur Su-30MKI, Adampur/Srinagar MiG-29, Pathankot Mirage 2000, Bathinda Netra, Agra Phalcon, Amritsar Harop; Pakistan: Minhas J-10CE, Rafiqui/Peshawar JF-17, Mushaf/Jacobabad F-16, Nur Khan Erieye, Masroor ZDK-03, Sialkot drones). Other fields are divert bases and targets.
- **Everything starts on the ground.** Tap a base to see its aircraft, choose a **loadout preset** (AIR / STRIKE / PRECISION) and **DEPLOY n** or **SCRAMBLE ALL**.
- **Limits.**
  - **Weapons:** come from national stocks.
  - **Fuel:** limited. At BINGO a flight returns to base.
  - **Landing:** refuels and re-arms (about 7 minutes).
  - **Airborne cap:** at most 24 Indian and 20 Pakistani aircraft in the air at once.
- **Strikes.** Hits on a base close its runway at 60% damage, so jets divert, and can destroy aircraft parked on it. SAM sites and radars can be destroyed.
- **Drones.** Harop hunts radars by their emissions (switch a SAM's radar off to hide it). Swarm drones are cheap and soak up SAM missiles.
- **Electronic warfare (on cooldowns).** LINK JAM cuts the enemy data link, BARRAGE blinds radars over an area, and DECOYS send fake fighters.
- **Decision cards.** 10-second choices at key moments: hold to guide a missile or break, press on below bingo fuel, scramble a threatened base, fire on an enemy AEW&C, stand down at high escalation.
- **Fog of war.** Kills are only *claimed* during the night. The debrief shows claimed against confirmed.
- **Escalation.** Strikes and kills raise a shared meter. At 100% both sides lose. Night 4 ends in a ceasefire.
- **After-action replay.** Watch the whole night from both sides.
- **Scoring.**
  - 10 points per priority target hit, and 8 per enemy priority target left untouched.
  - 6 per other base or air defence hit.
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

- Tap a flight to select it. Tap the map to move it there (it orbits on arrival).
- With a flight selected, tap an enemy track to attack it, or tap a red target site to strike it.
- Tap an airbase to deploy or scramble its aircraft and set their loadout. RTB sends a flight home to refuel and re-arm.
- **BREAK** (B) turns tail and runs, so a long-range missile burns out chasing you. **NOTCH** (N) flies at 90° to a close, fast missile to hide in radar clutter. Jets defend automatically when warned; these buttons let you choose the move, or act before any warning.
- Incoming missiles show a line to their target: red while they can still reach it, grey dashed once they are losing energy.
- ⚠ in the top bar auto-pauses the game on missile warnings.
- Drag to pan; pinch or scroll to zoom. Space pauses; keys 1–5 set speed; Esc deselects; R toggles radar; B/N break/notch.

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
