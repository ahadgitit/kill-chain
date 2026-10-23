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
- **Radar on, off, or low level.** Select a fighter to set its radar mode: AUTO (the AI decides), RADAR ON, RADAR OFF, or LOW LEVEL. Setting it on a formation lead applies it to the wingmen flying with it.
  - **Radar off:** enemy radars can still see the jet but can't lock it. Radar-guided missiles (BVR missiles and SAMs) can't be fired at it, only heat-seekers inside their ~20 km range. But a jet must switch its radar on to fire anything, bombs included, and then it can be locked. With radar off it only fires at its own mission target.
  - **Low level (terrain hugging):** radar off and flying low. Invisible to AEW&C, ground radars and SAMs. Enemy fighters see it only looking down at 60% of their radar range, and any fighter sees anything within 15 km. 15% slower and 60% more fuel. Firing switches the radar on and ends the low-level run.
  - **Exposed:** switching the radar on inside enemy airspace exposes the jet. Its radar stays on and it can't go silent or low again until it lands.
  - **The AI does this too:** it sends many of its strike jets in low, without an escort, behind a fighter sweep that draws your attention, and it raids AEW&C aircraft at low level.
- **Drone swarms and SAM magazines.** Swarms are drawn as clouds of dots and can only be shot down by SAMs, not fighters. Every SAM site has a small magazine (6–8 missiles, shown as a bar). Empty sites reload over 10 minutes (an amber loading bar), with one spare magazine per site per night; after that the site is EMPTY. A large swarm sent down a corridor drains the SAMs along it. A swarm can damage a target, at most 2 of its 3 hits, but it takes a strike to finish it.
- **Routes.** Ground attacks, drone attacks and diversions can be given up to 6 waypoints when you plan them (+ WAYPOINT, then tap the map). Escorts follow their striker's route.
- **Diversion.** A fighter mission that fakes a strike on a target you pick. It flies radar-on (the enemy sees a STRIKER), holds 70 km short of the target, drags pursuing fighters back toward the border and turns in again, for up to 25 minutes.
- **Going loud.** When a strike jet releases its weapons, its wingmen and escorts switch their radars on and leave low level too.
- **Scenario: OPERATION DEEP STRIKE** (Single night menu, not part of the campaign). A hypothetical pre-emptive Indian strike on Mushaf air base at Sargodha, behind layered SAMs (Lahore, Gujranwala, Hafizabad, Sargodha). India gets 150 swarm drones at Amritsar; Pakistan's nearby fighter bases are thinner. Play it as India: drain the SAMs with a swarm, pull the fighters away with a diversion, send the strike in low along the swarm's route, and race home.
- **The enemy commander.** The opposing side is run by a commander with a doctrine, not a timetable:
  - **Fighter control:** every intruder in its airspace, and every strike jet heading for it, gets a response with the numbers in its favour (two of its fighters per intruding jet, strikers first), from patrols nearby or scrambled from the nearest base.
  - **Strike packages:** a fighter sweep goes in first to clear the corridor, then the strike jets follow with escorts.
  - **Feints and pounces:** it may show fighters toward one target before striking another, and it launches early when your fighters around a target are gone.
  - **Learning:** targets that cost it strike jets get bigger sweeps, or it switches to a target that's cheaper to hit.
  - **Raids on your AEW&C** when it flies close to the frontier.
  - **Phases:** when the night turns against it, it brings its remaining strikes forward into one massed wave, then plans a final push late in the night.
  - **Tells:** before its big moves the log shows a purple ◆ INTEL line with a chirp, so you can read it and react.
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
- **Every jet is its own aircraft.** Jets that take off together from the same base for the same task fly in formation: a lead and wingmen (e.g. COBRA-1, COBRA-1.2) holding slots about 1.5 km off the lead's wing. Each jet still has its own missiles, radar, fuel and damage. It breaks off to fight or to dodge a missile aimed at it, then rejoins, and you can select any single jet and give it a new mission. If the lead is lost or re-tasked, the next wingman takes over. In a strike, each jet takes its own target from the list. Silhouettes show the type (delta wing for Rafale, Mirage and J-10; swept wing for Su-30, MiG-29, JF-17 and F-16), and a damaged jet turns amber.
- **After-action replay.** Watch the whole night from both sides.
- **Sound.** All synthesised in the browser (no audio files): a sonar-style radar ping with echoes and faint contact returns over a quiet low pad, missile launch whoosh, warning tones (launch warning, missile active), lock tone, air and ground explosions, jet take-off roar and a striker alarm. 🔊 in the top bar or on the menu mutes it.
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
| **Data link** | Linked units share tracks, so a radar-silent fighter still sees what the AEW&C sees. It switches its radar on only at the moment it fires, and with a data-link track the target gets no launch warning. The F-16 is not on the national Link-17 net in this sim, so it must use its own radar. |
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
js/ai.js       enemy commander: fighter control, strike packages, feints, phases, tells
js/render.js   canvas renderer
js/audio.js    synthesised sound effects and ambience (Web Audio API)
js/ui.js       input, HUD, menus, attract mode, game loop
js/training.js missile-evasion training mission
tools/build_map.py  rebuild js/mapdata.js from the world-atlas + sane-topojson npm packages
tools/headless.js   run nights AI-vs-AI headless: node tools/headless.js <runs> <night>
```

## Android app

`android/` is a native Android version of the game (no web view): the simulation and AI are ported
to Kotlin (`android/core`, checked event for event against the JS version with
`tools/dump_events.js`), and the map, HUD, panels, menus, training, replay and sounds are drawn
with the Android framework (`android/app`). It adds a first-run guide, a pause menu and an about
screen, and plays in landscape.

- `android/core`: game core, plain Kotlin. `gradle :core:installDist`, then
  `core/build/install/core/bin/core events 1 1` prints a night's events (compare with
  `node tools/dump_events.js 1 1`).
- `android/app`: app sources, plus a standard Android Gradle Plugin build (`app/build.gradle.kts`).
  With Android Studio or the SDK installed, `./gradlew :app:bundleRelease` makes a Play Store bundle.
- `android/apk`: builds the same app **without the Android SDK**: it compiles against the Android
  framework jar on Maven Central, runs the app under Robolectric (`gradle :apk:test` saves
  screenshots of every screen to `apk/build/shots`), and packs a signed APK with dx, a hand-written
  binary manifest and resource table (`android/packer`) and apksig:
  `gradle :apk:assembleApk` → `apk/build/outputs/KillChain.apk`, plus Play Store icon and feature
  graphic in `apk/build/outputs/store`.
- Signing: `android/release.keystore` (created on first build, never committed; default password
  `killchain`, override with `-Pkc.storepass=...`). Keep it: updates must be signed with the same key.

## Map data

Coastlines, country borders, rivers and lakes come from [Natural Earth](https://www.naturalearthdata.com/) (public domain), via the `world-atlas` and `sane-topojson` npm packages. Borders are Natural Earth's de facto lines, and Kashmir is shown divided by the Line of Control. The map uses a stereographic projection centred on 30°N 73°E, so shapes stay true and in-game kilometres stay within ~2.5% of real distances across the theatre.
