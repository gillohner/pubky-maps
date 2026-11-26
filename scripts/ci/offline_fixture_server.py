#!/usr/bin/env python3
"""Serves two tiny map styles (light.json, dark.json) and their vector tiles on 127.0.0.1 for the
emulator offline-maps test, so it needs no public network. Each tile answers after a short wait, so
parallel requests overlap; every request is logged as one JSON line (path, User-Agent, how many tile
requests were open at once).

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


def style(background: str) -> dict:
    return {
        "version": 8,
        "sources": {
            "fixture": {
                "type": "vector",
                "tiles": [f"http://127.0.0.1:{port}/tiles/{{z}}/{{x}}/{{y}}.pbf"],
                "maxzoom": 14,
            }
        },
        "layers": [
            {"id": "background", "type": "background", "paint": {"background-color": background}},
            {"id": "water", "type": "fill", "source": "fixture", "source-layer": "water"},
        ],
    }


class Handler(BaseHTTPRequestHandler):
    def log_line(self, extra: dict) -> None:
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"path": self.path, "ua": self.headers.get("User-Agent", ""), **extra}) + "\n")

    def send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "max-age=86400")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 (http.server's name)
        global open_tiles
        if self.path in ("/light.json", "/dark.json"):
            self.log_line({})
            colour = "#f2efe9" if self.path == "/light.json" else "#2b2d31"
            self.send(200, json.dumps(style(colour)).encode(), "application/json")
        elif self.path.startswith("/tiles/"):
            with lock:
                open_tiles += 1
                at_once = open_tiles
            self.log_line({"open": at_once})
            time.sleep(0.3)
            with lock:
                open_tiles -= 1
            # An empty tile: no features.
            self.send(200, b"", "application/x-protobuf")
        else:
            self.log_line({})
            self.send(404, b"", "text/plain")

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
