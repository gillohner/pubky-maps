#!/usr/bin/env python3
"""The routes emulator-place.sh asks the fixture server for, checked in order: by car from the GPS fix to
the Schober's point, then on foot from the same start, swapped (from the point back to the fix) and
swapped again; on foot from the dropped pin (somewhere else on the map), then from the fix again; last
from where the guided user went off the route (150 m east of Bahnhofquai): by itself, at most once
more by itself, then on Find a new route. All with the app's User-Agent.

The routes from off the route start where the app's rules put them, not at the spot the test sets the
GPS to: Android hands the app fixes on the way there (run 46115364345 attempt 1 rerouted from
(47.3776658, 8.5435012), 8.5 m short of the spot; attempt 2 from 3 m past it). By itself the app asks
on the second fix more than OFF_ROUTE_METERS (50 m, Guidance.kt) off the route's line, which on the
way from Bahnhofquai is any fix from about 34 % of the way (106 m from the spot) on; it asks once more
only 10 s later and from a fix at least 50 m from the last start (DirectionsController.mayRerouteFrom),
so after a first one from more than 50 m short of the spot it asks again at the spot (the fixture's
route still leaves the user off it), and never a third time. Find a new route asks from the last fix,
after the test held the GPS at the spot for 12 s. So each start by itself is more than 50 m off the
fixture route's line (the route the fixture server answers every time; the distance to the line's
nearest point, which on the way from Bahnhofquai is what the app measures up to 48 % of the way and
less after) and no farther from the spot than Bahnhofquai is (161 m; the dropped pin is 420 m away);
a second one is at least 50 m from the first; Find a new route's start is within AT_SPOT_METERS of
the spot; none is at the start's fix (fixes from 34 % of the way on are 154 m or more from it).

Prints the problems, one per line. Exit 0: none; 1: some; 2: the log, a route line in it or the fixture
route is unreadable.
CI: emulator-place.sh (after the place page's steps); checked by place-routes-check.py.

Usage: place_routes.py <requests.jsonl>
"""
import json
import math
import sys
from pathlib import Path

FIX = (47.3779, 8.5403)
PLACE = (47.3708689, 8.5418241)
OFF_SPOT = (47.377685, 8.543611)
BAHNHOFQUAI = (47.377335, 8.541531)
OFF_ROUTE_METERS = 50.0
AT_SPOT_METERS = 20.0
ROUTE_FILE = Path(__file__).resolve().parents[2] / "shared/src/commonTest/fixtures/search/hauptbahnhof-kunsthaus.valhalla.json"
COUNT = 8


def meters(a, b):
    """Metres between two (lat, lon) points (equirectangular, fine within a city)."""
    lat = math.radians((a[0] + b[0]) / 2)
    dy = math.radians(a[0] - b[0])
    dx = math.radians(a[1] - b[1]) * math.cos(lat)
    return 6371000.0 * math.hypot(dx, dy)


def decode_polyline6(encoded):
    """The (lat, lon) points of a polyline with 6 decimals (Valhalla's polyline6, as Valhalla.kt decodes it)."""
    points, i, values = [], 0, [0, 0]
    while i < len(encoded):
        for k in (0, 1):
            result = shift = 0
            while True:
                b = ord(encoded[i]) - 63
                i += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            values[k] += ~(result >> 1) if result & 1 else result >> 1
        points.append((values[0] / 1e6, values[1] / 1e6))
    return points


def route_line():
    """The fixture route's line; ValueError when it is not a line."""
    with open(ROUTE_FILE, encoding="utf-8") as f:
        line = decode_polyline6(json.load(f)["trip"]["legs"][0]["shape"])
    if len(line) < 2:
        raise ValueError(f"a line of {len(line)} points")
    return line


def off_line(p, line):
    """Metres from p to the nearest point of line (Guidance.kt's offSegment and planar)."""

    def planar(q, origin):
        x = math.radians(q[1] - origin[1]) * 6371000.0 * math.cos(math.radians(origin[0]))
        y = math.radians(q[0] - origin[0]) * 6371000.0
        return x, y

    best = math.inf
    for a, b in zip(line, line[1:]):
        bx, by = planar(b, a)
        px, py = planar(p, a)
        length2 = bx * bx + by * by
        t = 0.0 if length2 == 0 else min(1.0, max(0.0, (px * bx + py * by) / length2))
        best = min(best, math.hypot(px - t * bx, py - t * by))
    return best


