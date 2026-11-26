#!/usr/bin/env python3
"""Serves two tiny map styles (light.json, dark.json) for the emulator offline-maps test, so it needs
no public network. Both styles take their vector source from source.json (a TileJSON that the map
must check again at every load, as OpenFreeMap's does after an hour), whose tile addresses carry the
current release of the map data: GET /release/<n> moves them, as a map server does with each release.
Every tile is one water polygon over the whole tile, so a drawn map is the styles' water colour and a
missing tile the background colour. Each tile answers after a short wait, so parallel requests
overlap; every request is logged as one JSON line (path, User-Agent, how many tile requests were
open at once).

Usage: offline_fixture_server.py <port> <request log>
"""
import json
import sys
import threading
import time
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


TILE = water_tile()


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
            self.send(200, TILE, "application/x-protobuf")
        else:
            self.log_line({})
            self.send(404, b"", "text/plain")

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
