"""Vercel entrypoint for the Qareeb cloud control plane.

The production data layer will use Supabase through its HTTPS API. This first
endpoint is intentionally small and safe: it proves deployment and never
accepts desktop-control commands.
"""
import os
from http.server import BaseHTTPRequestHandler


class handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.rstrip("/") in ("", "/health", "/api", "/api/health"):
            body = b'{"ok":true,"service":"qareeb-cloud","controlPath":false}'
            self.send_response(200)
        else:
            body = b'{"error":"not_found"}'
            self.send_response(404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        body = b'{"error":"cloud_api_not_configured"}'
        self.send_response(503)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
