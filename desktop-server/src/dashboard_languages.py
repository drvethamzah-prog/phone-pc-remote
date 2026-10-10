"""Desktop catalogs: English defaults, explicit selection, English fallback."""

import json
import os
from pathlib import Path
import tempfile

LANGUAGES = {"en": "English", "ar": "العربية", "fr": "Français", "es": "Español",
             "de": "Deutsch", "zh": "简体中文"}
KEYS = ("checking", "Computer", "Local address", "Transport", "Response time", "Controls",
        "Trusted phones", "startup", "probe_tls", "trusted", "device", "seen", "refresh", "close",
        "available", "devices_error", "server_error", "dry_run", "enabled", "disabled", "legacy",
        "language", "save_error")
CATALOGS = dict(zip(LANGUAGES, [dict(zip(KEYS, values)) for values in [
    ["Checking local server...", "Computer", "Local address", "Transport", "Response time", "Controls",
     "Trusted phones", "Start with Windows", "Probe using TLS", "Trusted devices", "Device", "Last seen (UTC)",
     "Refresh", "Close", "Local server available", "Server available; device list could not be read.",
     "Could not verify the local server. Check server and transport mode.", "Dry run", "Enabled", "Disabled",
     "Legacy (unencrypted)", "Language", "Could not save the language preference."],
    ["جارٍ فحص السيرفر المحلي...", "الحاسوب", "العنوان المحلي", "نوع الاتصال", "زمن الاستجابة", "التحكم",
     "الهواتف الموثوقة", "التشغيل مع Windows", "الفحص عبر TLS", "الأجهزة الموثوقة", "الجهاز", "آخر ظهور (UTC)",
     "تحديث", "إغلاق", "السيرفر المحلي متاح", "السيرفر متاح؛ تعذّرت قراءة قائمة الأجهزة.",
     "تعذّر التحقق من السيرفر المحلي. تحقق من السيرفر ونوع الاتصال.", "وضع الاختبار", "مفعّل", "معطّل",
     "قديم (غير مشفّر)", "اللغة", "تعذّر حفظ اختيار اللغة."],
    ["Vérification du serveur local...", "Ordinateur", "Adresse locale", "Transport", "Temps de réponse", "Contrôle",
     "Téléphones approuvés", "Démarrer avec Windows", "Tester via TLS", "Appareils approuvés", "Appareil", "Dernière activité (UTC)",
     "Actualiser", "Fermer", "Serveur local disponible", "Serveur disponible ; liste des appareils inaccessible.",
     "Serveur local non vérifié. Vérifiez le serveur et le transport.", "Mode test", "Activé", "Désactivé",
     "Ancien (non chiffré)", "Langue", "Impossible d'enregistrer la langue."],
    ["Comprobando el servidor local...", "Ordenador", "Dirección local", "Transporte", "Tiempo de respuesta", "Control",
     "Teléfonos de confianza", "Iniciar con Windows", "Comprobar mediante TLS", "Dispositivos de confianza", "Dispositivo", "Última actividad (UTC)",
     "Actualizar", "Cerrar", "Servidor local disponible", "Servidor disponible; no se pudo leer la lista de dispositivos.",
     "No se pudo verificar el servidor local. Compruebe el servidor y el transporte.", "Modo de prueba", "Activado", "Desactivado",
     "Antiguo (sin cifrar)", "Idioma", "No se pudo guardar el idioma."],
    ["Lokaler Server wird geprüft...", "Computer", "Lokale Adresse", "Transport", "Antwortzeit", "Steuerung",
     "Vertrauenswürdige Telefone", "Mit Windows starten", "Über TLS prüfen", "Vertrauenswürdige Geräte", "Gerät", "Zuletzt gesehen (UTC)",
     "Aktualisieren", "Schließen", "Lokaler Server verfügbar", "Server verfügbar; Geräteliste konnte nicht gelesen werden.",
     "Lokaler Server konnte nicht überprüft werden. Server und Transport prüfen.", "Testmodus", "Aktiviert", "Deaktiviert",
     "Alt (unverschlüsselt)", "Sprache", "Sprache konnte nicht gespeichert werden."],
    ["正在检查本地服务器...", "计算机", "本地地址", "传输方式", "响应时间", "控制",
     "受信任的手机", "随 Windows 启动", "通过 TLS 检查", "受信任的设备", "设备", "最后活动时间 (UTC)",
     "刷新", "关闭", "本地服务器可用", "服务器可用，但无法读取设备列表。",
     "无法验证本地服务器。请检查服务器和传输方式。", "测试模式", "已启用", "已禁用",
     "旧版（未加密）", "语言", "无法保存语言设置。"],
]]))

