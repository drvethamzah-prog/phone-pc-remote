"""Vercel entrypoint for the Qareeb cloud control plane.

The production data layer will use Supabase through its HTTPS API. This first
endpoint is intentionally small and safe: it proves deployment and never
accepts desktop-control commands.
"""
import json
import os
from urllib.parse import urlsplit
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

    def bearer(self):
        value = self.headers.get("Authorization", "")
        return value[7:].strip() if value.lower().startswith("bearer ") else ""

    def do_GET(self):
        if self.path.rstrip("/") in ("", "/health", "/api", "/api/health"):
            return self.json(200, {"ok": True, "service": "qareeb-cloud", "controlPath": False})
        if self.path.rstrip("/") in ("/admin/check", "/api/admin/check"):
            token = self.bearer()
            if not token: return self.json(401, {"error": "missing_session"})
            status, user = self.supabase("/auth/v1/user", token=token)
            if status != 200: return self.json(401, {"error": "invalid_session"})
            user_id = user.get("id", "")
            role_status, rows = self.supabase(
                "/rest/v1/account_members?user_id=eq." + user_id + "&select=role",
                token=token,
            )
            role = rows[0].get("role", "member") if role_status == 200 and rows else "member"
            if role not in ("owner", "admin"): return self.json(403, {"error": "admin_role_required"})
            return self.json(200, {"ok": True, "role": role, "email": user.get("email")})
        if self.path.rstrip("/") in ("/admin/overview", "/api/admin/overview"):
            token = self.bearer()
            if not token: return self.json(401, {"error": "missing_session"})
            status, user = self.supabase("/auth/v1/user", token=token)
            if status != 200: return self.json(401, {"error": "invalid_session"})
            user_id = user.get("id", "")
            role_status, roles = self.supabase("/rest/v1/account_members?user_id=eq." + user_id + "&select=role", token=token)
            role = roles[0].get("role", "member") if role_status == 200 and roles else "member"
            if role not in ("owner", "admin"): return self.json(403, {"error": "admin_role_required"})
            _, devices = self.supabase("/rest/v1/devices?user_id=eq." + user_id + "&select=*", token=token)
            _, phones = self.supabase("/rest/v1/phones?user_id=eq." + user_id + "&select=*", token=token)
            _, audit = self.supabase("/rest/v1/audit_events?user_id=eq." + user_id + "&select=*&order=created_at.desc&limit=50", token=token)
            return self.json(200, {"role": role, "devices": devices, "phones": phones, "audit": audit, "controlPath": "local-only"})
        return self.json(404, {"error": "not_found"})

    def do_POST(self):
        path = urlsplit(self.path).path.rstrip("/")
        try: payload = self.body()
        except Exception: return self.json(400, {"error": "invalid_json"})
        try:
            if path in ("/auth/register", "/api/auth/register"):
                status, result = self.supabase("/auth/v1/signup", "POST", {"email": payload.get("email", ""), "password": payload.get("password", "")})
                return self.json(status, {"user": result.get("user"), "verificationRequired": not bool(result.get("session")), "error": result.get("msg") or result.get("message")})
            if path in ("/auth/sign-in", "/api/auth/sign-in"):
                status, result = self.supabase("/auth/v1/token?grant_type=password", "POST", {"email": payload.get("email", ""), "password": payload.get("password", "")})
                return self.json(status, result)
        except KeyError: return self.json(500, {"error": "supabase_environment_missing"})
        return self.json(404, {"error": "not_found"})

    def do_PUT(self):
        try: payload = self.body()
        except Exception: return self.json(400, {"error": "invalid_json"})
        token = self.bearer()
        if not token: return self.json(401, {"error": "missing_session"})
        status, user = self.supabase("/auth/v1/user", token=token)
        if status != 200: return self.json(401, {"error": "invalid_session"})
        if self.path.rstrip("/") not in ("/settings/backup", "/api/settings/backup"):
            return self.json(404, {"error": "not_found"})
        settings = payload.get("settings", {})
        if not isinstance(settings, dict): return self.json(400, {"error": "settings_must_be_object"})
        user_id = user.get("id")
        status, result = self.supabase("/rest/v1/account_settings", "POST", {
            "user_id": user_id, "settings": settings
        }, token=token)
        if status >= 400: return self.json(status, result)
        return self.json(200, {"ok": True, "scope": "settings-only"})

    def do_DELETE(self):
        token = self.bearer()
        if not token: return self.json(401, {"error": "missing_session"})
        status, user = self.supabase("/auth/v1/user", token=token)
        if status != 200: return self.json(401, {"error": "invalid_session"})
        if self.path.startswith("/api/phones/") or self.path.startswith("/phones/"):
            phone_id = self.path.rstrip("/").split("/")[-1]
            status, result = self.supabase("/rest/v1/phones?id=eq." + phone_id, "PATCH", {"revoked_at": "now()"}, token=token)
            return self.json(200 if status < 400 else status, {"ok": status < 400, "controlPath": "local-only"})
        if self.path.startswith("/api/devices/") or self.path.startswith("/devices/"):
            device_id = self.path.rstrip("/").split("/")[-1]
            status, result = self.supabase("/rest/v1/devices?id=eq." + device_id, "DELETE", token=token)
            return self.json(200 if status < 400 else status, {"ok": status < 400, "controlPath": "local-only"})
        return self.json(404, {"error": "not_found"})
