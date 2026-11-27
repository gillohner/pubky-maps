#!/usr/bin/env python3
"""Checks scripts/ci/tile_failures.py on logcat lines as the emulator test writes them and on a database in
MapLibre's layout: a failed style and a failed tile of the area count (the lines of run 45960219840, the
map asking the gone server for its cached tiles), a failed tile outside the area does not (a start's
world view), nor does a tile in MapLibre's cache that no pack links; a tile line it cannot read counts;
a database without an area's tile, or not a database, checks nothing and says so.
CI: android.yml, build job.

Usage: tile-failures-check.py
"""
import contextlib
import importlib.util
import io
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("tile_failures", Path(__file__).with_name("tile_failures.py"))
tile_failures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tile_failures)
failures = []


def expect(cond, what):
    if not cond:
        failures.append(what)


tile = "11-27 08:07:12.938 10116 10263 E maplibre-compose: [Style] Failed to load tile {} for source fixture: {}"
okhttp = "unexpected end of stream on com.android.okhttp.Address@b7c20608"
area_z14 = tile.format("14/8580/5737=>16", okhttp)
area_z12 = tile.format("12/2145/1434=>12", okhttp)
world = tile.format("1/0/0=>1", "io: Peer disconnected")
cached_only = tile.format("12/3768/2457=>12", "io: Peer disconnected")
unread = tile.format("14/8580/5737", okhttp)
style = f"11-26 17:27:37.727  9326  9371 E maplibre-compose: [Setup] loading style failed: {okhttp}"
noise = "11-27 08:07:13.000 10116 10116 I maplibre-compose: [Style] tile 14/8580/5737 loaded"
held = {(0, 0, 0), (1, 1, 0), (12, 2145, 1434), (14, 8580, 5737)}

counted, outside = tile_failures.failures([area_z14, world, noise, area_z12], held)
expect(counted == [area_z14, area_z12] and outside == 1, f"area tiles and the world view: {counted} {outside}")
counted, outside = tile_failures.failures([style, world], held)
expect(counted == [style] and outside == 1, f"a failed style: {counted} {outside}")
counted, outside = tile_failures.failures([unread], held)
expect(counted == [unread] and outside == 0, f"a tile line without its id: {counted} {outside}")
counted, outside = tile_failures.failures([noise], held)
expect(counted == [] and outside == 0, f"no failure: {counted} {outside}")

work = Path(tempfile.mkdtemp())


def database(name, linked, cached):
    """A database with MapLibre's tiles and region_tiles tables: `linked` in a pack, `cached` only cached."""
    path = work / name
    db = sqlite3.connect(path)
    db.execute("CREATE TABLE tiles (id INTEGER PRIMARY KEY, url_template TEXT, pixel_ratio INTEGER, z INTEGER, x INTEGER, y INTEGER)")
    db.execute("CREATE TABLE region_tiles (region_id INTEGER, tile_id INTEGER)")
    for n, (z, x, y) in enumerate(sorted(linked) + sorted(cached), start=1):
        db.execute("INSERT INTO tiles VALUES (?, 'http://127.0.0.1:18493/tiles/2/{z}/{x}/{y}.pbf', 1, ?, ?, ?)", (n, z, x, y))
        if n <= len(linked):
            db.execute("INSERT INTO region_tiles VALUES (1, ?)", (n,))
    db.commit()
    db.close()
    return path


def run(lines, db):
    log = work / "logcat.txt"
    log.write_text("\n".join(lines) + "\n", encoding="utf-8")
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = tile_failures.main(log, db)
    return code, out.getvalue()


area = database("area.db", held, {(12, 3768, 2457)})
expect(tile_failures.held_tiles(area) == held, f"held tiles: {tile_failures.held_tiles(area)}")
code, out = run([world, cached_only, noise], area)
expect(code == 0 and out.startswith("0 failed") and "2 tiles outside the area" in out, f"outside the area: {code} {out}")
code, out = run([world, area_z14, area_z12], area)
expect(code == 1 and out.startswith("2 failed") and area_z14 in out and area_z12 in out and world not in out, f"area tiles: {code} {out}")
code, out = run([world], database("empty.db", set(), {(12, 3768, 2457)}))
expect(code == 2 and "holds no tile" in out, f"no area in the database: {code} {out}")
torn = work / "torn.db"
torn.write_bytes(b"not a database, " * 64)
code, out = run([world], torn)
expect(code == 2 and out.startswith("unreadable"), f"not a database: {code} {out}")
shutil.rmtree(work)

if failures:
    for failure in failures:
        print(f"::error::tile-failures-check: {failure}")
    sys.exit(1)
print("tile-failures-check: ok")
