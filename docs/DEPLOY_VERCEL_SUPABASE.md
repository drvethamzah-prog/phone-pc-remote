# نشر Qareeb على Vercel وSupabase

## 1. إنشاء مشروع Supabase

أنشئ مشروعًا جديدًا، ثم احتفظ بعنوان المشروع ومفتاح `anon`. لا تضع مفتاح
`service_role` في التطبيق أو GitHub؛ يستخدمه الخادم فقط.

أنشئ جداول الحسابات والأجهزة ونسخ الإعدادات وفق العقود الموجودة في
`backend/contracts/`. النسخة الاحتياطية تقتصر على إعدادات المستخدم ولا تحتوي
على مفاتيح التحكم المحلية.

## 2. إنشاء مشروع Vercel

اربط مستودع GitHub بالمجلد الجذر للمشروع. لا تختَر `website/` كـRoot Directory
لأن API موجود في `api/`.

أضف متغيرات البيئة في Vercel لكل من Preview وProduction:

```text
SUPABASE_URL
SUPABASE_ANON_KEY
SUPABASE_SERVICE_ROLE_KEY
APP_BASE_URL
EMAIL_PROVIDER_API_KEY
```

لا تطبع قيم هذه المتغيرات في السجلات ولا ترسلها داخل التطبيق.

## 3. اختبار أول نشر

بعد النشر، اختبر:

```text
GET https://YOUR-DOMAIN/api/health
```

يجب أن تكون الاستجابة:

```json
{"ok":true,"service":"qareeb-cloud","controlPath":false}
```

بعد نجاح ذلك نكمل وظائف الحساب والبريد وSupabase داخل `api/index.py`.
