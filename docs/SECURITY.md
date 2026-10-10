# تصميم أمان Qareeb Control المحلي

## Windows Credential Storage Hardening (Source Only)

The server now stores device tokens in a version-2 store under
`%LOCALAPPDATA%/QareebControl/credentials/trusted-devices.json`.
Tokens are encrypted with user-scope Windows DPAPI and bound to their device ID.
The credential directory grants access only to the current user and SYSTEM.
On the next server launch, the legacy workspace store is migrated while preserving
pairing tokens. The plaintext file is removed only after the encrypted store can
be decrypted successfully. Corruption fails closed instead of resetting trust.

This source change has NOT been activated on the running production server.
No production credentials, ACLs, pairing codes, or network settings were changed.
Android 0.35 now stores confirmed and pending tokens with Android Keystore AES-GCM,
using preference names as authenticated associated data. Legacy plaintext values
migrate in a single committed preference update after existing ciphertext validates.
Missing keys and invalid ciphertext block credential use without deleting saved values.
API-35 emulator checks cover legacy/pending migration, randomized encryption, reload,
AAD binding, corruption and missing-key rejection. Full application instrumentation
passed 80 checks each in English and Arabic against an isolated recording server.
The current physical phone has NOT been upgraded or verified. At-rest encryption does not encrypt the
network connection and does not protect against malware running as the same user.
The known pairing code, optional TLS, request resource limits, and power-command
authorization still require hardening before commercial release.

## أساس الهوية وTLS المنفذ

أضيف `desktop-server/src/local_identity.py` كأساس للهوية، ثم وُصل اختياريا
بمستمع السيرفر عبر `--tls` وبـ Android 0.31 عبر بصمة شهادة محفوظة من الحاسوب
مباشرة. جلسة المستخدم الحالية وإعداد التشغيل التلقائي لم يُرحّلا وما زالا
بالوضع غير المشفر. راجع `PINNED_TLS.md`؛ ليس هذا إصدارا جاهزا للبيع.

- هوية مستمدة من SHA-256 للمفتاح العام، لا من اسم الحاسوب أو عنوان IP.
- شهادة ذاتية التوقيع بمفتاح ECDSA P-256 وبصمة SHA-256، صالحة لسنة.
- مفتاح خاص محفوظ عبر Windows DPAPI لحساب المستخدم، دون حماية على مستوى الجهاز.
- ملف `local-identity.json` مستثنى من حزمة التوزيع. لا تُوزع هوية المطور.
- إنشاء الملف كاملًا قبل نشره دون الكتابة فوق هوية أنشأتها عملية أخرى.
- التلف وانتهاء الصلاحية وفشل فك الحماية وعدم تطابق المفتاح والشهادة أخطاء صريحة،
  وليست أسبابًا لإنشاء هوية بديلة تلقائيًا.
- سياق TLS يقبل 1.2 فما فوق، مع مجموعات تشفير حديثة؛ لا تثبيت لشهادة
  في مخزن ثقة Windows ولا حاجة لإنترنت أو جهة إصدار خارجية.
- ملفات تحميل المفتاح المؤقتة مشفرة بكلمة عشوائية في الذاكرة وتحذف بعد التحميل؛
  حماية DPAPI لا تمنع برنامجًا خبيثًا يعمل بصلاحيات نفس حساب المستخدم.

الاختبارات تشمل اتصال TLS حقيقيًا عبر loopback ورفض شهادة مختلفة، وثبات الهوية
بعد إعادة تحميلها، وتزامن الإنشاء، والتلف وعدم التطابق. لا تعادل هذه الاختبارات
اختبار Android أو إثبات أمان الاقتران الأولي.

نُفذ إدخال البصمة يدويا ومقارنتها محليا؛ المتبقي قبل الإطلاق: الاقتران عبر QR،
وخطة تجديد الشهادة بنفس الهوية واستعادة المفاتيح،
وموافقة الحاسوب على الإضافة وتخزين أسرار الهاتف في Android Keystore.
دالة `trusted_client_context` أداة اختبار Python وليست تنفيذ التحقق على Android.
يتحقق Android عبر `PinnedTls` من بصمة الشهادة وصلاحيتها قبل إرسال أي طلب TCP،
ولا يقبل بصمة من الاكتشاف أو يرجع للنقل غير المشفر بعد حفظ بصمة.

