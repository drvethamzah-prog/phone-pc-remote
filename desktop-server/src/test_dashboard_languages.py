import json
from pathlib import Path
import tempfile
import unittest

from dashboard_languages import CATALOGS, KEYS, LANGUAGES, load_language, save_language, translate


class LanguageTests(unittest.TestCase):
    def test_catalogs_have_all_labels(self):
        self.assertEqual(set(CATALOGS), set(LANGUAGES))
        for code, catalog in CATALOGS.items():
            with self.subTest(code=code):
                self.assertEqual(set(catalog), set(KEYS))
                self.assertTrue(all(isinstance(value, str) and value for value in catalog.values()))

    def test_english_default_and_fallback(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "settings.json"
            self.assertEqual(load_language(path), "en")
            for content in ('{"language":"unknown"}', '[]', 'broken'):
                path.write_text(content)
                self.assertEqual(load_language(path), "en")
        self.assertEqual(translate("unknown", "close"), "Close")

    def test_language_selection_survives_reload(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "settings.json"
            for code in LANGUAGES:
                save_language(path, code)
                self.assertEqual(load_language(path), code)
                self.assertEqual(json.loads(path.read_text()), {"language": code})
            self.assertFalse(list(Path(folder).glob("*.tmp")))

    def test_unsupported_selection_does_not_overwrite_preferences(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "settings.json"
            save_language(path, "fr")
            with self.assertRaises(ValueError):
                save_language(path, "unsupported")
            self.assertEqual(load_language(path), "fr")


if __name__ == "__main__":
    unittest.main()
