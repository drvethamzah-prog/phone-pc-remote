# Changelog

## 0.48.0-connection-notice

- Android 0.41 replaces the long red technical banner with a restrained status
  band: icon, short title, localized hint and Devices action. Offline status is
  shown even without an error; connection warnings use a neutral summary.
- Full status remains in the collapsed Diagnostics section of Devices; no
  endpoint/exception dump in the global banner. Transport/authentication unchanged.
- Build/lint and test APK generation passed. Native notice test checks concise
  content and preservation of collapsed diagnostic detail, with screenshot output.
- API-35 notice tests passed in English and Arabic dark mode; both screenshots
  were visually inspected for layout/RTL. Physical-phone verification pending.

## 0.47.0-qr-cancel

- Android 0.40 uses a non-exported portrait PairingCaptureActivity, preserving
  ZXing camera lifecycle while adding a visible localized Cancel button.
  Cancellation returns RESULT_CANCELED without importing QR or editing profiles.
- Scanner regression test clicks the real button and checks that saved settings
  remain unchanged. Build/test APK and lint passed; physical-phone check pending.
- API-35 native scanner launch, portrait and visible Cancel-button click checks
  passed; cancellation finished the scanner with saved preferences unchanged.

## 0.46.0-portrait-qr

- Android 0.39 overrides ZXing's landscape activity orientation with portrait.
  Disable its runtime orientation lock so launch rotation cannot override that
  manifest policy. Add a native scanner portrait-orientation assertion.
- Build/test APK and lint passed. API-35 scanner launch/portrait check passed
  with the emulator initially landscape and camera permission granted. A repeat
  denial test could not find the system denial button in persisted permission
  state, so that repeat is inconclusive (0.38 denial test previously passed).

## 0.45.0-qr-scanner-fix

- Android 0.38 fixes a reproduced scanner-launch crash: ZXing CaptureManager
  referenced androidx.core.content.ContextCompat absent from the packaged app.
  Explicitly package AndroidX Core 1.13.1 and enable AndroidX in Gradle.
- Add native scanner-launch regression coverage, including camera permission
  denial and permission-granted launch. No pairing credentials are reset.
- Debug/test builds and lint passed. QR camera decoding and encrypted pairing
  still require the user's physical-phone test; launch success is not pairing.

## 0.44.0-auto-keyboard

- Android 0.37 optionally opens the native keyboard when a new editable Windows field gains focus. Default enabled; the existing manual keyboard button remains. Manual dismissal does not reopen it for the same field. No automatic hiding on blur.
- Foreground Control-tab-only polling uses the saved authenticated endpoint, captured TLS pin and session-generation guards. Probe failures do not trigger reconnection. Old servers are safely ignored.
- Authenticated input_focus requires enabled controls. Demand-driven Windows UI Automation reads only enabled/focus/value-pattern/read-only properties and an opaque field identity, never text, names or values. A daemon caches state; stale/provider failures fail closed. Requires comtypes 1.4.17. Some custom/document controls without ValuePattern are not detected.
- Includes the optional adaptive connection switch and isolated policy/TCP/queue tests documented in docs/ADAPTIVE_CONNECTION.md.
- Build/lint and 31 focus/transport Python tests passed; Windows accessibility monitor smoke test passed. Physical phone automatic-opening and app-specific compatibility remain unverified. Running production server needs restart to load this feature.

## 0.43.0-computer-profiles

- Android 0.36 adds My Computers: saved name, endpoint, last successful connection and a truthful Connected/Saved state, with Connect, Add Computer and confirmed local Forget actions. Saved does not claim the computer is currently reachable.
- Up to 20 independent profiles retain their port, certificate pin and scoped IPv6 cache. Each profile token uses Android Keystore AES-GCM with its profile-specific preference key as AAD. Selection and confirmed pairing commit active context and encrypted credentials together.
- The existing active pairing migrates without changing its token. Connection switches cancel pending recovery/handshakes/queued controls; response generation guards ignore old callbacks. Address edits require a fresh handshake before authenticated commands can be sent. Pending enrollment credentials are namespaced per profile and legacy pending credentials migrate when used.
- Local Forget removes that computer's saved token and profile, not other computers. It does not revoke the phone's authorization on the PC; the separate server-side removal command remains available.
- Requests capture their TLS pin when queued/dispatched; an old request does not borrow the newly selected computer's pin. Stale control sessions close on generation changes. Forget also clears selected-profile pending secrets and matching legacy pending credentials.
- Final API-35 emulator runs passed 108 checks in English and 108 in Arabic, including real Activity add/switch/reconnect/forget behavior plus isolated storage tests. Both screenshots were inspected; debug/test builds and lint passed, and all 75 Python tests passed in separate processes. Initial Arabic test-snapshot timeout was resolved on rerun after matching the harness backlog to the production listener backlog.
- No production server restart, network configuration change or physical phone installation. This is still a debug/test APK, not a signed commercial release. Physical multi-PC and phone-upgrade validation remain pending.

