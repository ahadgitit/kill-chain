# KILL CHAIN

A web-based interactive simulation of realistic modern air wars. The first scenario is a DEFCON-style 2D game inspired by the India–Pakistan air battle of 6–7 May 2025.
It works on desktop and mobile (touch), with no install and no build step.

Command **India**, whose strike package must put SCALP-EG and SPICE-2000 weapons on five target sites, or **Pakistan**, which must defend using AEW&C, Link-17 and PL-15E. The other side is AI-controlled. Outcomes come from the simulation each time you play and are not scripted.

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
- Tap an airbase to scramble reserve flights.
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
js/sim.js      simulation core (sensors, link picture, missiles, AI), no DOM access
js/render.js   canvas renderer
js/ui.js       input, HUD, menus, attract mode, game loop
js/training.js missile-evasion training mission
tools/build_map.py  rebuild js/mapdata.js from the world-atlas + sane-topojson npm packages
tools/headless.js   run AI-vs-AI headless: node tools/headless.js 10
```

## Map data

Coastlines, country borders, rivers and lakes come from [Natural Earth](https://www.naturalearthdata.com/) (public domain), via the `world-atlas` and `sane-topojson` npm packages. Borders are Natural Earth's de facto lines, and Kashmir is shown divided by the Line of Control. The map uses a stereographic projection centred on 30°N 73°E, so shapes stay true and in-game kilometres stay within ~2.5% of real distances across the theatre.
