#!/usr/bin/env python3
"""Serves the recorded Pinakes and Nexus answers of the place page (shared/src/commonTest/fixtures/
place) on 127.0.0.1 for the emulator place test, so it needs no public network: the Conditorei
Schober's card, its lists, tags, posts and people, and the places in Zürich's old town. Other links
are unknown (404), as on a real Pinakes. Every request is logged as one JSON line (method, path,
uri or uris, User-Agent).

Usage: place_fixture_server.py <port> <fixture dir> <request log>
"""
import json
import sys
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
SCHOBER_ID = next(v["details"]["id"] for v in VIEWS if v["details"]["uri"] == CARD["uri"])


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
        self.log_request_line({"uri": uri})
        path = url.path
        if path == "/v1/resources/by-uri":
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