## 0.42.0-server-resource-guards

- Bound incoming TCP frames to 16 KiB including the newline, before JSON parsing. Oversized frames are rejected and closed without draining the entire payload; EOF-terminated incomplete frames are rejected as well.
- Share an atomic admission budget across IPv4 and IPv6 listeners: 64 active workers globally and 16 per source address. Reject excess sockets before thread creation or TLS negotiation; release slots after handler failures and disconnects. Existing socket read and TLS handshake timeouts remain in force.
- Reject shutdown scheduling while controls are disabled. Trusted cancellation stays available so an earlier shutdown can still be reversed. No actual shutdown command was executed during tests.
- 75 Python tests passed in separate test-file processes, including real loopback oversized/incomplete frames, excess connections, recovery after closure, concurrent admission, and dry-run power behavior. Combined Tkinter discovery cleanup remains a known test-runner issue. Android remains at 0.35; no new APK is required for these server-only changes.
- Source only: the production server was not restarted. These limits reduce resource exposure but do not guarantee availability under hostile traffic. Mandatory TLS, initial-pairing authorization, Android response-size limits and stronger power-command permissions remain pending.

## 0.41.0-protected-credentials

- Windows trusted-device tokens now use user-scope DPAPI in a private user-profile directory. Legacy migration preserves pairing and removes the plaintext store only after verifying the encrypted copy. Seven isolated tests cover migration, failures, identity binding, corruption and actual directory ACLs; 67 Python tests passed across separate test-file runs (combined Tkinter discovery has a cleanup crash).
- Android 0.35 encrypts confirmed and pending pairing credentials with AES-GCM using Android Keystore. Preference names are authenticated as AAD. Existing plaintext credentials migrate together; corrupted ciphertext or a missing key blocks credential use without deleting or silently replacing saved pairing.
- Credential writes are committed before connection success is published. Storage errors have English/Arabic messages. This does not activate TLS or harden legacy pairing codes.
- Debug APK, Android-test APK and lint built successfully. API-35 emulator instrumentation passed 80 checks in English and 80 in Arabic against an isolated recording server, including Keystore migration/tamper/missing-key tests, IME and Wi-Fi recovery. Initial emulator Wi-Fi was disconnected; the first Arabic run later hit a transient test-snapshot connection timeout, and its complete rerun passed. This is not physical-device or release-signing verification.
- No production server restart, production credential migration, live ACL change or phone installation was performed. Physical phone upgrade verification remains pending.

## 0.40.0-secure-startup

- Persist an explicit next-launch legacy/TLS setting independently of sign-in registration and diagnostic transport. The desktop editor requires confirmation and validates an existing TLS identity before saving TLS mode.
- Startup launcher adds `--tls --require-existing-identity` for secure mode. Invalid/unreadable settings or missing/invalid TLS identity block startup rather than silently launching plaintext or replacing the identity. Logs contain generic diagnostics, not key material.
- Atomic runtime-only settings, excluded from distribution. Missing settings preserve existing legacy installations; no configuration was activated or server restarted on the user's computer.
- 60 Python tests cover startup arguments, validation failure, no downgrade, preference persistence, existing identity loading and previous transport/UI behavior. Actual secure sign-in/reboot and physical Android tests remain pending. Android stays at 0.34.

## 0.39.0-usb-network-policy

- Android 0.34 adds a persisted Automatic / USB-network-only experimental selector. USB transport is considered only on API 31+ when Android exposes it as an app-usable Network. USB-only mode never falls back to Wi-Fi, Ethernet, cellular or VPN.
- Policy rejects cellular and VPN bearers, including mixed transport capabilities. Existing local sockets/discovery remain bound through Android Network APIs; no raw interface guess, ADB, root, network-route changes or automatic tethering activation.
- 51 Python tests passed, including a compiled Java matrix of 96 network-policy combinations. Android debug build/lint also passed after the final UI messaging refinement.
- This is NOT verified generic USB tethering support: tethering downstream interfaces may not appear as Android Network objects. Physical Samsung/Windows cable testing and disconnect/recovery/permission tests remain pending. Bluetooth and direct USB accessory protocols remain unimplemented.

