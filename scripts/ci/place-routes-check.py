#!/usr/bin/env python3
"""Checks scripts/ci/place_routes.py on the route requests of run 46115364345: attempt 2's (green) and
attempt 1's, whose new route by itself started 8.5 m short of where the user went off the route (a fix
on the way there; the old 1e-4 degree check failed it), pass, and so does a first new route from 35 %
of the way from Bahnhofquai to the spot (51 m off the route; the app's first fix off it is at 34.1 %),
also with the second one by itself the app then asks at the spot. A new route by itself from
Bahnhofquai (the last fix on the route), from 34 % of the way (49.9 m off the route), from the start's
GPS fix or from farther than Bahnhofquai from the spot, a second one by itself less than 50 m from the
first, Find a new route not at the spot, a missing or extra route, a wrong costing, end or User-Agent
fail; an unreadable log, route line or fixture route exits 2.
CI: android.yml, build job.

Usage: place-routes-check.py
"""
import contextlib
import copy
import importlib.util
import io
import json
import sys
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("place_routes", Path(__file__).with_name("place_routes.py"))
place_routes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(place_routes)
failures = []


def expect(cond, what):
    if not cond:
        failures.append(what)


def route(costing, start, end):
    return {
        "path": "/route",
        "costing": costing,
        "locations": [{"lat": start[0], "lon": start[1]}, {"lat": end[0], "lon": end[1]}],
        "ua": "PubkyMaps/0.1.0",
    }


FIX = (47.3778983, 8.5403)
PLACE = (47.3708689, 8.5418241)
# Run 46115364345 attempt 2's routes (place/requests.jsonl of the emulator-smoke-api35 artifact; the
# other requests left out).
GREEN = [
    route("auto", FIX, PLACE),
    route("pedestrian", FIX, PLACE),
    route("pedestrian", PLACE, FIX),
    route("pedestrian", FIX, PLACE),
    route("pedestrian", (47.37423671279669, 8.545872999984141), PLACE),
    route("pedestrian", FIX, PLACE),
    route("pedestrian", (47.3776896, 8.5436476), PLACE),
    route("pedestrian", (47.3776833, 8.54361), PLACE),
]


def with_start(routes, i, start):
    changed = copy.deepcopy(routes)
    changed[i]["locations"][0] = {"lat": start[0], "lon": start[1]}
    return changed


# Attempt 1's artifact was replaced by attempt 2's; its failure message gave route 6's start, the other
# routes start where attempt 2's do (the same GPS fixes): the new route by itself 8.5 m short of the spot.
RED = with_start(GREEN, 6, (47.3776658, 8.5435012))

expect(place_routes.problems(GREEN) == [], f"attempt 2's routes: {place_routes.problems(GREEN)}")
expect(place_routes.problems(RED) == [], f"attempt 1's routes: {place_routes.problems(RED)}")
expect(7.5 < place_routes.meters((47.3776658, 8.5435012), place_routes.OFF_SPOT) < 9.5, "attempt 1's start is about 8 m from the spot")


def fails(routes, what, mentions):
    found = place_routes.problems(routes)
    expect(any(mentions in p for p in found), f"{what}: wanted a problem naming {mentions!r}, got {found}")


bahnhofquai = (47.377335, 8.541531)
spot = place_routes.OFF_SPOT
fails(with_start(GREEN, 6, bahnhofquai), "new route by itself from Bahnhofquai", "route 6: start")
fails(with_start(GREEN, 7, bahnhofquai), "Find a new route from Bahnhofquai", "route 7: Find a new route")
fails(with_start(GREEN, 6, FIX), "new route by itself from the start's fix", "route 6: start")
fails(with_start(GREEN, 7, FIX), "Find a new route from the start's fix", "route 7: start")
# The fixture route as the app gets it: Bahnhofquai is its point 22, the spot 150 m off it.
shape = place_routes.route_line()
expect(len(shape) == 94 and shape[22] == bahnhofquai, f"the fixture route's line: {len(shape)} points, point 22 {shape[22:23]}")
expect(149 < place_routes.off_line(spot, shape) < 151, "the spot is 150 m off the route")
expect(160 < place_routes.meters(bahnhofquai, spot) < 162, "Bahnhofquai is 161 m from the spot")


def on_the_way(share):
    """The point share of the way from Bahnhofquai to the spot."""
    return (bahnhofquai[0] + share * (spot[0] - bahnhofquai[0]), bahnhofquai[1] + share * (spot[1] - bahnhofquai[1]))


