# التصميم التقني

## نظرة عامة

`Qareeb Control` يتكون من ثلاثة أجزاء. تبقى أسماء الحزمة والمجلد `phone-pc-remote` مؤقتا لأسباب توافق البناء.

```text
Android Phone App  <--- Local TCP JSON --->  Desktop Server
Android Phone App  <--- UDP discovery ---->  Desktop Server
                                      |
                                      +-- Local Support Website
```

لا يوجد خادم خارجي. لا توجد حسابات. لا توجد مزامنة سحابية.

## Desktop Server

برنامج الحاسوب مسؤول عن:

- فتح منفذ محلي للاستماع للهاتف.
- عرض رمز اقتران.
- التحقق من الجهاز والرمز والـ token.
- تحويل أوامر الهاتف الموثوقة إلى أوامر نظام.
- حفظ الأجهزة الموثوقة محليا في `desktop-server/data/trusted-devices.json`.
- تسجيل أخطاء واتصالات في `desktop-server/data/events.log`.
- إيقاف وإعادة تفعيل التحكم عبر `control_stop` و`control_start`.
- إدارة الأجهزة الموثوقة عبر أوامر محمية بالـ token.
- حفظ إعدادات التشغيل المحلية في `desktop-server/data/settings.json`.

النسخة الحالية Python وتستخدم المكتبة القياسية قدر الإمكان. التحكم بالماوس والكيبورد على Windows يستخدم `ctypes` وواجهات Windows المحلية بدون مكتبة خارجية.

## Mobile App

تطبيق الهاتف الحالي موجود كـ Android project داخل `mobile-app/`.

مسؤوليات التطبيق:

- الاتصال بالحاسوب عبر IP و Port.
- إرسال رسائل JSON over TCP.
- تنفيذ `ping`, `status`, و`hello`.
- حفظ آخر IP/Port و token محليا عبر `SharedPreferences`.
- زر `بحث` في Android يستخدم UDP discovery على المنفذ `8765` لاكتشاف السيرفر داخل الشبكة.
- عند التشغيل على Android Emulator يستخدم التطبيق `10.0.2.2` تلقائيا كعنوان الحاسوب إذا لم يوجد IP محفوظ.
- عرض استجابات JSON داخل التطبيق يخفي قيمة `token`.
- توفير واجهة تراكباد مع حركة ماوس، نقرة يسار، وتمرير بإصبعين.
- إرسال أوامر الماوس والكيبورد والميديا والاختصارات.
- أزرار الأحرف والأرقام ترسل `key_press` مباشرة حتى تظهر فورا في الحقل المحدد على Windows، بينما يبقى `type_text` للنصوص القادمة من كيبورد الهاتف والرموز.
- إرسال أوامر إدارة أساسية مثل عرض الأجهزة الموثوقة وتغيير رمز الاقتران وحذف الجهاز.

التنفيذ الحالي يستخدم Android SDK وJava بدون مكتبات خارجية. تم تثبيت أدوات Android محليا داخل المشروع، وتم بناء APK تجريبي بنجاح في `mobile-app/app/build/outputs/apk/debug/app-debug.apk`، واختباره على Android Emulator مع تعبئة تلقائية لعنوان `10.0.2.2`. تم أيضا تأكيد اتصال هاتف فعلي ونجاح الماوس، وبقي تثبيت إدخال الكيبورد اللحظي بعد الإصلاح.

## بروتوكول الرسائل

كل رسالة تكون JSON في سطر واحد.

مثال من الهاتف إلى الحاسوب:

```json
{"type":"mouse_move","deviceId":"phone-1","token":"...","dx":12,"dy":-4}
```

مثال من الحاسوب إلى الهاتف:

```json
{"type":"status","controlEnabled":true,"computerName":"Office-PC"}
```

## حالة التنفيذ الحالية

السيرفر الحالي يدعم، وتطبيق Android الأولي يرسل، الرسائل التالية:

- `ping`
- `status`
- `hello`
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

الاقتران يصدر token، وإعادة الاتصال تستخدم token محفوظا في ملف محلي. أوامر التحكم والإدارة لا تنفذ إلا مع `deviceId` و`token` صحيحين.

## حدود الأمان للأوامر

- `mouse_move`: يتم تحديد `dx` و`dy` بين `-200` و`200` لكل رسالة.
- `scroll`: يتم تحديد `amount` بين `-10` و`10` لكل رسالة.
- `mouse_click`: يسمح فقط بـ `left`, `right`, و`middle`، وعدد نقرات بين `1` و`2`.
- `key_press`: يسمح بمفاتيح معروفة فقط، وتكرار حتى 5 مرات لكل رسالة.
- `type_text`: يسمح بنص قصير حتى 160 حرفا، ولا يتم تسجيل النص نفسه في log.
- `media_control`: يسمح بأوامر ميديا محددة مثل الصوت والتشغيل.
- `shortcut`: يسمح باختصارات محددة مسبقا فقط، مثل copy وpaste وalt_tab.
- `get_settings`: يعرض إعدادات التشغيل المحلية.
- `update_settings`: يحدث الحساسية وحد النص ضمن حدود آمنة.
- `trusted_devices`: يعرض الأجهزة الموثوقة بدون tokens.
- `forget_device`: يحذف جهازا موثوقا ويبطل token الخاص به.
- `rotate_pairing_code`: ينشئ رمز اقتران جديدا أثناء تشغيل السيرفر.
- `control_start`: يعيد تفعيل التحكم بعد الإيقاف.
- `control_stop`: يوقف التحكم حتى إعادة تشغيل السيرفر أو استخدام `control_start`.

## لماذا TCP أولا؟

TCP المحلي يسمح لنا ببناء نسخة أولى بدون تنزيل مكتبات:

- Python يملك `socketserver`.
- Android يملك `Socket`.
- الرسائل النصية سهلة الاختبار.

WebSocket خيار جيد لاحقا، خصوصا إذا احتجنا واجهة ويب أو تكاملا مع متصفح.
