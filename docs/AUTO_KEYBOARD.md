# Automatic phone keyboard

Android 0.37 opens its native keyboard on a new editable-field identity while
connected, controls enabled and the Control tab foreground. This setting defaults
on and can be disabled. Manual keyboard access remains available. Focus means
clicking/selecting the input, not hovering over it. Dismissing the keyboard does
not reopen it until focus changes. Blur does not automatically hide the keyboard.

The phone queries input_focus every 750 ms with its existing credentials and
saved endpoint/pin, using bounded 1-second connect/read timeouts. Session changes
discard old replies. No reconnect is initiated on an optional-probe error.
Detection is not instantaneous and may take more than one poll on startup.

Windows requires comtypes from requirements.txt. A dedicated daemon owns its UI
Automation COM apartment and samples at 250 ms only while recently queried.
Providers cannot block input-control threads. Cached results older than two
seconds are unknown. A hung provider currently disables detection until server
restart; it does not restart threads unboundedly.

Detection requires enabled, focused controls with a writable ValuePattern.
Read-only controls are excluded. Some custom editors, document-based controls,
elevated applications or inaccessible browser providers may not be detected.
No screen capture or field text/value/name is collected. Identity is a truncated
hash of process/runtime identifiers; it is not stored in preferences or logs.

Validation: five metadata/classification tests, authenticated loopback transport
coverage (including unauthorized and controls-disabled rejection), total 31
Python tests; build/lint passed and a real Windows monitor returned supported.
No focus was stolen from the user's current application. Android UI end-to-end
and browser/editor compatibility still require phone testing. The old server
process must restart to load the new command; an APK update alone is insufficient.

Reference: https://learn.microsoft.com/en-us/windows/win32/api/uiautomationclient/nf-uiautomationclient-iuiautomation-getfocusedelement
