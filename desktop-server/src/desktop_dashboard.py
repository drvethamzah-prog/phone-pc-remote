"""Read-only local server dashboard with reversible sign-in startup settings."""

import hashlib
import json
from pathlib import Path
import queue
import socket
import ssl
import threading
import time

from windows_startup import current_command, set_enabled, startup_command
from dashboard_languages import LANGUAGES, load_language, save_language, translate
from startup_settings import load_mode, save_mode
import credential_store

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
MAX_RESPONSE_BYTES = 65536


def probe_server(port=8765, tls=False, identity_path=None):
    """Only loopback, bounded response, no control command or pairing token."""
    started = time.monotonic()
    connection = socket.create_connection(("127.0.0.1", port), timeout=2)
    try:
        connection.settimeout(2)
        if tls:
            from cryptography import x509
            from cryptography.hazmat.primitives import hashes
            path = identity_path or DATA_DIR / "local-identity.json"
            record = json.loads(Path(path).read_text(encoding="utf-8"))
            certificate = x509.load_pem_x509_certificate(record["certificate"].encode("ascii"))
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.check_hostname = False
            context.load_verify_locations(cadata=record["certificate"])
            connection = context.wrap_socket(connection, server_hostname="localhost")
            actual = hashlib.sha256(connection.getpeercert(binary_form=True)).digest()
            if actual != certificate.fingerprint(hashes.SHA256()):
                raise ssl.SSLError("Computer certificate mismatch")
        connection.sendall(b'{"type":"status"}\n')
        with connection.makefile("rb") as reader:
            line = reader.readline(MAX_RESPONSE_BYTES + 1)
        if not line.endswith(b"\n") or len(line) > MAX_RESPONSE_BYTES:
            raise ValueError("Invalid or oversized status response")
        response = json.loads(line)
        if not isinstance(response, dict) or response.get("type") != "status":
            raise ValueError("Unexpected server response")
        # Never expose arbitrary reply fields or secrets through the UI.
        values = {key: response.get(key) for key in (
            "computerName", "localIp", "localIpv6", "port", "trustedDeviceCount",
            "controlEnabled", "startedAt", "controlsDryRun")}
        values["latencyMs"] = round((time.monotonic() - started) * 1000, 1)
        values["transport"] = "TLS" if tls else "Legacy (unencrypted)"
        return values
    finally:
        connection.close()


def trusted_device_rows(path=None):
    path = Path(path) if path is not None else credential_store.default_path()
    if not path.exists() and path == credential_store.default_path():
        path = DATA_DIR / "trusted-devices.json"
    if not path.exists():
        return []
    record = json.loads(path.read_text(encoding="utf-8"))
    devices = record.get("devices", {})
    if not isinstance(devices, dict):
        raise ValueError("Invalid trusted device store")
    return [(str(device.get("deviceName", "Unknown"))[:120],
             str(device.get("lastSeenAt", ""))[:80])
            for device in devices.values() if isinstance(device, dict)]


