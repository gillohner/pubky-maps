"""A stand-in for the Pubky HTTP relay (http-relay's inbox), for emulator tests.

It answers the requests the app's core makes while it waits for Pubky Ring: a long poll on
`GET /inbox/<channel>` that ends with 408 after a few seconds (no approval ever arrives), and the
health probe `GET /inbox/<channel>/ack` with http-relay's own 404 "Not found". Every request is
recorded (method and path only: the channel id is not a secret, the relay secret never reaches a
relay). The phone reaches it on its own 127.0.0.1 through `adb reverse`.
"""
import http.server
import threading
import time


class FixtureRelay:
    def __init__(self, port, hold_seconds=5):
        self.port = port
        self.hold = hold_seconds
        self.requests = []
        self._lock = threading.Lock()
        relay = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                with relay._lock:
                    relay.requests.append({"method": "GET", "path": self.path, "at": time.time()})
                parts = [p for p in self.path.split("?")[0].split("/") if p]
                if len(parts) == 3 and parts[0] == "inbox" and parts[2] == "ack":
                    self._send(404, b"Not found")
                elif len(parts) == 2 and parts[0] == "inbox":
                    # The long poll: nothing arrives, the relay times out like http-relay does.
                    time.sleep(relay.hold)
                    self._send(408, b"Request Timeout")
                else:
                    self._send(404, b"Not found")

            def do_POST(self):
                n = int(self.headers.get("content-length") or 0)
                if n:
                    self.rfile.read(n)
                with relay._lock:
                    relay.requests.append({"method": "POST", "path": self.path, "at": time.time()})
                self._send(200, b"")

            def _send(self, code, body):
                self.send_response(code)
                self.send_header("content-type", "text/plain")
                self.send_header("content-length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self._server.daemon_threads = True

    @property
    def url(self):
        return f"http://127.0.0.1:{self.port}/inbox"

    def start(self):
        threading.Thread(target=self._server.serve_forever, daemon=True).start()
        return self

    def channel_polls(self):
        """The channels the app listened on (GET /inbox/<channel>)."""
        with self._lock:
            reqs = list(self.requests)
        out = []
        for r in reqs:
            parts = [p for p in r["path"].split("?")[0].split("/") if p]
            if r["method"] == "GET" and len(parts) == 2 and parts[0] == "inbox":
                out.append(parts[1])
        return out

    def snapshot(self):
        with self._lock:
            return list(self.requests)
