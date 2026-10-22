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
- Drag to pan; pinch or scroll to zoom. Space pauses; keys 1–5 set speed; Esc deselects; R toggles radar.

## Code

```
index.html, style.css
js/geo.js      simplified map outlines + projection
js/data.js     aircraft, weapons, SAMs, scenario
js/sim.js      simulation core (sensors, link picture, missiles, AI), no DOM access
js/render.js   canvas renderer
js/ui.js       input, HUD, menus, game loop
tools/headless.js   run AI-vs-AI headless: node tools/headless.js 10
```