# A first new route by itself from 35 % of the way (51 m off the route; the app's first off-route fix is
# at 34.1 %): it passes, and so does the app asking once more by itself at the spot (105 m on) before
# Find a new route there.
third = on_the_way(0.35)
expect(50 < place_routes.off_line(third, shape) < 52, f"35 % of the way is 51 m off the route: {place_routes.off_line(third, shape)}")
expect(104 < place_routes.meters(third, spot) < 106, "35 % of the way is 105 m from the spot")
expect(place_routes.problems(with_start(GREEN, 6, third)) == [], f"route 6 from 35 % of the way: {place_routes.problems(with_start(GREEN, 6, third))}")
EARLY = with_start(GREEN[:7], 6, third) + [GREEN[6], GREEN[7]]
expect(place_routes.problems(EARLY) == [], f"a first new route from 35 % of the way, the second at the spot: {place_routes.problems(EARLY)}")
fails(with_start(EARLY, 7, on_the_way(0.6)), "asked again by itself 40 m from the first", "route 7: asked again by itself")
fails(RED[:7] + [GREEN[6], GREEN[7]], "asked again by itself 8.5 m from the first (attempt 1's)", "route 7: asked again by itself")
fails(with_start(EARLY, 7, on_the_way(0.33)), "asked again by itself 48 m off the route", "route 7: start")
fails(with_start(EARLY, 8, third), "Find a new route from the first new route's start", "route 8: Find a new route")
fails(EARLY + [GREEN[7]], "a fourth new route", "costings")
# Just short of the app's 50 m: 34 % of the way is 49.9 m off the route.
short = on_the_way(0.34)
expect(49.5 < place_routes.off_line(short, shape) <= 50, f"34 % of the way is 49.9 m off the route: {place_routes.off_line(short, shape)}")
fails(with_start(GREEN, 6, short), "new route by itself 49.9 m off the route", "m from the route's line")
# 170 m east of the spot: 300 m off the route, but farther from the spot than Bahnhofquai.
far = (spot[0], spot[1] + 170 / 75500)
expect(165 < place_routes.meters(far, spot) < 175, "the east point is about 170 m from the spot")
fails(with_start(GREEN, 6, far), "new route by itself 170 m east of the spot", "farther than Bahnhofquai")
fails(with_start(GREEN, 6, place_routes.point(GREEN[4]["locations"][0])), "new route by itself from the dropped pin", "farther than Bahnhofquai")
# Find a new route from the last fix: 30 m short of the spot is not where the user stands.
fails(with_start(GREEN, 7, (spot[0], spot[1] - 30 / 75500)), "Find a new route 30 m short of the spot", "route 7: Find a new route")
fails(with_start(GREEN, 4, FIX), "the dropped pin's route from the fix", "route 4: start")
fails(with_start(GREEN, 4, (46.2, 6.1)), "the dropped pin's route from Geneva", "not the dropped pin")
fails(with_start(GREEN, 5, bahnhofquai), "the second route from the fix from elsewhere", "route 5: start")
fails(GREEN[:7], "no Find a new route", "costings")
fails(GREEN + [GREEN[7]], "a second new route by itself from where the first was", "route 7: asked again by itself")
by_car = copy.deepcopy(GREEN)
by_car[1]["costing"] = "auto"
fails(by_car, "on foot asked by car", "costings")
elsewhere = copy.deepcopy(GREEN)
elsewhere[3]["locations"][1] = {"lat": 47.3708, "lon": 8.5418241}
fails(elsewhere, "a route to somewhere else", "route 3: end")
east = copy.deepcopy(GREEN)
east[5]["locations"][1] = {"lat": 47.3708689, "lon": 8.5419}
fails(east, "a route to a point east of the place", "route 5: end")
not_swapped = copy.deepcopy(GREEN)
not_swapped[2]["locations"].reverse()
fails(not_swapped, "the swapped route not swapped", "route 2:")
agent = copy.deepcopy(GREEN)
agent[6]["ua"] = "okhttp/4.12.0"
fails(agent, "a route without the app's User-Agent", "route 6: User-Agent")

# The command: exit 0 on attempt 1's log (other requests ignored), 1 on a wrong one, 2 on an unreadable one.
with tempfile.TemporaryDirectory() as tmp:
    log = Path(tmp) / "requests.jsonl"

    def run(lines):
        log.write_text("".join(json.dumps(line) + "\n" for line in lines), encoding="utf-8")
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            rc = place_routes.main(["place_routes.py", str(log)])
        return rc, out.getvalue()

    rc, out = run([{"path": "/v1/resources/by-uri"}] + RED + [{"path": "/api/search"}])
    expect(rc == 0 and "9 m from the spot (142 m off the route), 0 m from the spot (150 m off the route)" in out, f"attempt 1's log: {rc} {out!r}")
    rc, out = run(with_start(GREEN, 6, bahnhofquai))
    expect(rc == 1 and "route 6" in out, f"a wrong log: {rc} {out!r}")
    log.write_text("not json\n", encoding="utf-8")
    with contextlib.redirect_stderr(io.StringIO()):
        rc = place_routes.main(["place_routes.py", str(log)])
    expect(rc == 2, f"an unreadable log: {rc}")
    no_locations = copy.deepcopy(GREEN)
    no_locations[3]["locations"] = None
    no_lon = copy.deepcopy(GREEN)
    del no_lon[6]["locations"][0]["lon"]
    for lines in (no_locations, no_lon, ["not an object"]):
        rc, _ = run(lines)
        expect(rc == 2, f"an unreadable route line: {rc} {lines[:1]}")
    with contextlib.redirect_stderr(io.StringIO()):
        rc = place_routes.main(["place_routes.py", str(Path(tmp) / "missing.jsonl")])
    expect(rc == 2, f"a missing log: {rc}")
    # The fixture route missing or without a shape: exit 2, not a pass.
    route_file = place_routes.ROUTE_FILE
    try:
        for content in (None, "{}", '{"trip": {"legs": []}}', '{"trip": {"legs": [{"shape": ""}]}}'):
            place_routes.ROUTE_FILE = Path(tmp) / "route.json"
            if content is None:
                place_routes.ROUTE_FILE = Path(tmp) / "no-route.json"
            else:
                place_routes.ROUTE_FILE.write_text(content, encoding="utf-8")
            rc, _ = run(GREEN)
            expect(rc == 2, f"an unreadable fixture route ({content}): {rc}")
    finally:
        place_routes.ROUTE_FILE = route_file

if failures:
    for failure in failures:
        print(f"::error::place-routes-check: {failure}")
    sys.exit(1)
print("place-routes-check: ok")