## 0.38.0-language-selection

- English is the primary/default UI language. Android 0.33 changes the new-install default from Arabic to English without overriding explicitly saved language choices. Keyboard input language and transport remain unchanged.
- Desktop adds an explicit language picker, immediate label/status translation and atomic preference persistence. Initial desktop catalogs: English, Arabic, French, Spanish, German and Simplified Chinese, with English fallback.
- Android currently offers English, Arabic and system-language selection only; the other desktop catalogs are not Android translations. All-world language coverage is a roadmap objective, not a completed feature.
- Test catalog completeness, defaults, malformed preference recovery, persistence and unsupported choices. Native typography/RTL visual QA and professional translation review remain pending.

## 0.37.0-desktop-dashboard

- Add a resizable native desktop dashboard with asynchronous loopback status checks, five-second refresh, transport indication, response time, controls state and trusted-device last-seen table. Device count indicates trust, not active connections.
- Existing startup settings now open the dashboard; opening/closing it never starts/stops the server or changes startup settings. Only an explicit startup checkbox change modifies the per-user registration.
- Support explicit legacy/TLS diagnostic selection without changing server mode or silently falling back. TLS diagnostics trust the local certificate and do not decrypt/read the private key.
- 46 Python tests passed, including real legacy/TLS status, bounded/malformed response handling, secret filtering and hidden-window smoke testing. Native visual QA and Arabic desktop localization remain pending. Android stays at 0.32.

## 0.36.0-local-qr

- Android 0.32 adds an embedded QR scanner with optional camera hardware, an explicit computer confirmation and strict local-only payload validation. Fingerprint, local address, port and temporary ticket are imported together; TLS is mandatory after confirmation.
- Desktop `--tls --pairing-qr` generates and displays a five-minute 128-bit enrollment ticket. Static pairing code is disabled in this mode; the ticket permits one phone/requested-token tuple with idempotent retries. Existing trusted reconnects remain available after ticket expiration.
- 39 Python tests passed, including Java payload compatibility, malformed/nonlocal payload rejection, ticket expiration and concurrent claims. Android debug build and lint passed. Camera/physical-phone scanning and native visual QA remain pending.
- No production server restart, TLS migration, remote-session interruption, startup changes, or installer changes were performed. QR generation is currently a startup-only development option, not a live desktop management UI.

## 0.35.0-pinned-tls

- Opt-in desktop `--tls` mode encrypts all TCP commands on IPv4 and scoped link-local IPv6, with worker-thread TLS handshakes and no plaintext fallback. Legacy mode remains the default during migration.
- Android 0.31 adds a manually verified SHA-256 certificate fingerprint setting. All local TCP paths require TLS after saving a pin, independent of changes to the computer IP. Discovery is not a source of trust. No automatic certificate acceptance.
- Cross-language integration verifies Java control transport against the real Python handler: trusted hello and Arabic input in dry-run mode, wrong pin rejection, and plaintext rejection. Python suite: 34 tests. Android debug build and lint passed; physical TLS migration and native UI verification remain pending.
- Windows startup remains in its previous legacy configuration. Do not enable a phone pin until its desktop is deliberately migrated to TLS. QR enrollment, certificate renewal, local approval and secure phone token storage remain future work.

## 0.34.0-local-ipv6

- Android 0.30.0-local-ipv6 remembers up to eight link-local IPv6 endpoints from successful pairing/hello responses and verifies the saved secret before using them on reconnect. IPv4 remains available if IPv6 fails.
- Re-scope link-local addresses to the phone's current local interface; never reuse the desktop's interface index or persist a foreign zone identifier.
- Desktop listens only on enumerated scoped link-local IPv6 addresses, not IPv6 wildcard/global addresses. IPv4 and IPv6 share the same trust/control state. Refresh listeners when local interfaces change; custom IPv4 bind configurations do not implicitly enable IPv6.
- Preserve the existing approved IPv4 host route until physical-phone IPv6 control is verified. An IPv6 ICMP reply is not proof of application connectivity. First-contact IPv6 discovery/mDNS and secure TLS pairing remain future work.

