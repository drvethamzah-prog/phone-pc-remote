"""Vercel entrypoint for the Qareeb cloud control plane.

The production data layer will use Supabase through its HTTPS API. This first
endpoint is intentionally small and safe: it proves deployment and never
accepts desktop-control commands.
"""
import json
import os
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import BaseHTTPRequestHandler


class handler(BaseHTTPRequestHandler):
    def json(self, status, value):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def body(self):
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def supabase(self, path, method="GET", payload=None, token=None):
        base = os.environ["SUPABASE_URL"].rstrip("/")
        key = os.environ["SUPABASE_ANON_KEY"]
        headers = {"apikey": key, "Content-Type": "application/json"}
        if token: headers["Authorization"] = "Bearer " + token
        data = json.dumps(payload).encode() if payload is not None else None
        request = Request(base + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=8) as response: return response.status, json.loads(response.read() or b"{}")
        except HTTPError as error: return error.code, json.loads(error.read() or b"{}")

    def do_GET(self):
        if self.path.rstrip("/") in ("", "/health", "/api", "/api/health"):
            return self.json(200, {"ok": True, "service": "qareeb-cloud", "controlPath": False})
        return self.json(404, {"error": "not_found"})

    def do_POST(self):
        try: payload = self.body()
        except Exception: return self.json(400, {"error": "invalid_json"})
        try:
            if self.path.endswith("/auth/register"):
                status, result = self.supabase("/auth/v1/signup", "POST", {"email": payload.get("email", ""), "password": payload.get("password", "")})
                return self.json(status, {"user": result.get("user"), "verificationRequired": not bool(result.get("session")), "error": result.get("msg") or result.get("message")})
            if self.path.endswith("/auth/sign-in"):
                status, result = self.supabase("/auth/v1/token?grant_type=password", "POST", {"email": payload.get("email", ""), "password": payload.get("password", "")})
                return self.json(status, result)
        except KeyError: return self.json(500, {"error": "supabase_environment_missing"})
        return self.json(404, {"error": "not_found"})
