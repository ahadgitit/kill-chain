"""Builds js/mapdata.js from Natural Earth TopoJSON (via npm packages).

  npm pack world-atlas@2.0.2 sane-topojson@4.0.0   # then untar both
  python3 tools/build_map.py <world-atlas/package> <sane-topojson/package>

Country polygons, land borders and coastlines come from world-atlas countries-10m
(shared arcs = land borders); rivers and lakes from sane-topojson world_50m.
Boundaries are Natural Earth's de facto lines; Kashmir is shown by the Line of Control.
"""
import json, sys, os
from collections import defaultdict
import topo

WA, ST = sys.argv[1], sys.argv[2]
BBOX = (50.0, -2.0, 106.0, 44.0)          # lon0, lat0, lon1, lat1
THEATRE = (64.0, 20.0, 82.0, 38.0)        # finer detail here

def in_box(p, b, pad=0.0):
    return b[0] - pad <= p[0] <= b[2] + pad and b[1] - pad <= p[1] <= b[3] + pad

def dp(pts, tol):
    """Douglas-Peucker simplification (keeps endpoints)."""
    if len(pts) < 3: return pts
    if pts[0] == pts[-1]:                      # closed ring: split at the farthest point
        k = max(range(len(pts)), key=lambda i: (pts[i][0] - pts[0][0]) ** 2 + (pts[i][1] - pts[0][1]) ** 2)
        if k == 0: return pts
        return dp(pts[:k + 1], tol) + dp(pts[k:], tol)[1:]
    keep = [False] * len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = pts[a]; bx, by = pts[b]
        dx, dy = bx - ax, by - ay
        L = (dx * dx + dy * dy) ** 0.5 or 1e-12
        best, bi = -1, -1
        for i in range(a + 1, b):
            px, py = pts[i]
            d = abs(dy * px - dx * py + bx * ay - by * ax) / L
            if d > best: best, bi = d, i
        if best > tol:
            keep[bi] = True
            stack += [(a, bi), (bi, b)]
    return [p for p, k in zip(pts, keep) if k]

def tol_for(pts):
    inside = sum(in_box(p, THEATRE) for p in pts[::3])
    return 0.012 if inside > len(pts[::3]) * 0.3 else 0.045

def clip_ring(ring, b):
    """Sutherland-Hodgman clip of a closed ring to a rectangle."""
    def clip(pts, inside, inter):
        out = []
        for i in range(len(pts)):
            cur, prev = pts[i], pts[i - 1]
            if inside(cur):
                if not inside(prev): out.append(inter(prev, cur))
                out.append(cur)
            elif inside(prev): out.append(inter(prev, cur))
        return out
    def ix(x):
        return lambda p, q: (x, p[1] + (q[1] - p[1]) * (x - p[0]) / (q[0] - p[0]))
    def iy(y):
        return lambda p, q: (p[0] + (q[0] - p[0]) * (y - p[1]) / (q[1] - p[1]), y)
    pts = ring
    for inside, inter in [(lambda p: p[0] >= b[0], ix(b[0])), (lambda p: p[0] <= b[2], ix(b[2])),
                          (lambda p: p[1] >= b[1], iy(b[1])), (lambda p: p[1] <= b[3], iy(b[3]))]:
        if not pts: break
        pts = clip(pts, inside, inter)
    return pts

def clip_line(pts, b):
    """Split a polyline into the runs that fall inside the box (with one point of overhang)."""
    runs, cur = [], []
    for i, p in enumerate(pts):
        if in_box(p, b, 0.5):
            if not cur and i: cur.append(pts[i - 1])
            cur.append(p)
        elif cur:
            cur.append(p); runs.append(cur); cur = []
    if cur: runs.append(cur)
    return [r for r in runs if len(r) > 1]

flat = lambda pts: [round(v, 3) for p in pts for v in p]

# ---------- countries, borders, coasts ----------
d, arcs = topo.load(os.path.join(WA, 'countries-10m.json'))
geoms = [g for g in d['objects']['countries']['geometries']
         if any(in_box(p, BBOX) for poly in topo.polygons(g) for r in poly for i in r for p in topo.arc(arcs, i)[::4])]
users = defaultdict(set)
for g in geoms:
    for poly in topo.polygons(g):
        for r in poly:
            for i in r: users[i if i >= 0 else ~i].add(g['properties']['name'])
simple = {i: dp(arcs[i], tol_for(arcs[i])) for i in users}
sarc = lambda i: simple[i] if i >= 0 else simple[~i][::-1]

countries = []
for g in geoms:
    rings = []
    for poly in topo.polygons(g):
        for k, r in enumerate(poly):
            pts = []
            for i in r:
                p = sarc(i); pts.extend(p if not pts else p[1:])
            pts = clip_ring(pts, BBOX)
            if len(pts) >= 3: rings.append(flat(pts))
    if rings: countries.append({'n': g['properties']['name'], 'r': rings})

frontier, borders, coasts = None, [], []
for i, names in users.items():
    pts = simple[i]
    if names == {'India', 'Pakistan'}:
        frontier = pts if pts[0][1] < pts[-1][1] else pts[::-1]   # south -> north
        continue
    for run in clip_line(pts, BBOX):
        (borders if len(names) > 1 else coasts).append({'p': flat(run), 'n': sorted(names)})

# Where the international border / working boundary becomes the Line of Control (near Akhnoor).
locStart = min(range(len(frontier)), key=lambda k: (frontier[k][0] - 74.66) ** 2 + (frontier[k][1] - 32.89) ** 2)

# ---------- rivers & lakes ----------
d2, arcs2 = topo.load(os.path.join(ST, 'dist', 'world_50m.json'))
rivers = []
for g in d2['objects']['rivers']['geometries']:
    for l in topo.lines(g):
        pts = topo.ring(arcs2, l)
        for run in clip_line(pts, BBOX):
            rivers.append(flat(dp(run, 0.02)))
lakes = []
for g in d2['objects']['lakes']['geometries']:
    for poly in topo.polygons(g):
        pts = topo.ring(arcs2, poly[0])
        if all(in_box(p, BBOX) for p in pts[::3]) and len(pts) > 5:
            lakes.append(flat(dp(pts, 0.02)))

out = {
    'bbox': BBOX, 'countries': countries, 'frontier': flat(frontier), 'locStart': locStart,
    'frontierCoarse': flat(dp(frontier, 0.04)),
    'borders': [b['p'] for b in borders], 'coasts': [c['p'] for c in coasts], 'rivers': rivers, 'lakes': lakes,
}
js = ('// Generated by tools/build_map.py from Natural Earth (public domain) via world-atlas and sane-topojson.\n'
      '// Coordinates are flat [lon, lat, lon, lat, ...] arrays. Do not edit by hand.\n'
      'globalThis.KC = globalThis.KC || {};\nKC.MAPDATA = ' + json.dumps(out, separators=(',', ':')) + ';\n')
dst = os.path.join(os.path.dirname(__file__), '..', 'js', 'mapdata.js')
open(dst, 'w').write(js)
n = lambda xs: sum(len(x) for x in xs) // 2
print('countries', len(countries), 'pts', sum(n(c['r']) for c in countries), '| frontier', len(frontier), 'locStart', locStart,
      '| borders', n(out['borders']), '| coasts', n(out['coasts']), '| rivers', n(rivers), '| lakes', len(lakes), '| bytes', len(js))
