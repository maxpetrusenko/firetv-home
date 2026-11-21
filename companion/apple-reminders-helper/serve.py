#!/usr/bin/env python3
"""Serve one reminders.json file over HTTP (GET /reminders.json only).

usage: serve.py --file PATH [--bind ADDRESS] [--port PORT]

The default bind address is 127.0.0.1. To let the TV reach it, bind to this Mac's LAN
address and keep the port closed to the internet. The file has no authentication.
"""
import argparse
import http.server
import os


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True, help="path of the reminders.json written by the helper")
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    a = ap.parse_args()
    path = os.path.abspath(a.file)

    class H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.split("?")[0] != "/reminders.json":
                self.send_error(404)
                return
            try:
                with open(path, "rb") as f:
                    data = f.read()
            except OSError:
                self.send_error(503, "reminders file not ready")
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    http.server.ThreadingHTTPServer((a.bind, a.port), H).serve_forever()


if __name__ == "__main__":
    main()
