// Writes js/mapdata.js as a compact binary for the Android app:
// node tools/export_map.js android/core/src/main/resources/mapdata.bin
// Format (big-endian): "KCM1", bbox[4] f64, then sections. Each line set: int count, then for
// each line: int n (number of values), n f64 values (lon, lat, lon, lat, ...).
const fs = require('fs'), path = require('path'), vm = require('vm');
vm.runInThisContext(fs.readFileSync(path.join(__dirname, '..', 'js', 'mapdata.js'), 'utf8'));
const M = globalThis.KC.MAPDATA;
const parts = [];
const int = v => { const b = Buffer.alloc(4); b.writeInt32BE(v); parts.push(b); };
const dbl = v => { const b = Buffer.alloc(8); b.writeDoubleBE(v); parts.push(b); };
const str = s => { const b = Buffer.from(s, 'utf8'); int(b.length); parts.push(b); };
const line = a => { int(a.length); for (const v of a) dbl(v); };
const lines = ls => { int(ls.length); for (const a of ls) line(a); };
parts.push(Buffer.from('KCM1'));
for (const v of M.bbox) dbl(v);
int(M.countries.length);
for (const c of M.countries) { str(c.n); lines(c.r); }
for (const k of ['borders', 'coasts', 'rivers', 'lakes']) lines(M[k]);
line(M.frontier); int(M.locStart); line(M.frontierCoarse);
fs.writeFileSync(process.argv[2], Buffer.concat(parts));
console.log('wrote', process.argv[2], Buffer.concat(parts).length, 'bytes');
