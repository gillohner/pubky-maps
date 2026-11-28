#!/usr/bin/env python3
"""Serves the recorded Pinakes and Nexus answers of the place page (shared/src/commonTest/fixtures/
place) on 127.0.0.1 for the emulator place test, so it needs no public network: the Conditorei
Schober's card, its lists, tags, posts and people, and the places in Zürich's old town. Other links
are unknown (404), as on a real Pinakes. Valhalla's /route answers FOSSGIS' recorded route from
Zürich HB for any request. Panoramax' /api/search answers the federation's recorded answer for a
place in Nantes (fixtures/panoramax) for any place, its thumbnails and viewer copies served here as one small PNG.
Every request is logged as one JSON line (method, path, uri or uris, Pinakes' referenced filter, a
route's costing and locations, Panoramax' place_position, User-Agent).

Usage: place_fixture_server.py <port> <fixture dir> <request log>
"""
import json
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

port = int(sys.argv[1])
fixtures = sys.argv[2]
log_path = sys.argv[3]


def load(name: str):
    with open(f"{fixtures}/{name}", encoding="utf-8") as f:
        return json.load(f)


CARD = load("pinakes-card-schober.json")
SPELLINGS = {CARD["uri"], *CARD["refs"], *CARD.get("aliases", [])}
VIEWS = load("nexus-by-ids-zurich.json")
with open(f"{fixtures}/../search/hauptbahnhof-kunsthaus.valhalla.json", encoding="utf-8") as f:
    ROUTE = json.load(f)
SCHOBER_ID = next(v["details"]["id"] for v in VIEWS if v["details"]["uri"] == CARD["uri"])
with open(f"{fixtures}/../panoramax/search-nantes.json", encoding="utf-8") as f:
    STREET_PHOTOS = json.load(f)
for feature in STREET_PHOTOS["features"]:
    feature["assets"]["thumb"]["href"] = f"http://127.0.0.1:{port}/panoramax/thumb/{feature['id']}.png"
    feature["assets"]["sd"]["href"] = f"http://127.0.0.1:{port}/panoramax/sd/{feature['id']}.png"


def png(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """A PNG of one colour: a stand-in for a photo's thumbnail."""

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    rows = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


THUMBNAIL = png(160, 120, (122, 106, 90))


class Handler(BaseHTTPRequestHandler):
    def answer(self, status: int, body) -> None:
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_request_line(self, extra: dict) -> None:
        with open(log_path, "a", encoding="utf-8") as log:
            line = {"method": self.command, "path": urlparse(self.path).path, "ua": self.headers.get("User-Agent", "")}
            log.write(json.dumps({**line, **extra}) + "\n")

    def do_GET(self):  # noqa: N802 (http.server's name)
        url = urlparse(self.path)
        query = parse_qs(url.query)
        uri = query.get("uri", [None])[0]
        extra = {"uri": uri, "referenced": query.get("referenced", [None])[0]}
        if "place_position" in query:
            extra["place_position"] = query["place_position"][0]
        self.log_request_line(extra)
        path = url.path
        if path == "/api/search":
            self.answer(200, STREET_PHOTOS)
        elif path.startswith(("/panoramax/thumb/", "/panoramax/sd/")):
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(THUMBNAIL)))
            self.end_headers()
            self.wfile.write(THUMBNAIL)
        elif path == "/v1/resources/by-uri":
            if uri in SPELLINGS:
                self.answer(200, CARD)
            else:
                self.answer(404, {"error": "unknown link"})
        elif path == f"/v1/resources/{CARD['id']}/collections":
            self.answer(200, load("pinakes-collections-schober.json"))
        elif path == "/v1/search":
            self.answer(200, load("pinakes-bbox-zurich.json") if "bbox" in query else {"items": [], "cursor": None})
        elif path == f"/v1/resource/{SCHOBER_ID}/replies":
            self.answer(200, load("nexus-replies-schober.json") if query.get("skip", ["0"])[0] == "0" else [])
        elif path == f"/v1/resource/{SCHOBER_ID}/embeds":
            self.answer(200, load("nexus-embeds-schober.json"))
        elif path.startswith("/v1/resource/"):
            self.answer(200, [])
        else:
            self.answer(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
        path = urlparse(self.path).path
        if path == "/route":
            self.log_request_line({"costing": body.get("costing"), "locations": body.get("locations")})
            self.answer(200, ROUTE)
            return
        self.log_request_line({"uris": body.get("uris")})
        if path == "/v1/stream/resources/by_ids":
            self.answer(200, [v for v in VIEWS if v["details"]["uri"] in (body.get("uris") or [])])
        elif path == "/v1/stream/users/by_ids":
            self.answer(200, load("nexus-users.json"))
        elif path == "/v1/stream/posts/by_ids":
            self.answer(200, load("nexus-collection-posts.json"))
        else:
            self.answer(404, {"error": "not found"})

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
