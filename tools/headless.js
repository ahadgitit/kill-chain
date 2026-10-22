// Runs nights AI-vs-AI without a browser: node tools/headless.js [runs] [night] [seed]
const fs = require('fs'), path = require('path'), vm = require('vm');
for (const f of ['mapdata.js', 'geo.js', 'data.js', 'sim.js', 'ai.js']) {
  vm.runInThisContext(fs.readFileSync(path.join(__dirname, '..', 'js', f), 'utf8'), { filename: f });
}
const KC = globalThis.KC;
const runs = +process.argv[2] || 1, nightNo = +process.argv[3] || 1, seed0 = +process.argv[4] || 1;
const night = KC.NIGHTS[nightNo - 1];
const verbose = runs === 1;
const tot = { IN: 0, PK: 0, pIN: 0, pPK: 0 };
for (let r = 0; r < runs; r++) {
  const sim = new KC.Sim({ name: night.title, start: night.start, duration: night.duration, night }, null, seed0 + r);
  while (!sim.over) sim.update(5);
  if (verbose) for (const e of sim.events) if (e.cls !== 'info' && !/LAUNCH WARNING|MISSILE|evaded|defeated|notched/.test(e.text)) console.log(sim.clock(e.t), e.side.padEnd(3), e.text);
  const s = sim.summary();
  tot.IN += s.IN.lost; tot.PK += s.PK.lost; tot.pIN += s.IN.points; tot.pPK += s.PK.points;
  console.log(`run ${r}: lost IN ${s.IN.lost} PK ${s.PK.lost} | obj IN ${s.IN.objHits} PK ${s.PK.objHits} | ground kills IN ${s.IN.groundKills} PK ${s.PK.groundKills} | intercepts IN ${s.IN.intercepts} PK ${s.PK.intercepts} | drones killed IN ${s.IN.droneKills} PK ${s.PK.droneKills} | claims IN ${s.IN.claimed}/${s.IN.kills} PK ${s.PK.claimed}/${s.PK.kills} | esc ${Math.round(sim.esc)} | points IN ${s.IN.points} PK ${s.PK.points} ${sim.endReason}`);
}
if (runs > 1) console.log('avg lost', (tot.IN / runs).toFixed(1), (tot.PK / runs).toFixed(1), 'points', (tot.pIN / runs).toFixed(1), (tot.pPK / runs).toFixed(1));