## 0.33.0-local-recovery

- Android 0.29.0-local-recovery observes link/network changes, cancels stale requests and queued input, and retries only the handshake with a bounded, jittered foreground recovery cycle.
- Add a foreground-only signed health probe every five seconds to detect an idle server failure or a missed network event; no input is sent by the probe.
- Bind TCP/UDP sockets to local Wi-Fi/Ethernet, including Wi-Fi without Internet validation. Reject public control destinations, cellular-only transport and active default VPN; never fall back to Internet control.
- Use actual IPv4 prefix lengths for broadcast and limit TCP scanning to the current /24 window intersected with the real subnet, rather than guessing adjacent networks.
- Rediscover the trusted server using nonce-bound HMAC-SHA256 proof of the existing pairing secret. Use the UDP packet source, not the server's advertised interface IP. Verify proof on a persistent TCP identify/hello connection before reconnecting with a saved secret.
- Requires the updated desktop server for automatic reconnection. Legacy phone clients remain supported by the updated server. Discovery proof is not encryption; TLS and secure initial pairing remain commercial-release blockers.
- The host-specific Windows route remains a local workaround, not an automatic product feature. Network isolation/asymmetric routing is not repaired by rediscovery or retries.

## 0.32.0-pairing-response

- Send hello on a one-shot connection rather than requesting persistence on a disposable socket; retain persistent transport for input commands.
- Use the existing 7-second response timeout for pairing instead of the shorter 3-second timeout. Preserve bounded handshake-only retries and saved/candidate credentials.
- Add regression checks for a hello reply followed by EOF and Android's one-shot hello flag. The reported physical-phone response timeout still requires validation on that phone; its root cause is not confirmed.
- Android app version: 0.28.0-pairing-response.

## 0.31.0-media-remote

- Redesign Media as a compact, centered remote with a prominent circular play/pause key, previous/next keys, a vertical volume/mute rocker, and icon-led shortcut keys.
- Remove the oversized music placeholder; constrain remote width and allow scrolling on small screens or with larger text. Retain Arabic/English labels and existing control commands.
- Add instrumentation checks that click all nine remote buttons and verify server-side key codes and shortcut modifiers without affecting the real Windows desktop.
- Android app version: 0.27.0-media-remote.

## 0.30.0-polished-devices

- Refine Devices with a primary connection action, outlined secondary buttons, paired port/code fields, icon-led management rows, and collapsed diagnostics.
- Add persistent Arabic, English, and system-language choices in Devices and Settings; localize UI labels, accessibility descriptions, confirmations, and status messages through Android resources.
- Follow locale direction and retain the selected screen when changing language; preserve saved pairing credentials.
- Android app version: 0.26.0-polished-devices. Native IME/transport checks also cover locale labels, layout direction, and pairing preservation.

## 0.29.0-live-dashboard

- Fix Windows Unicode injection: use the complete Win32 INPUT union (40 bytes on x64) and UTF-16 surrogate pairs. Add four keyboard regression tests; verify real SendInput acceptance with a non-typing key-up probe.
- Replace the long control form with Control, Media, and Devices screens; retain pairing and transport behavior.
- Use a flexible touchpad with the native Android keyboard, compact shortcuts, and an always-visible stop/resume action.
- Forward IME composing text before word confirmation; reconcile corrections and deletion without repeating committed words.
- Clear local composition after target changes, connection failure, and stop; reject queued edit batches atomically when full.
- Handle Android system/keyboard insets and follow the system light/dark appearance; bundle licensed Lucide icons offline.
- Add pure-Java delta tests and Android instrumentation against an isolated recording server.
- Verified native Gboard letter-by-letter input and deletion on the API 35 emulator; Samsung keyboard validation on the physical phone remains pending.
- Android version: `0.25.0-live-dashboard`. No production server protocol change is required.

## 0.28.0-confirmed-pairing

- Remove the timeout fallback that replaced a confirmed credential with an unused token.
- Serialize pairing/reconnection attempts and retain one pending candidate per endpoint.
- Retry a lost handshake reply once with the same credentials; only persist a confirmed token.
- Keep handshake sockets open until the client receives the reply and distinguish connect/read timeouts.
- Add version/request correlation and response timing to server handshake diagnostics without logging tokens.
- Android version: `0.24.0-confirmed-pairing`.

## 0.27.0-low-latency

