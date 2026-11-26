#!/usr/bin/env python3
"""Serves two tiny map styles (light.json, dark.json) for the emulator offline-maps test, so it needs
no public network. Both styles take their vector source from source.json (a TileJSON that the map
must check again at every load, as OpenFreeMap's does after an hour), whose tile addresses carry the
current release of the map data: GET /release/<n> moves them, as a map server does with each release.
Every tile is one water polygon over the whole tile, so a drawn map is the styles' water colour and a
missing tile the background colour, and a grid of points labelled as the city "Fixture Town"
(OpenMapTiles' place layer, drawn as dots in the water colour), for the name of a downloaded area;
every zoom 14 tile also holds a point of interest named "Fixture Bakery" (OpenMapTiles' poi layer, an
id of its own per tile), for search in the offline area. POST /overpass answers an Overpass query for
the area's place details as Overpass does (CSV of the query's columns, then its end line): every
bakery in the query's box is open 24/7 and has a phone number. Each tile answers after a short wait, so
parallel requests overlap; every request is logged as one JSON line (path, User-Agent, how many tile
requests were open at once, an Overpass query).

Usage: offline_fixture_server.py <port> <request log>
"""
import json
import math
import re
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
log_path = sys.argv[2]
lock = threading.Lock()
open_tiles = 0
release = 1

WATER = {"/light.json": ("#f2efe9", "#3c78d8"), "/dark.json": ("#2b2d31", "#1c4587")}


def style(background: str, water: str) -> dict:
    return {
        "version": 8,
        "sources": {"fixture": {"type": "vector", "url": f"http://127.0.0.1:{port}/source.json"}},
        "layers": [
            {"id": "background", "type": "background", "paint": {"background-color": background}},
            {"id": "water", "type": "fill", "source": "fixture", "source-layer": "water", "paint": {"fill-color": water}},
            # Dots, not text: a label needs glyphs, and the app finds the places in either.
            {
                "id": "place",
                "type": "circle",
                "source": "fixture",
                "source-layer": "place",
                "paint": {"circle-color": water, "circle-radius": 3},
            },
        ],
    }


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def field(number: int, payload: bytes) -> bytes:
    """A length-delimited protobuf field."""
    return varint(number << 3 | 2) + varint(len(payload)) + payload


def zigzag(value: int) -> int:
    return (value << 1) ^ (value >> 31)


def water_tile() -> bytes:
    """A Mapbox Vector Tile with layer "water": one square a little larger than the tile."""
    edge, size = -64, 4096 + 128
    geometry = [
        1 | 1 << 3, zigzag(edge), zigzag(edge),  # MoveTo
        2 | 3 << 3, zigzag(size), zigzag(0), zigzag(0), zigzag(size), zigzag(-size), zigzag(0),  # LineTo x3
        7 | 1 << 3,  # ClosePath
    ]
    feature = varint(1 << 3) + varint(1) + varint(3 << 3) + varint(3) + field(4, b"".join(varint(v) for v in geometry))
    layer = varint(15 << 3) + varint(2) + field(1, b"water") + field(2, feature) + varint(5 << 3) + varint(4096)
    return field(3, layer)


