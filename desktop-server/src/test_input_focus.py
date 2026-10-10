import unittest
from unittest.mock import Mock
import input_focus


class FocusTests(unittest.TestCase):
    def setUp(self):
        self.api = Mock()
        self.element = Mock()
        self.element.CurrentIsEnabled = True
        self.element.CurrentHasKeyboardFocus = True
        self.element.CurrentProcessId = 123
        self.element.GetRuntimeId.return_value = (1, 2, 3)
        self.pattern = self.element.GetCurrentPattern.return_value.QueryInterface.return_value
        self.pattern.CurrentIsReadOnly = False

    def test_editable_metadata_only(self):
        result = input_focus.describe(self.element, self.api)
        self.assertTrue(result["editable"])
        self.assertEqual(len(result["focusId"]), 24)
        self.assertEqual(set(result), {"editable", "focusId"})
        self.assertNotIn("Value", repr(self.pattern.mock_calls))

    def test_read_only(self):
        self.pattern.CurrentIsReadOnly = True
        self.assertFalse(input_focus.describe(self.element, self.api)["editable"])

    def test_disabled_or_unfocused(self):
        self.element.CurrentIsEnabled = False
        self.assertFalse(input_focus.describe(self.element, self.api)["editable"])
        self.element.GetCurrentPattern.assert_not_called()
        self.element.CurrentIsEnabled = True
        self.element.CurrentHasKeyboardFocus = False
        self.assertFalse(input_focus.describe(self.element, self.api)["editable"])

    def test_no_value_pattern(self):
        self.element.GetCurrentPropertyValue.return_value = False
        self.assertFalse(input_focus.describe(self.element, self.api)["editable"])

    def test_field_identity_changes(self):
        first = input_focus.describe(self.element, self.api)
        self.element.GetRuntimeId.return_value = (1, 2, 4)
        self.assertNotEqual(first["focusId"], input_focus.describe(self.element, self.api)["focusId"])


if __name__ == "__main__":
    unittest.main()