def show_dashboard():
    import tkinter as tk
    from tkinter import messagebox, ttk

    language_path = DATA_DIR / "dashboard-settings.json"
    locale = {"code": load_language(language_path)}
    translated_widgets = []
    status_key = ["checking"]
    latest = [None]

    def tr(key):
        return translate(locale["code"], key)

    def localized(widget, key):
        translated_widgets.append((widget, key))
        widget.configure(text=tr(key))
        return widget

    root = tk.Tk()
    root.title("Qareeb Control")
    root.geometry("780x560")
    root.minsize(580, 440)
    frame = ttk.Frame(root, padding=20)
    frame.pack(fill="both", expand=True)
    frame.columnconfigure(1, weight=1)
    frame.rowconfigure(10, weight=1)
    ttk.Label(frame, text="Qareeb Control", font=("Segoe UI", 20, "bold")).grid(row=0, column=0, columnspan=2, sticky="w")
    status = tk.StringVar(value=tr("checking"))
    ttk.Label(frame, textvariable=status, wraplength=540).grid(row=1, column=0, columnspan=2, sticky="w", pady=(4, 16))
    fields = {}
    for row, name in enumerate(("Computer", "Local address", "Transport", "Response time", "Controls", "Trusted phones"), start=2):
        localized(ttk.Label(frame), name).grid(row=row, column=0, sticky="w", padx=(0, 20), pady=4)
        fields[name] = tk.StringVar(value="--")
        ttk.Label(frame, textvariable=fields[name], wraplength=420).grid(row=row, column=1, sticky="w", pady=4)

    tls = tk.BooleanVar(value=False)
    busy = False
    closed = False
    results = queue.Queue()
    enabled = tk.BooleanVar(value=current_command() == startup_command())

    def toggle_startup():
        try:
            set_enabled(enabled.get())
        except OSError as error:
            enabled.set(current_command() == startup_command())
            messagebox.showerror("Qareeb Control", str(error), parent=root)

    options = ttk.Frame(frame)
    options.grid(row=8, column=0, columnspan=2, sticky="ew", pady=12)
    localized(ttk.Checkbutton(options, variable=enabled, command=toggle_startup), "startup").pack(side="left")
    localized(ttk.Checkbutton(options, variable=tls), "probe_tls").pack(side="right")
    localized(ttk.Label(frame, font=("Segoe UI", 11, "bold")), "trusted").grid(row=9, column=0, sticky="w", pady=(4, 8))
    table = ttk.Treeview(frame, columns=("name", "seen"), show="headings", height=5)
    table.heading("name", text=tr("device"))
    table.heading("seen", text=tr("seen"))
    table.column("name", width=240, minwidth=140)
    table.column("seen", width=250, minwidth=160)
    table.grid(row=10, column=0, columnspan=2, sticky="nsew")
    scrollbar = ttk.Scrollbar(frame, orient="vertical", command=table.yview)
    scrollbar.grid(row=10, column=2, sticky="ns")
    table.configure(yscrollcommand=scrollbar.set)

    def refresh():
        nonlocal busy
        if closed or busy:
            return
        busy = True
        refresh_button.state(["disabled"])
        use_tls = tls.get()

        def worker():
            try:
                values = probe_server(tls=use_tls)
                try:
                    devices = trusted_device_rows()
                    error = None
                except (OSError, ValueError):
                    devices, error = [], "devices_error"
                results.put((values, devices, error))
            except (OSError, ValueError, KeyError, ImportError) as error:
                results.put((None, [], "server_error"))

        threading.Thread(target=worker, daemon=True).start()

    def poll():
        nonlocal busy
        if closed:
            return
        try:
            values, devices, error = results.get_nowait()
        except queue.Empty:
            pass
        else:
            busy = False
            refresh_button.state(["!disabled"])
            status_key[0] = error or "available"
            status.set(tr(status_key[0]))
            latest[0] = values
            for value in fields.values():
                value.set("--")
            for item in table.get_children():
                table.delete(item)
            if values:
                fields["Computer"].set(str(values["computerName"]))
                fields["Local address"].set(str(values["localIp"]) + ":" + str(values["port"]))
                fields["Transport"].set("TLS" if values["transport"] == "TLS" else tr("legacy"))
                fields["Response time"].set(str(values["latencyMs"]) + " ms")
                fields["Controls"].set(tr("dry_run" if values["controlsDryRun"] else "enabled" if values["controlEnabled"] else "disabled"))
                fields["Trusted phones"].set(str(values["trustedDeviceCount"]))
                for device in devices:
                    table.insert("", "end", values=device)
        root.after(100, poll)

    def close():
        nonlocal closed
        closed = True
        root.destroy()

    def edit_startup_transport():
        dialog = tk.Toplevel(root)
        dialog.title(tr("startup_transport"))
        dialog.transient(root)
        dialog.grab_set()
        box = ttk.Frame(dialog, padding=20)
        box.pack(fill="both", expand=True)
        try:
            current_mode = load_mode()
        except (OSError, ValueError):
            current_mode = ""
        mode = tk.StringVar(value=current_mode)
        ttk.Label(box, text=tr("startup_notice"), wraplength=420).pack(anchor="w", pady=(0, 12))
        ttk.Radiobutton(box, text=tr("legacy"), variable=mode, value="legacy").pack(anchor="w", pady=4)
        ttk.Radiobutton(box, text="TLS", variable=mode, value="tls").pack(anchor="w", pady=4)

        def apply_mode():
            if mode.get() == current_mode:
                dialog.destroy()
                return
            confirmation = tr("startup_confirm") + ("\n\n" + tr("legacy") if mode.get() == "legacy" else "")
            if not messagebox.askyesno("Qareeb Control", confirmation, parent=dialog):
                return
            try:
                save_mode(mode.get())
            except Exception:
                messagebox.showerror("Qareeb Control", tr("startup_failed"), parent=dialog)
                return
            dialog.destroy()

        buttons = ttk.Frame(box)
        buttons.pack(fill="x", pady=(16, 0))
        ttk.Button(buttons, text=tr("close"), command=dialog.destroy).pack(side="right")
        ttk.Button(buttons, text=tr("apply"), command=apply_mode).pack(side="right", padx=8)

    localized(ttk.Button(frame, command=edit_startup_transport), "startup_transport").grid(
        row=12, column=0, columnspan=2, sticky="w", pady=(12, 0))

    actions = ttk.Frame(frame)
    actions.grid(row=11, column=0, columnspan=2, sticky="ew", pady=(16, 0))
    localized(ttk.Label(actions), "language").pack(side="left", padx=(0, 8))
    selected_language = tk.StringVar(value=LANGUAGES[locale["code"]])
    picker = ttk.Combobox(actions, textvariable=selected_language, values=list(LANGUAGES.values()), state="readonly", width=18)
    picker.pack(side="left")

    def change_language(event):
        code = next(key for key, name in LANGUAGES.items() if name == selected_language.get())
        try:
            save_language(language_path, code)
        except OSError:
            selected_language.set(LANGUAGES[locale["code"]])
            messagebox.showerror("Qareeb Control", tr("save_error"), parent=root)
            return
        locale["code"] = code
        for widget, key in translated_widgets:
            widget.configure(text=tr(key))
        table.heading("name", text=tr("device"))
        table.heading("seen", text=tr("seen"))
        status.set(tr(status_key[0]))
        values = latest[0]
        if values:
            fields["Transport"].set("TLS" if values["transport"] == "TLS" else tr("legacy"))
            fields["Controls"].set(tr("dry_run" if values["controlsDryRun"] else "enabled" if values["controlEnabled"] else "disabled"))

    picker.bind("<<ComboboxSelected>>", change_language)
    localized(ttk.Button(actions, command=close), "close").pack(side="right")
    refresh_button = localized(ttk.Button(actions, command=refresh), "refresh")
    refresh_button.pack(side="right", padx=8)
    root.protocol("WM_DELETE_WINDOW", close)

    def automatic_refresh():
        if not closed:
            refresh()
            root.after(5000, automatic_refresh)

    root.after(100, poll)
    root.after(5000, automatic_refresh)
    refresh()
    root.mainloop()


if __name__ == "__main__":
    show_dashboard()