def point(location):
    return (location["lat"], location["lon"])


def problems(routes, shape=None):
    """What is wrong with the route requests (the request log's /route lines), in order; shape is the
    fixture route's line (read from ROUTE_FILE when not given)."""
    shape = route_line() if shape is None else shape
    found = []
    # COUNT routes, or one more when the app asked again by itself after a first new route from a fix
    # on the way to the spot.
    count = len(routes) if len(routes) in (COUNT, COUNT + 1) else COUNT
    costings = [r.get("costing") for r in routes]
    wanted = ["auto"] + ["pedestrian"] * (count - 1)
    if costings != wanted:
        found.append(f"costings {costings}, wanted {wanted}")
    starts = []
    for i, r in enumerate(routes[:count]):
        start, end = (point(loc) for loc in r["locations"])
        if i == 2:
            start, end = end, start
        starts.append(start)
        at_fix = abs(start[0] - FIX[0]) < 1e-3 and abs(start[1] - FIX[1]) < 1e-3
        if at_fix != (i not in (4,) and i < 6):
            found.append(f"route {i}: start {start} {'at' if at_fix else 'not at'} the GPS fix")
        if 6 <= i < count - 1:
            off = off_line(start, shape)
            if off <= OFF_ROUTE_METERS:
                found.append(f"route {i}: start {start} is {off:.0f} m from the route's line, not more than {OFF_ROUTE_METERS:.0f} m off it")
            if meters(start, OFF_SPOT) > meters(BAHNHOFQUAI, OFF_SPOT):
                found.append(f"route {i}: start {start} is {meters(start, OFF_SPOT):.0f} m from where the user went off the route, farther than Bahnhofquai")
        if i == 7 and count == COUNT + 1 and meters(starts[6], start) < OFF_ROUTE_METERS:
            found.append(f"route 7: asked again by itself from {start}, {meters(starts[6], start):.0f} m from the first new route's start")
        if i == count - 1 and meters(start, OFF_SPOT) > AT_SPOT_METERS:
            found.append(f"route {i}: Find a new route from {start}, {meters(start, OFF_SPOT):.0f} m from where the user stands")
        if i == 4 and not (abs(start[0] - 47.37) < 0.1 and abs(start[1] - 8.54) < 0.1):
            found.append(f"route 4: start {start} is not the dropped pin in Zürich")
        if abs(end[0] - PLACE[0]) >= 1e-6 or abs(end[1] - PLACE[1]) >= 1e-6:
            found.append(f"route {i}: end {end} is not the place")
        if not r.get("ua", "").startswith("PubkyMaps/"):
            found.append(f"route {i}: User-Agent {r.get('ua')!r}")
    return found


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    try:
        shape = route_line()
    except (OSError, ValueError, LookupError, TypeError) as e:
        print(f"cannot read the fixture route {ROUTE_FILE}: {e!r}", file=sys.stderr)
        return 2
    try:
        with open(argv[1], encoding="utf-8") as f:
            lines = [json.loads(line) for line in f if line.strip()]
    except (OSError, ValueError) as e:
        print(f"cannot read {argv[1]}: {e}", file=sys.stderr)
        return 2
    try:
        routes = [line for line in lines if line.get("path") == "/route"]
        found = problems(routes, shape)
    except (AttributeError, KeyError, TypeError, ValueError) as e:
        print(f"cannot read a route in {argv[1]}: {e!r}", file=sys.stderr)
        return 2
    for p in found:
        print(p)
    if not found:
        starts = [point(r["locations"][0]) for r in routes[6:]]
        off = ", ".join(f"{meters(at, OFF_SPOT):.0f} m from the spot ({off_line(at, shape):.0f} m off the route)" for at in starts)
        print(f"routes: {len(routes)} as asked; the {len(starts)} from off the route start {off}")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
