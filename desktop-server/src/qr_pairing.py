"""Short-lived QR enrollment; never starts or stops a server itself."""

import hmac
import ipaddress
from pathlib import Path
import secrets
import threading
import time
from urllib.parse import urlencode


class Enrollment:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.deadline = clock() + 300
        self.expires = int(time.time()) + 300
        self.code = secrets.token_hex(16).upper()
        self.owner = None
        self.consumed = False
        self.lock = threading.Lock()

    def accept(self, code, device_id, requested_token):
        with self.lock:
            if self.clock() >= self.deadline or self.consumed or not requested_token:
                return False
            if not hmac.compare_digest(code.encode(), self.code.encode()):
                return False
            owner = (device_id, requested_token)
            if self.owner is not None:
                return False
            self.owner = owner
            self.consumed = True
            return True

    def uri(self, host, port, fingerprint):
        address = ipaddress.ip_address(host)
        if address.is_loopback or address.is_unspecified or address.is_multicast:
            raise ValueError("QR requires a local LAN address")
        if address.version == 6:
            local = address.is_link_local and "%" not in host
        else:
            local = any(address in ipaddress.ip_network(network) for network in
                        ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"))
        if not local or not 1 <= port <= 65535:
            raise ValueError("QR requires a local LAN endpoint")
        if len(fingerprint) != 64 or any(c not in "0123456789abcdef" for c in fingerprint):
            raise ValueError("Invalid certificate fingerprint")
        return "qareeb://pair?" + urlencode(dict(v=1, host=host, port=port, fp=fingerprint,
                                                 code=self.code, expires=self.expires))


def write_qr(uri, path):
    import qrcode
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    qrcode.make(uri, error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=8, border=4).save(path)


def show_qr(path):
    import tkinter as tk
    from PIL import Image, ImageTk
    root = tk.Tk()
    root.title("Qareeb Control - Local Pairing")
    image = Image.open(path)
    image.thumbnail((520, 520))
    bitmap = ImageTk.PhotoImage(image)
    tk.Label(root, image=bitmap).pack(padx=16, pady=16)
    tk.Label(root, text="Scan with Qareeb Control. Expires in 5 minutes.").pack(pady=(0, 16))
    root.after(300000, root.destroy)
    root.mainloop()


if __name__ == "__main__":
    import sys
    show_qr(sys.argv[1])
