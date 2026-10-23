// Every event of one night, for comparing with the Kotlin port:
// node tools/dump_events.js <night> <seed> [player|none]
const fs = require('fs'), path = require('path'), vm = require('vm');
for (const f of ['mapdata.js', 'geo.js', 'data.js', 'sim.js', 'ai.js']) vm.runInThisContext(fs.readFileSync(path.join(__dirname, '..', 'js', f), 'utf8'), { filename: f });
const KC = globalThis.KC;
const night = KC.NIGHTS[(+process.argv[2] || 1) - 1], seed = +process.argv[3] || 1;
const player = process.argv[4] && process.argv[4] !== 'none' ? process.argv[4] : null;
const sim = new KC.Sim({ name: `${night.tag || 'NIGHT ' + night.n}: ${night.title}`, start: night.start, duration: night.duration, night }, player, seed);
let last = 0;
const out = [];
while (!sim.over) {
  sim.update(5);
  for (const e of sim.events) if (e.n > last) { out.push(`${sim.clock(e.t)} ${e.side} ${e.text}`); last = e.n; }
}
const s = sim.summary();
out.push(`points IN ${s.IN.points} PK ${s.PK.points} lost IN ${s.IN.lost} PK ${s.PK.lost} | ${sim.endReason}`);
console.log(out.join('\n'));