المراجع: [Python SSL](https://docs.python.org/3/library/ssl.html)،
[PyCA X.509](https://cryptography.io/en/latest/x509/tutorial/).

## الهدف

### Server Resource Guards (0.42, Not Activated On The Running Server)

- TCP requests have a 16 KiB frame limit, including the required newline. The
  bound is enforced before UTF-8 decoding and JSON parsing. Oversized and
  unterminated EOF frames close the connection after a small error response.
- IPv4 and scoped IPv6 listeners share one locked budget: 64 active workers,
  at most 16 per source address. Excess sockets are closed before worker creation
  and TLS negotiation. Slots are released on disconnect and handler failure.
- TLS handshake timeout remains five seconds; socket idle read timeout remains
  sixty seconds. There is no absolute trickle-frame deadline or per-command rate
  limit yet. Attackers can still occupy the budget, including via many sources.
- Shutdown scheduling respects disabled controls. Trusted shutdown cancellation
  remains possible. Trusted clients can still re-enable controls under the existing
  protocol; this is not an administrator-only lock or a complete permission model.
- Android response readers remain unbounded pending a separate client change.
  These guards do not replace mandatory TLS or local initial-pairing authorization.

75 Python tests passed in separate processes. Production settings and the active
session were not modified; new server behavior takes effect on a later launch.

الحاسوب لا يقبل التحكم إلا من هاتف تم السماح له صراحة.

## طبقات الأمان

1. رمز اقتران يظهر على الحاسوب.
2. Token خاص بكل جهاز موثوق.
3. قائمة أجهزة موثوقة محفوظة محليا.
4. تحقق من `deviceId` و`token` قبل أي أمر تحكم.
5. إيقاف فوري للتحكم عبر `control_stop` أو إيقاف السيرفر.
6. أوامر الطاقة الحساسة تحتاج token وتأكيد صريح ومهلة إلغاء.
7. سجل محلي لمحاولات الاتصال والأخطاء.
8. حدود أمان لحركة الماوس والتمرير والنقر.

## عملية الاقتران

```text
الهاتف يطلب الاتصال
|
الحاسوب يتحقق من رمز الاقتران
|
الحاسوب يضيف الجهاز إلى القائمة الموثوقة
|
الحاسوب يصدر token للجهاز
|
الهاتف يستخدم token في الاتصالات اللاحقة
```

في النسخة ذات الواجهة الرسومية سنضيف نافذة موافقة على الحاسوب قبل اعتماد الجهاز.

## أوامر التحكم الحالية

أوامر التحكم الحالية لا تعمل إلا من جهاز موثوق:

- `mouse_move`
- `mouse_click`
- `scroll`
- `key_press`
- `type_text`
- `media_control`
- `shortcut`
- `get_settings`
- `update_settings`
- `trusted_devices`
- `forget_device`
- `rotate_pairing_code`
- `control_start`
- `control_stop`
- `shutdown_computer`
- `cancel_shutdown`

حدود الأمان الحالية:

- حركة الماوس محدودة لكل رسالة.
- التمرير محدود لكل رسالة.
- النقر محصور في أزرار معروفة.
- مفاتيح الكيبورد محصورة في قائمة مسموحة.
- `type_text` محدود بـ 160 حرفا، ولا يسجل النص نفسه في log.
- أوامر الميديا والاختصارات محصورة في قوائم معروفة.
- `get_settings` و`update_settings` يحتاجان جهازا موثوقا.
- `trusted_devices` لا يرجع tokens.
- واجهة Android تخفي قيمة `token` عند عرض استجابة JSON بعد الاقتران.
- اكتشاف UDP يرجع بيانات عامة فقط مثل اسم الحاسوب وIP والمنفذ، ولا يرجع رمز الاقتران أو token.
- رمز الاقتران الافتراضي الحالي هو `111111` لتسهيل الاختبار المحلي؛ عند مشاركة البرنامج خارج بيئة التجربة يفضل تشغيل السيرفر برمز خاص عبر `--pairing-code`.
- `forget_device` يبطل token الجهاز المحذوف.
- `rotate_pairing_code` يحتاج جهازا موثوقا.
- `shutdown_computer` يحتاج تأكيدا صريحا بقيمة `SHUTDOWN` ويجدول الإطفاء بعد 30 ثانية افتراضيا.
- `cancel_shutdown` يستطيع إلغاء الإطفاء المجدول قبل انتهاء المهلة.
- بعد `control_stop` يرفض السيرفر أوامر التحكم حتى إعادة تشغيله أو استخدام `control_start`.
- يمكن تشغيل السيرفر بخيار `--dry-run-controls` لاختبار الأوامر بدون تحريك المؤشر أو الكتابة الفعلية.

## الميزات الحساسة

هذه الميزات يجب أن تكون قابلة للتفعيل أو التعطيل من إعدادات الحاسوب:

- إعادة تشغيل الحاسوب.
- Clipboard sync.
- مدير الملفات.
- الأوامر المخصصة.
- الكتابة التلقائية الطويلة.

## التخزين

كل البيانات تحفظ محليا داخل مجلد البرنامج. في التنفيذ الحالي يستخدم السيرفر مجلد `desktop-server/data/`:

- `trusted-devices.json`: الأجهزة الموثوقة والـ tokens.
- `settings.json`: إعدادات تشغيل محلية غير حساسة.
- `events.log`: سجل الاتصال والأخطاء.

ملفات مثل `trusted-devices.json` و`settings.json` و`events.log` تعتبر بيانات تشغيل محلية ولا تدخل في ملف ZIP الرسمي.

لا يتم رفع أي شيء إلى الإنترنت.
