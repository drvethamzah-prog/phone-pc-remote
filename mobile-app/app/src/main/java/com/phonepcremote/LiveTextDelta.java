package com.phonepcremote;

/** A suffix edit, applied at the current remote caret without replaying committed text. */
final class LiveTextDelta {
    final int backspaces;
    final String text;

    LiveTextDelta(int backspaces, String text) {
        this.backspaces = backspaces;
        this.text = text;
    }

    static LiveTextDelta between(String before, String after) {
        int prefix = 0;
        while (prefix < before.length() && prefix < after.length()) {
            int left = before.codePointAt(prefix);
            if (left != after.codePointAt(prefix)) break;
            prefix += Character.charCount(left);
        }
        return new LiveTextDelta(before.codePointCount(prefix, before.length()), after.substring(prefix));
    }

    boolean isEmpty() {
        return backspaces == 0 && text.isEmpty();
    }
}
