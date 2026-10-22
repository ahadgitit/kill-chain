// Runs the scenario AI-vs-AI without a browser: node tools/headless.js [runs] [seed]
const fs = require('fs'), path = require('path'), vm = require('vm');
for (const f of ['mapdata.js', 'geo.js', 'data.js', 'sim.js']) {
  vm.runInThisContext(fs.readFileSync(path.join(__dirname, '..', 'js', f), 'utf8'), { filename: f });
}
const KC = globalThis.KC;
const runs = +process.argv[2] || 1, seed0 = +process.argv[3] || 1;
const verbose = runs === 1;
const tot = { IN: 0, PK: 0, hits: 0 };
for (let r = 0; r < runs; r++) {
  const sim = new KC.Sim(KC.SCENARIO, null, seed0 + r);
  while (!sim.over) sim.update(5);
  if (verbose) for (const e of sim.events) if (e.side !== 'PK' || e.cls !== 'info') console.log(sim.clock(e.t), e.side.padEnd(3), e.text);
  const s = sim.summary();
  tot.IN += s.IN.lost; tot.PK += s.PK.lost; tot.hits += s.IN.hits;
  console.log(`run ${r}: IN lost ${s.IN.lost}, PK lost ${s.PK.lost}, hits ${s.IN.hits}, intercepts ${s.PK.intercepts}, shots IN ${s.IN.shots} PK ${s.PK.shots}`);
}
if (runs > 1) console.log('avg', (tot.IN / runs).toFixed(2), (tot.PK / runs).toFixed(2), (tot.hits / runs).toFixed(2));
