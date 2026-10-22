"""Minimal TopoJSON decoder used by tools/build_map.py."""
import json

def load(path):
    d = json.load(open(path))
    sx, sy = d['transform']['scale']; tx, ty = d['transform']['translate']
    arcs = []
    for a in d['arcs']:
        x = y = 0; pts = []
        for dx, dy in a:
            x += dx; y += dy
            pts.append((x * sx + tx, y * sy + ty))
        arcs.append(pts)
    return d, arcs

def arc(arcs, i):
    return arcs[i] if i >= 0 else arcs[~i][::-1]

def ring(arcs, idxs):
    out = []
    for i in idxs:
        p = arc(arcs, i)
        out.extend(p if not out else p[1:])
    return out

def polygons(geom):
    """Yield polygons as lists of arc-index rings."""
    if geom['type'] == 'Polygon': yield geom['arcs']
    elif geom['type'] == 'MultiPolygon': yield from geom['arcs']

def lines(geom):
    if geom['type'] == 'LineString': yield geom['arcs']
    elif geom['type'] == 'MultiLineString': yield from geom['arcs']