STARTUP_KEYS = ("startup_transport", "startup_notice", "startup_confirm", "startup_failed", "apply")
for code, values in zip(LANGUAGES, [
    ["Startup transport...", "Applies at the next launch, not to the running session. TLS requires a prepared computer identity and a trusted fingerprint on the phone.",
     "Save this startup transport? Phone and server settings must match. This does not restart the server.", "Could not save startup transport. Check the computer identity and settings.", "Apply"],
    ["نوع الاتصال عند التشغيل...", "يسري عند التشغيل القادم، وليس على الجلسة الحالية. يتطلب TLS هوية حاسوب جاهزة وبصمة موثوقة على الهاتف.",
     "حفظ نوع الاتصال للتشغيل القادم؟ يجب تطابق إعدادات الهاتف والسيرفر. لن يعاد تشغيل السيرفر.", "تعذّر حفظ نوع الاتصال. تحقق من هوية الحاسوب والإعدادات.", "تطبيق"],
    ["Transport au démarrage...", "Prend effet au prochain lancement. TLS exige une identité préparée et une empreinte approuvée sur le téléphone.",
     "Enregistrer ce transport ? Les réglages du téléphone et du serveur doivent correspondre. Aucun redémarrage du serveur.", "Impossible d'enregistrer le transport. Vérifiez l'identité et les réglages.", "Appliquer"],
    ["Transporte al iniciar...", "Se aplica al próximo inicio. TLS requiere una identidad preparada y una huella de confianza en el teléfono.",
     "¿Guardar este transporte? Los ajustes del teléfono y del servidor deben coincidir. No se reiniciará el servidor.", "No se pudo guardar el transporte. Compruebe la identidad y los ajustes.", "Aplicar"],
    ["Transport beim Start...", "Gilt erst beim nächsten Start. TLS benötigt eine vorbereitete Identität und einen bestätigten Fingerabdruck auf dem Telefon.",
     "Diesen Transport speichern? Telefon und Server müssen übereinstimmen. Der Server wird nicht neu gestartet.", "Transport konnte nicht gespeichert werden. Identität und Einstellungen prüfen.", "Anwenden"],
    ["启动传输设置...", "仅在下次启动时生效。TLS 需要已准备的计算机身份及手机上受信任的指纹。",
     "保存启动传输设置？手机与服务器设置必须一致。不会重启服务器。", "无法保存启动传输设置。请检查计算机身份和设置。", "应用"],
]):
    CATALOGS[code].update(zip(STARTUP_KEYS, values))
KEYS += STARTUP_KEYS


def translate(language, key):
    return CATALOGS.get(language, CATALOGS["en"]).get(key, CATALOGS["en"].get(key, key))


def load_language(path):
    try:
        code = json.loads(Path(path).read_text(encoding="utf-8")).get("language", "en")
        return code if code in LANGUAGES else "en"
    except (OSError, ValueError, AttributeError, TypeError):
        return "en"


def save_language(path, code):
    if code not in LANGUAGES:
        raise ValueError("Unsupported language")
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as output:
        temporary = Path(output.name)
        output.write(json.dumps({"language": code}).encode("utf-8"))
        output.flush()
        os.fsync(output.fileno())
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
