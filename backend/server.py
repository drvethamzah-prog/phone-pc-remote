"""Minimal local cloud control-plane for Qareeb Control.

This is a development server: it owns accounts, email verification, sessions,
device metadata, subscription state, and settings-only backup. It never accepts
or forwards desktop control commands.
"""
from __future__ import annotations

import hashlib
import json
import re
import secrets
import sqlite3
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
DB_PATH = ROOT / "data" / "cloud.sqlite3"
SESSIONS: dict[str, str] = {}
EMAIL_CODES: dict[str, tuple[str, float]] = {}
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def db() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    connection.executescript("""
    CREATE TABLE IF NOT EXISTS accounts (
      id TEXT PRIMARY KEY, email TEXT UNIQUE NOT NULL, verified INTEGER NOT NULL DEFAULT 0,
      subscription TEXT NOT NULL DEFAULT 'trial', created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS settings_backup (
      account_id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated_at INTEGER NOT NULL,
      FOREIGN KEY(account_id) REFERENCES accounts(id)
    );
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY, account_id TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL,
      last_seen_at INTEGER NOT NULL, FOREIGN KEY(account_id) REFERENCES accounts(id)
    );
    """)
    return connection


def now() -> int:
    return int(time.time())


def public_account(row: sqlite3.Row) -> dict:
    return {"id": row["id"], "email": row["email"], "verified": bool(row["verified"]),
            "subscription": row["subscription"]}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_args):
        return

    def send_json(self, status: int, payload: dict):
        data = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        if length > 64 * 1024:
            raise ValueError("body_too_large")
        return json.loads(self.rfile.read(length) or b"{}")

    def account(self) -> sqlite3.Row | None:
        authorization = self.headers.get("Authorization", "")
        token = authorization.removeprefix("Bearer ").strip()
        account_id = SESSIONS.get(token)
        if not account_id:
            return None
        return db().execute("SELECT * FROM accounts WHERE id=?", (account_id,)).fetchone()

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/health":
            return self.send_json(200, {"ok": True, "controlPath": False})
        if path == "/v1/me":
            account = self.account()
            return self.send_json(200 if account else 401, {"account": public_account(account)} if account else {"error": "unauthorized"})
        if path == "/v1/settings/backup":
            account = self.account()
            if not account: return self.send_json(401, {"error": "unauthorized"})
            row = db().execute("SELECT payload,updated_at FROM settings_backup WHERE account_id=?", (account["id"],)).fetchone()
            return self.send_json(200, {"settings": json.loads(row["payload"]) if row else {}, "updatedAt": row["updated_at"] if row else None})
        return self.send_json(404, {"error": "not_found"})

    def do_POST(self):
        try: payload = self.body()
        except (ValueError, json.JSONDecodeError) as exc: return self.send_json(400, {"error": str(exc)})
        path = urlparse(self.path).path
        if path == "/v1/auth/register":
            email = str(payload.get("email", "")).strip().lower()
            if not EMAIL_RE.match(email): return self.send_json(400, {"error": "valid_email_required"})
            connection = db(); row = connection.execute("SELECT * FROM accounts WHERE email=?", (email,)).fetchone()
            if row is None:
                account_id = secrets.token_hex(16)
                connection.execute("INSERT INTO accounts(id,email,created_at) VALUES(?,?,?)", (account_id, email, now())); connection.commit()
            else: account_id = row["id"]
            code = f"{secrets.randbelow(1000000):06d}"; EMAIL_CODES[email] = (hashlib.sha256(code.encode()).hexdigest(), time.time() + 600)
            return self.send_json(200, {"accountId": account_id, "email": email, "verificationRequired": True, "devVerificationCode": code})
        if path == "/v1/auth/verify-email":
            email = str(payload.get("email", "")).strip().lower(); code = str(payload.get("code", ""))
            saved = EMAIL_CODES.get(email)
            if not saved or saved[1] < time.time() or not secrets.compare_digest(saved[0], hashlib.sha256(code.encode()).hexdigest()): return self.send_json(400, {"error": "invalid_or_expired_code"})
            connection = db(); connection.execute("UPDATE accounts SET verified=1 WHERE email=?", (email,)); connection.commit(); EMAIL_CODES.pop(email, None)
            return self.sign_in(email)
        if path == "/v1/auth/sign-in":
            email = str(payload.get("email", "")).strip().lower(); account = db().execute("SELECT * FROM accounts WHERE email=?", (email,)).fetchone()
            if not account or not account["verified"]: return self.send_json(403, {"error": "verified_email_required"})
            return self.sign_in(email)
        return self.send_json(404, {"error": "not_found"})

    def sign_in(self, email: str):
        account = db().execute("SELECT * FROM accounts WHERE email=?", (email,)).fetchone(); token = secrets.token_urlsafe(32); SESSIONS[token] = account["id"]
        return self.send_json(200, {"token": token, "account": public_account(account)})

    def do_PUT(self):
        if urlparse(self.path).path != "/v1/settings/backup": return self.send_json(404, {"error": "not_found"})
        account = self.account()
        if not account: return self.send_json(401, {"error": "unauthorized"})
        try: settings = self.body().get("settings", {})
        except (ValueError, json.JSONDecodeError) as exc: return self.send_json(400, {"error": str(exc)})
        if not isinstance(settings, dict) or len(json.dumps(settings)) > 128 * 1024: return self.send_json(400, {"error": "settings_only_object_required"})
        connection = db(); connection.execute("INSERT OR REPLACE INTO settings_backup(account_id,payload,updated_at) VALUES(?,?,?)", (account["id"], json.dumps(settings), now())); connection.commit()
        return self.send_json(200, {"saved": True, "settings": settings})


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(); parser.add_argument("--host", default="127.0.0.1"); parser.add_argument("--port", type=int, default=8787); args = parser.parse_args()
    db().close(); print(f"Qareeb cloud server listening on http://{args.host}:{args.port}")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