def place_layer() -> bytes:
    """OpenMapTiles' place layer: a city named "Fixture Town" at each of 8 x 8 points over the tile, so
    a few of them are in the frame at every zoom the area picker shows. Each is a feature of its own,
    as a town's label is."""
    keys = [b"name", b"class"]
    values = [b"Fixture Town", b"city"]
    step = 4096 // 8
    features = b""
    for row in range(8):
        for column in range(8):
            geometry = [1 | 1 << 3, zigzag(step // 2 + column * step), zigzag(step // 2 + row * step)]
            features += field(
                2,
                varint(1 << 3) + varint(1 + row * 8 + column)
                + field(2, b"".join(varint(v) for v in (0, 0, 1, 1)))
                + varint(3 << 3) + varint(1)
                + field(4, b"".join(varint(v) for v in geometry)),
            )
    layer = varint(15 << 3) + varint(2) + field(1, b"place") + features
    layer += b"".join(field(3, key) for key in keys) + b"".join(field(4, field(1, value)) for value in values)
    return field(3, layer + varint(5 << 3) + varint(4096))


def bakery_layer(x: int, y: int) -> bytes:
    """OpenMapTiles' poi layer with one bakery in the middle of tile 14/x/y (OSM node id x*100000+y)."""
    keys = [b"name", b"class", b"subclass"]
    values = [b"Fixture Bakery", b"shop", b"bakery"]
    geometry = [1 | 1 << 3, zigzag(2048), zigzag(2048)]
    feature = (
        varint(1 << 3) + varint((x * 100000 + y) * 10 + 1)
        + field(2, b"".join(varint(v) for v in (0, 0, 1, 1, 2, 2)))
        + varint(3 << 3) + varint(1)
        + field(4, b"".join(varint(v) for v in geometry))
    )
    layer = varint(15 << 3) + varint(2) + field(1, b"poi") + field(2, feature)
    layer += b"".join(field(3, key) for key in keys) + b"".join(field(4, field(1, value)) for value in values)
    return field(3, layer + varint(5 << 3) + varint(4096))


TILE = water_tile() + place_layer()

BAKERY_HOURS = "24/7"
BAKERY_PHONE = "+41 44 000 00 00"


def tile_of(lat: float, lon: float) -> tuple[int, int]:
    """The zoom 14 tile at a point."""
    n = 2**14
    x = int((lon + 180) / 360 * n)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    return x, y


def overpass_answer(query: str) -> bytes:
    """The bakeries of the zoom 14 tiles in the query's box, open 24/7 with a phone, as CSV of the
    query's columns (type, id, then OSM tags), and the line its `make end; out;` gives."""
    columns = re.search(r"\[out:csv\(([^;]*);", query).group(1).split(",")
    south, west, north, east = map(float, re.search(r"\[bbox:([^\]]*)\]", query).group(1).split(","))
    (west_x, north_y), (east_x, south_y) = tile_of(north, west), tile_of(south, east)
    rows = []
    for x in range(west_x, east_x + 1):
        for y in range(north_y, south_y + 1):
            tags = {"::type": "node", "::id": str(x * 100000 + y), '"opening_hours"': BAKERY_HOURS, '"phone"': BAKERY_PHONE}
            rows.append("\t".join(tags.get(column, "") for column in columns))
    rows.append("\t".join(["end", "1"] + [""] * (len(columns) - 2)))
    return ("\n".join(rows) + "\n").encode()


class Handler(BaseHTTPRequestHandler):
    def log_line(self, extra: dict) -> None:
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"path": self.path, "ua": self.headers.get("User-Agent", ""), **extra}) + "\n")

    def send(self, status: int, body: bytes, content_type: str, max_age: int = 86400) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", f"max-age={max_age}")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 (http.server's name)
        global open_tiles, release
        if self.path in WATER:
            self.log_line({})
            self.send(200, json.dumps(style(*WATER[self.path])).encode(), "application/json")
        elif self.path == "/source.json":
            self.log_line({"release": release})
            tilejson = {
                "tilejson": "3.0.0",
                "tiles": [f"http://127.0.0.1:{port}/tiles/{release}/{{z}}/{{x}}/{{y}}.pbf"],
                "minzoom": 0,
                "maxzoom": 14,
            }
            self.send(200, json.dumps(tilejson).encode(), "application/json", max_age=0)
        elif self.path.startswith("/release/"):
            release = int(self.path.rsplit("/", 1)[1])
            self.send(200, b"", "text/plain")
        elif self.path.startswith("/tiles/"):
            with lock:
                open_tiles += 1
                at_once = open_tiles
            self.log_line({"open": at_once})
            time.sleep(0.3)
            with lock:
                open_tiles -= 1
            parts = self.path.split("?")[0].removesuffix(".pbf").split("/")
            body = TILE
            if len(parts) == 6 and parts[3] == "14":
                body = TILE + bakery_layer(int(parts[4]), int(parts[5]))
            self.send(200, body, "application/x-protobuf")
        else:
            self.log_line({})
            self.send(404, b"", "text/plain")

    def do_POST(self):  # noqa: N802 (http.server's name)
        body = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode()
        if self.path == "/overpass":
            query = urllib.parse.parse_qs(body).get("data", [""])[0]
            self.log_line({"query": query})
            self.send(200, overpass_answer(query), "text/csv", max_age=0)
        else:
            self.log_line({})
            self.send(404, b"", "text/plain")

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
