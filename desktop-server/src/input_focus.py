"""Demand-driven focus metadata only; never reads names, values or text."""
import hashlib
import platform
import threading
import time

try:
    import comtypes as _comtypes
except ImportError:
    _comtypes = None


def describe(element, api):
    if not element or not element.CurrentIsEnabled or not element.CurrentHasKeyboardFocus:
        return {"editable": False, "focusId": ""}
    if not element.GetCurrentPropertyValue(api.UIA_IsValuePatternAvailablePropertyId):
        return {"editable": False, "focusId": ""}
    pattern = element.GetCurrentPattern(api.UIA_ValuePatternId).QueryInterface(api.IUIAutomationValuePattern)
    if pattern.CurrentIsReadOnly:
        return {"editable": False, "focusId": ""}
    identity = str((element.CurrentProcessId, tuple(element.GetRuntimeId())))
    return {"editable": True, "focusId": hashlib.sha256(identity.encode()).hexdigest()[:24]}


class FocusMonitor:
    def __init__(self):
        self.lock = threading.Lock()
        self.thread = None
        self.requested = 0.0
        self.updated = 0.0
        self.value = {"editable": False, "focusId": "", "supported": False}

    def snapshot(self):
        if platform.system() != "Windows":
            return dict(self.value)
        with self.lock:
            self.requested = time.monotonic()
            if self.thread is None:
                self.thread = threading.Thread(target=self._run, daemon=True, name="input-focus")
                self.thread.start()
            if time.monotonic() - self.updated > 2:
                return {"editable": False, "focusId": "", "supported": False}
            return dict(self.value)

    def _run(self):
        try:
            if _comtypes is None:
                return
            from comtypes.client import CreateObject, GetModule
            _comtypes.CoInitializeEx(0)
            try:
                api = GetModule("UIAutomationCore.dll")
                automation = CreateObject(api.CUIAutomation, interface=api.IUIAutomation)
                while True:
                    with self.lock:
                        active = time.monotonic() - self.requested < 3
                    if active:
                        try:
                            value = describe(automation.GetFocusedElement(), api)
                            value["supported"] = True
                        except Exception:
                            value = {"editable": False, "focusId": "", "supported": False}
                        with self.lock:
                            self.value, self.updated = value, time.monotonic()
                    time.sleep(0.25)
            finally:
                _comtypes.CoUninitialize()
        except Exception:
            # Missing/broken accessibility providers must not disrupt control.
            pass


MONITOR = FocusMonitor()