- Keep control commands on one ordered TCP connection with TCP_NODELAY.
- Coalesce pending pointer movements and discard stale motion after a network stall.
- Bound the input queue and clear it on transport failure without replaying commands.
- Serialize trusted-device writes and persist last-seen activity at most every 30 seconds.
- Preserve one-request compatibility for existing clients; add an in-app RTT measurement.
- Clear stale timeout status when a new control command succeeds.
- Android version: `0.23.0-low-latency`.

## 0.26.0-pairing-token-fallback

- جعل الهاتف يرسل `requestedToken` أثناء الاقتران، ويستطيع حفظه احتياطيا إذا ضاع رد السيرفر وظهرت مهلة.
- جعل Desktop Server يحفظ `requestedToken` الصالح عند الاقتران بدل الاعتماد دائما على token مولد من السيرفر.
- رفع نسخة Android إلى `0.22.0-pairing-token-fallback`.

## 0.25.0-default-pairing-flow

- جعل تطبيق Android يملأ رمز الاقتران `111111` افتراضيا.
- جعل زر `اتصال ذكي` ينفذ الاقتران مباشرة إذا كان رمز الاقتران موجودا.
- إضافة زر `نسيان محلي` لمسح token المحفوظ على الهاتف والبدء من اقتران جديد.
- جعل أمر الرمز في السيرفر يعيد تثبيت الرمز المحدد عند التشغيل بدلا من توليد رمز عشوائي أثناء الاختبار.
- إضافة تسجيل تشخيصي في Desktop Server لنوع الرسالة الواردة أو الاتصال الفارغ.
- إضافة `desktop-server/src/capability_check.py` لفحص قدرة البروتوكول آليا في وضع dry-run.
- رفع نسخة Android إلى `0.21.0-default-pairing-code`.

## 0.24.0-fixed-pairing-code

- تثبيت رمز الاقتران الافتراضي في Desktop Server على `111111` لتسهيل تجربة الهاتف الحقيقي.
- إضافة خيار `--pairing-code` لتغيير الرمز عند تشغيل السيرفر بدون تعديل الكود.

## 0.23.0-pairing-resilience

- تحسين صمود الاقتران وإعادة الاتصال في Android: مهلة أطول لرسائل `hello`.
- منع تكدس أوامر الاتصال خلف فحص الشبكة الطويل بتحويل منفذ التنفيذ الداخلي إلى عدة خيوط.
- تحسين رسالة انتهاء المهلة لتوضح أن الحل هو البحث ثم الاقتران أو إعادة الاتصال.
- رفع نسخة Android إلى `0.20.0-pairing-resilience`.

## 0.22.0-world-class-baseline

- إضافة معيار منتج عالمي لـ `Qareeb Control` مبني على مقارنة مباشرة مع Unified Remote وKDE Connect وPhone Link.
- تحسين واجهة Android: هوية بصرية أوضح، حالة اتصال مصقولة، زر `اتصال ذكي`، حقول وأزرار أكثر احترافية، وتراكباد بصري أفضل.
- تثبيت اتجاه الواجهة RTL بما يناسب الواجهة العربية.
- رفع نسخة Android إلى `0.19.0-world-class-ui`.

## 0.21.0-qareeb-control-plan

- اعتماد اسم المنتج الجديد `Qareeb Control` مع إبقاء اسم المجلد والحزمة التقنية `phone-pc-remote` مؤقتا للتوافق.
- تحديث عنوان تطبيق Android واسم السيرفر وموقع الدعم المحلي.
- مراجعة خطة المشروع لتطابق الواقع الحالي: الهاتف الفعلي اتصل، والماوس يعمل، والبند المفتوح هو تثبيت الكيبورد اللحظي.
- تحويل أزرار الأحرف والأرقام في Android إلى `key_press` مباشر، وجعل `Space` يرسل مفتاح المسافة مباشرة.
- إضافة fallback في Desktop Server لإدخال الأحرف عبر ضغطات مفاتيح Windows قبل مسار Unicode.
- رفع نسخة Android إلى `0.18.0-qareeb-keyboard`.

## 0.20.0-wide-discovery

- توسيع فحص البحث TCP في Android ليشمل النطاق الحالي والنطاقات المجاورة للـ Wi-Fi.
- رفع عدد خيوط الفحص إلى 64 وتمديد نافذة البحث إلى 12 ثانية.
- يستهدف هذا حالة وجود الهاتف على `10.10.11.x` والحاسوب على `10.10.10.x`.
- رفع نسخة Android إلى `0.17.0-wide-discovery`.

