# Desktop Server

هذا المجلد يحتوي برنامج الحاسوب المحلي لمنتج `Qareeb Control`.

## النسخة الحالية

الملف `src/server.py` هو نواة السيرفر المحلي:

- يستمع افتراضيا على `0.0.0.0:8765`.
- يطبع اسم الحاسوب، IP المحلي، المنفذ، ورمز الاقتران الافتراضي `111111`.
- يستقبل رسائل JSON بسطر واحد لكل رسالة.
- يدعم `ping`, `status`, و`hello`.
- يحفظ الأجهزة الموثوقة في `data/trusted-devices.json` بعد الاقتران.
- يكتب سجل اتصال وأخطاء في `data/events.log` بدون تسجيل النصوص الحساسة.
- يدعم أوامر ماوس آمنة: `mouse_move`, `mouse_click`, و`scroll`.
- يدعم أوامر كيبورد آمنة: `key_press`, `type_text`, `media_control`, و`shortcut`.
- يحاول إدخال الأحرف الإنجليزية والأرقام كضغطات مفاتيح فعلية عند الحاجة حتى تعمل الكتابة اللحظية في الحقل المحدد على Windows.
- يدعم إدارة الأجهزة: `trusted_devices`, `forget_device`, و`rotate_pairing_code`.
- يدعم إعدادات محلية: `get_settings` و`update_settings`.
- يدعم `control_stop` و`control_start` لإيقاف وإعادة تفعيل التحكم.
- لا ينفذ أوامر التحكم أو الإدارة إلا مع `deviceId` و`token` صحيحين.

## التشغيل

```powershell
python .\src\server.py
```

يمكن تغيير المنفذ أو عنوان الاستماع:

```powershell
python .\src\server.py --host 0.0.0.0 --port 8765
```

يمكن تغيير رمز الاقتران عند التشغيل:

```powershell
python .\src\server.py --pairing-code 111111
```

للاختبار بدون تحريك الماوس أو الكتابة الفعلية:

```powershell
python .\src\server.py --dry-run-controls
```

لبدء السيرفر والتحكم معطل:

```powershell
python .\src\server.py --controls-disabled
```

## الاتصال التجريبي

يوجد عميل اختبار محلي:

```powershell
python .\src\test_client.py ping
python .\src\test_client.py status
```

للاقتران، شغل السيرفر واستخدم رمز الاقتران الظاهر. افتراضيا الرمز هو `111111`:

```powershell
python .\src\test_client.py hello --device-id test-phone --device-name "Test Phone" --pairing-code YOUR_CODE
```

سيعيد السيرفر token. لا تشارك هذا token مع أحد. لإعادة الاتصال:

```powershell
python .\src\test_client.py hello --device-id test-phone --token YOUR_TOKEN
```

## اختبار الماوس

```powershell
python .\src\test_client.py mouse-move --device-id test-phone --token YOUR_TOKEN --dx 20 --dy -10
python .\src\test_client.py mouse-click --device-id test-phone --token YOUR_TOKEN --button left
python .\src\test_client.py scroll --device-id test-phone --token YOUR_TOKEN --amount -3
```

## اختبار الكيبورد والميديا

```powershell
python .\src\test_client.py key-press --device-id test-phone --token YOUR_TOKEN --key enter
python .\src\test_client.py key-press --device-id test-phone --token YOUR_TOKEN --key a --modifiers ctrl
python .\src\test_client.py type-text --device-id test-phone --token YOUR_TOKEN --text "hello local"
python .\src\test_client.py media-control --device-id test-phone --token YOUR_TOKEN --control volume_up
python .\src\test_client.py shortcut --device-id test-phone --token YOUR_TOKEN --shortcut copy
```

## اختبار الإدارة

```powershell
python .\src\test_client.py trusted-devices --device-id test-phone --token YOUR_TOKEN
python .\src\test_client.py control-stop --device-id test-phone --token YOUR_TOKEN
python .\src\test_client.py control-start --device-id test-phone --token YOUR_TOKEN
python .\src\test_client.py rotate-pairing-code --device-id test-phone --token YOUR_TOKEN
python .\src\test_client.py forget-device --device-id test-phone --token YOUR_TOKEN --target-device-id test-phone
```

## اختبار الإعدادات

```powershell
python .\src\test_client.py get-settings --device-id test-phone --token YOUR_TOKEN
python .\src\test_client.py update-settings --device-id test-phone --token YOUR_TOKEN --mouse-sensitivity 1.4 --scroll-sensitivity 1.2 --max-text-length 160
```

## بيانات محلية

مجلد `data/` مخصص لبيانات التشغيل المحلية. ملفات مثل `trusted-devices.json` و`settings.json` و`events.log` لا تدخل في ملف ZIP الرسمي.
