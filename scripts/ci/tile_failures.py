#!/usr/bin/env python3
"""The map's load failures in an emulator test's log that an offline area must not have: every style that
failed to load, and every tile that failed although the area's packs hold it (MapLibre's database,
region_tiles). A tile outside the area has nothing to draw from without a server, so its failure is
expected and does not count: a start draws the whole world before the map reaches the area (tile 1/0/0),
and a slow emulator's start can still be drawing it when the test's window opens. A tile line in a form
this script does not read counts.

Prints how many failures count and how many were tiles outside the area, then the lines that count.
Exit 0: none counts; 1: some count; 2: the database is unreadable or holds no tile of an area, so there
was nothing to check against.
CI: emulator-offline.sh (theme switches during an update check); checked by tile-failures-check.py.

Usage: tile_failures.py <logcat.txt> <maplibre.db>
"""
import re
import sqlite3
import sys

STYLE = re.compile(r"loading style failed")
TILE = re.compile(r"Failed to load tile")
TILE_ID = re.compile(r"Failed to load tile (\d+)/(\d+)/(\d+)=>\d+ ")


def held_tiles(db_path):
    """(z, x, y) of every tile an offline pack links, in any style or release."""
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        return set(db.execute("SELECT t.z, t.x, t.y FROM region_tiles r JOIN tiles t ON t.id = r.tile_id"))
    finally:
        db.close()


def failures(lines, held):
    """(the failure lines that count, how many tile failures were outside the area)."""
    counted, outside = [], 0
    for line in lines:
        if STYLE.search(line):
            counted.append(line)
        elif TILE.search(line):
            tile = TILE_ID.search(line)
            if tile is None or tuple(map(int, tile.groups())) in held:
                counted.append(line)
            else:
                outside += 1
    return counted, outside


def main(logcat_path, db_path):
    try:
        held = held_tiles(db_path)
    except sqlite3.DatabaseError as e:
        print(f"unreadable MapLibre database {db_path}: {e}")
        return 2
    if not held:
        print(f"the MapLibre database {db_path} holds no tile of an offline area")
        return 2
    with open(logcat_path, encoding="utf-8", errors="replace") as f:
        counted, outside = failures(f.read().splitlines(), held)
    print(f"{len(counted)} failed (styles, tiles of the area); {outside} tiles outside the area failed")
    for line in counted:
        print(line)
    return 1 if counted else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: tile_failures.py <logcat.txt> <maplibre.db>")
    sys.exit(main(sys.argv[1], sys.argv[2]))