## 0.19.0-auto-reconnect

- إضافة إعادة اتصال تلقائية عند فتح تطبيق Android إذا كان token محفوظا.
- جعل زر الاقتران يعيد الاتصال عند ترك رمز الاقتران فارغا وكان الهاتف موثوقا سابقا.
- تحسين رسالة فشل الاتصال لتعرض `host:port` المستهدف.
- رفع نسخة Android إلى `0.16.0-auto-reconnect`.

## 0.18.0-pairing-stability

- جعل Desktop Server يغلق اتصال TCP بعد معالجة رسالة واحدة، بما يطابق نمط تطبيق Android ويمنع اتصالات معلقة.
- زيادة مهلة اتصال Android العادية إلى 5 ثوان.
- جعل زر الاقتران يعتبر `hello_ok` نجاحا إذا كان الهاتف موثوقا سابقا.
- رفع نسخة Android إلى `0.15.0-pairing-stability`.

## 0.17.0-control-surface

- إعادة ترتيب واجهة التحكم في Android إلى قسم ماوس مباشر وقسم لوحة مفاتيح مباشرة.
- إضافة لوحة مفاتيح أزرار داخل التطبيق: الأرقام والحروف وبعض الرموز تكتب فور الضغط عليها عبر `type_text`.
- إبقاء زر `فتح كيبورد الهاتف` للكتابة العربية أو الرموز الإضافية مع الإرسال اللحظي.
- رفع نسخة Android إلى `0.14.0-control-surface`.

## 0.16.0-discovery-scan

- تحسين زر `بحث` في Android: يبدأ بـ UDP discovery ثم ينتقل تلقائيا إلى فحص TCP للشبكة المحلية عند فشل البث.
- إضافة صلاحية `ACCESS_WIFI_STATE` لاشتقاق نطاق Wi-Fi المحلي للفحص.
- فحص المنفذ `8765` عبر رسالة `status` بدون حاجة إلى UDP broadcast.
- رفع نسخة Android إلى `0.13.0-discovery-scan`.

## 0.15.0-hidden-keyboard

- إزالة حقل الكيبورد الظاهر من واجهة Android.
- إضافة زر `فتح الكيبورد` يفتح لوحة مفاتيح الهاتف ويرسل الإدخال للحاسوب عبر مستقبل داخلي مخفي.
- اعتراض `Backspace` و`Enter` من لوحة المفاتيح مباشرة بجانب الأزرار اليدوية.
- رفع نسخة Android إلى `0.12.0-hidden-keyboard`.

## 0.14.0-live-keyboard

- تحويل حقل الكيبورد في Android إلى كتابة مباشرة بدون زر إرسال نص.
- إرسال الحروف الجديدة فور كتابتها عبر `type_text`.
- إرسال الحذف من لوحة الهاتف كأوامر `backspace` متتابعة.
- إضافة زر `مسح الحقل` لمسح نص الهاتف فقط بدون إرسال حذف للحاسوب.
- رفع نسخة Android إلى `0.11.0-live-keyboard`.

## 0.13.0-power-controls

- إضافة أمر `shutdown_computer` في Desktop Server لإطفاء الحاسوب بعد اقتران صالح وتأكيد صريح.
- إضافة أمر `cancel_shutdown` لإلغاء الإطفاء المجدول.
- إضافة قسم `الطاقة` في تطبيق Android مع زر إطفاء مؤكد وزر إلغاء الإطفاء.
- تحديث عميل الاختبار المحلي لدعم أوامر الطاقة مع خيار `--confirm-shutdown`.
- اختبار أوامر الطاقة في وضع `--dry-run-controls` فقط بدون إطفاء الجهاز فعليا.
- رفع نسخة Android إلى `0.10.0-power-controls`.

## 0.12.0-network-discovery

- إضافة UDP discovery في Desktop Server على نفس منفذ الاتصال `8765`.
- رد الاكتشاف يرجع اسم الحاسوب وIP والمنفذ وحالة السيرفر بدون كشف رمز الاقتران أو token.
- إضافة زر `بحث` في تطبيق Android لاكتشاف السيرفر تلقائيا وملء IP/Port.
- رفع نسخة Android إلى `0.9.0-network-discovery`.
- إعادة بناء APK وتحديث النسخة داخل `releases/` وداخل ZIP الرسمي.
## 0.11.0-android-trackpad-polish

