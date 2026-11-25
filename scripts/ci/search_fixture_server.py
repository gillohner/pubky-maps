#!/usr/bin/env python3
"""Serves recorded Photon and Pinakes answers on 127.0.0.1 for the emulator search test, so the
test needs no public network. A query from the fixture set (queries.json) gets its recorded answer,
any other query an empty one. Every request is logged as one JSON line (path, q, User-Agent).

Usage: search_fixture_server.py <port> <fixture dir> <request log>
"""
import json
import sys
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

port = int(sys.argv[1])
fixtures = sys.argv[2]
log_path = sys.argv[3]


def fold(text: str) -> str:
    return " ".join(text.casefold().split())


with open(f"{fixtures}/queries.json", encoding="utf-8") as f:
    SLUGS = {fold(case["query"]): case["fixture"] for case in json.load(f)["queries"]}

EMPTY = {"/api": {"type": "FeatureCollection", "features": []}, "/v1/search": {"items": [], "cursor": None, "reach_truncated": False}}
FILES = {"/api": "photon", "/v1/search": "pinakes"}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802 (http.server's name)
        url = urlparse(self.path)
        q = parse_qs(url.query).get("q", [""])[0]
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"path": url.path, "q": q, "ua": self.headers.get("User-Agent", "")}) + "\n")
        if url.path not in FILES:
            self.send_error(404)
            return
        slug = SLUGS.get(fold(unicodedata.normalize("NFC", q)))
        if slug:
            with open(f"{fixtures}/{slug}.{FILES[url.path]}.json", "rb") as f:
                body = f.read()
        else:
            body = json.dumps(EMPTY[url.path]).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
