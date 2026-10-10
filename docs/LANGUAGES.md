# Language Policy

English is the primary/default UI language, independent of OS language, country,
keyboard language or network. Users explicitly select their preferred UI language.
Existing saved choices must survive updates. A future catalog must not change any
connection, startup, pairing or keyboard input setting.

## Implemented

- Desktop: English, Arabic, French, Spanish, German and Simplified Chinese.
- Picker displays native language names and updates existing widgets/status without
  restarting the server or desktop window. Missing translations fall back to English.
- The selected desktop language is atomically saved in excluded runtime data file
  `desktop-server/data/dashboard-settings.json`. Invalid/missing preferences use English.
- Android 0.33: English default for new installations; existing English/Arabic/System
  choices are preserved. Android currently contains only English and Arabic catalogs.
  System-language selection falls back to English for unsupported OS languages.

The six desktop catalogs do not imply six Android catalogs or all-world support.
The saved desktop language and saved phone language are independent preferences.

## Expansion and Acceptance

Add complete catalogs, not language options displaying untranslated English.
Planned additional languages include Portuguese, Turkish, Hindi, Japanese, Korean,
Russian and Indonesian; prioritize demand and reviewed translations. Keep protocol
identifiers, IP addresses, tokens, brand name and fingerprints unchanged.

Validate all keys, placeholders/plurals, fallback, persistence and unchanged control
behavior. Before release, verify native text shaping, right-to-left layout, long
labels, font fallback and screen-reader names. Current Tk desktop Arabic shaping/
RTL and CJK typography have not passed visual acceptance. Do not market universal
language support until reviewed catalogs and per-platform tests actually exist.