- صقل تراكباد Android بإضافة تمرير بإصبعين.
- منع `ScrollView` من خطف حركة السحب أثناء استخدام التراكباد.
- تحديث سطح حالة الاتصال ليظهر أوضح في أعلى التطبيق.
- تحويل إعدادات الحساسية إلى Presets: `بطيء`, `متوازن`, `سريع`.
- جعل نص الاستجابة قابلا للتحديد والنسخ عند الحاجة.
- رفع نسخة Android إلى `0.8.0-trackpad-polish`.
- إعادة بناء APK واختباره على Android Emulator: اختبار سريع، اقتران، سحب تراكباد، نقر ماوس، وPreset سريع.

## 0.10.0-android-emulator-assist

- إضافة تعرف تلقائي على Android Emulator داخل تطبيق Android.
- تعبئة عنوان `10.0.2.2` تلقائيا عندما يعمل التطبيق على المحاكي ولا يوجد IP محفوظ.
- إضافة زر `محاكي` وزر `اختبار سريع` في قسم الاتصال.
- إخفاء قيمة `token` عند عرض JSON الاستجابة داخل واجهة التطبيق.
- رفع نسخة Android إلى `0.7.0-emulator-security-polish`.
- إعادة بناء APK واختباره على Android Emulator: الاختبار السريع، الاقتران، وأمر نقر ماوس في وضع dry-run.
- إضافة APK الجاهز إلى ZIP الرسمي داخل `android-apk/phone-pc-remote-android-debug.apk`.

## 0.9.0-android-emulator-smoke-test

- تثبيت حزمة Android Emulator وصورة `system-images;android-35;google_apis;x86_64`.
- إنشاء جهاز افتراضي محلي باسم `PhonePCRemote_API35` داخل `.android-avd/`.
- تشغيل التطبيق على Android Emulator وتأكيد بقاء `MainActivity` في الواجهة الأمامية.
- اختبار الاقتران من التطبيق مع Desktop Server عبر `10.0.2.2:8765`.
- اختبار أوامر `Ping`, `Status`, نقر الماوس، والتمرير في وضع `--dry-run-controls`.
- حفظ لقطة شاشة وملفات UIAutomator داخل `releases/` كأدلة اختبار محلية.
- استبعاد `.android-avd/` و`.android-home/` من ZIP الرسمي.

## 0.8.0-android-debug-apk

- تثبيت JDK 17 وAndroid Studio وأدوات Android SDK المحلية داخل المشروع.
- تثبيت `platform-tools`, `platforms;android-35`, و`build-tools;35.0.0`.
- تجهيز Gradle محلي داخل `.gradle-local/` للبناء من دون تثبيت Gradle على النظام.
- إضافة `mobile-app/local.properties` لمسار SDK المحلي مع استبعاده من ZIP الرسمي.
- إزالة BOM من ملفات Gradle وJava/XML الخاصة بتطبيق Android حتى ينجح البناء.
- إنتاج APK تجريبي بنجاح في `mobile-app/app/build/outputs/apk/debug/app-debug.apk`.
- نسخ APK الجاهز إلى `releases/phone-pc-remote-android-debug.apk`.
- تحديث استثناءات ZIP حتى لا تدخل أدوات Android وGradle المحلية داخل الحزمة الرسمية.

## 0.7.0-local-settings-support

- إعادة إنشاء موقع الدعم المحلي داخل `website/` مع صفحة تشغيل وأمان واختبار.
- إضافة ملف بصري محلي للموقع داخل `website/assets/local-control-preview.svg`.
- إضافة إعدادات محلية محفوظة في `desktop-server/data/settings.json`.
- إضافة أوامر `get_settings` و`update_settings` المحمية بالـ token.
- دعم إعدادات `mouseSensitivity`, `scrollSensitivity`, و`maxTextLength`.
- تطبيق الحساسية على `mouse_move` و`scroll`.
- جعل حد `type_text` قابلا للتعديل عبر الإعدادات.
- توسيع عميل الاختبار المحلي لدعم قراءة وتحديث الإعدادات.
- إضافة أزرار حساسية أولية في تطبيق Android.
- استبعاد `settings.json` من ملف ZIP الرسمي لأنه ملف تشغيل محلي.

## 0.6.0-local-device-management

- إضافة أوامر إدارة الأجهزة الموثوقة في Desktop Server.
- إضافة `trusted_devices` لعرض الأجهزة الموثوقة بدون كشف tokens.
- إضافة `forget_device` لحذف جهاز موثوق وإبطال token الخاص به.
- إضافة `rotate_pairing_code` لتغيير رمز الاقتران أثناء تشغيل السيرفر.
- إضافة `control_start` لإعادة تفعيل التحكم بعد `control_stop`.
- توسيع عميل الاختبار المحلي لدعم أوامر الإدارة الجديدة.
- إضافة أزرار إدارة مقابلة في تطبيق Android الأولي.
- اختبار مسار الإدارة كاملا في dry-run: عرض الأجهزة، إيقاف/تشغيل التحكم، تغيير الرمز، حذف الجهاز، ورفض token بعد الحذف.

## 0.5.0-android-client-scaffold

- إنشاء مشروع Android أولي داخل `mobile-app/` قابل للاستيراد في Android Studio.
- إضافة `MainActivity` بواجهة اتصال واقتران وتخزين محلي للـ token.
- إضافة TCP client في التطبيق يرسل JSON بسطر واحد إلى Desktop Server.
- إضافة تراكباد أولي يرسل `mouse_move` ونقرة يسار.
- إضافة أزرار ماوس: نقر يمين ويسار وتمرير.
- إضافة واجهة كيبورد لإرسال نص قصير ومفاتيح أساسية.
- إضافة أزرار ميديا واختصارات مثل Copy وPaste وAlt+Tab.
- إضافة زر `control_stop` من التطبيق.
- توثيق أن أدوات Android لم تكن متوفرة في ذلك الإصدار، لذلك لم يكن APK قد أُنتج بعد.

## 0.4.0-local-keyboard-media

- إضافة أوامر كيبورد آمنة: `key_press` و`type_text`.
- إضافة أوامر ميديا عبر `media_control` مثل الصوت والتشغيل والإيقاف.
- إضافة اختصارات محددة عبر `shortcut` مثل copy وpaste وalt_tab وshow_desktop.
- حماية كل أوامر الكيبورد والميديا بـ `deviceId` و`token`.
- إضافة حد أقصى للنصوص القصيرة: 160 حرفا.
- منع تسجيل النصوص المكتوبة في logs.
- توسيع `--dry-run-controls` ليغطي الكيبورد والميديا أيضا.
- توسيع عميل الاختبار المحلي لدعم أوامر الكيبورد والميديا والاختصارات.
- تحديث الخطة والتصميم التقني ووثيقة الأمان وREADME السيرفر.

## 0.3.0-local-mouse-control

- إضافة تحكم ماوس فعلي على Windows عبر Python `ctypes` بدون مكتبات خارجية.
- إضافة أوامر `mouse_move`, `mouse_click`, و`scroll` مع تحقق إلزامي من `deviceId` و`token`.
- إضافة حدود أمان لحركة الماوس والتمرير وعدد النقرات.
- إضافة أمر `control_stop` لإيقاف التحكم حتى إعادة تشغيل السيرفر.
- إضافة خيار `--dry-run-controls` لاختبار أوامر التحكم بدون تحريك المؤشر الحقيقي.
- توسيع عميل الاختبار المحلي لدعم أوامر الماوس.
- تحديث خطة المشروع والتصميم التقني ووثيقة الأمان وREADME السيرفر.

## 0.2.0-local-connection

- إضافة تخزين دائم للأجهزة الموثوقة في Desktop Server.
- إضافة سجل محلي لمحاولات الاتصال والأخطاء.
- إضافة رسالة `status` تعرض اسم الحاسوب و IP والمنفذ وحالة السيرفر.
- إضافة عميل اختبار محلي `desktop-server/src/test_client.py` لتجربة `ping`, `status`, و`hello`.
- تحديث الوثائق لتوضيح تشغيل السيرفر والاختبار اليدوي.
- منع ملفات التشغيل الحساسة مثل tokens و logs من دخول ZIP الرسمي.

## 0.1.0-local-planning

- إنشاء هيكل المشروع المحلي.
- تثبيت مبدأ العمل بدون خدمات أونلاين.
- إضافة خطة زمنية أولية.
- إضافة تصميم الاتصال المحلي بين الهاتف والحاسوب.
- إضافة سكربت إنتاج ملف ZIP واحد.
- إضافة موقع دعم محلي ثابت.
