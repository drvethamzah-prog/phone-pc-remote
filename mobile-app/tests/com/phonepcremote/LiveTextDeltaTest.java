package com.phonepcremote;

public final class LiveTextDeltaTest {
    private static void expect(String before, String after, int deletes, String text) {
        LiveTextDelta edit = LiveTextDelta.between(before, after);
        if (edit.backspaces != deletes || !edit.text.equals(text)) throw new AssertionError("Unexpected delta");
        int end = before.offsetByCodePoints(before.length(), -edit.backspaces);
        if (!(before.substring(0, end) + edit.text).equals(after)) throw new AssertionError("Edit did not reconstruct text");
    }

    public static void main(String[] args) {
        expect("", "s", 0, "s");
        expect("s", "sa", 0, "a");
        expect("sa", "sa", 0, "");
        expect("teh", "the", 2, "he");
        expect("hello", "hell", 1, "");
        expect("", "\u0633", 0, "\u0633");
        expect("\u0633", "\u0633\u0644", 0, "\u0644");
        expect("a\uD83D\uDE00", "a", 1, "");
        expect("\uD83D\uDE00", "\uD83D\uDE03", 1, "\uD83D\uDE03");
        expect("abc tail", "axc tail", 7, "xc tail");
        expect("word", "word ", 0, " ");
        expect("", "line\nline", 0, "line\nline");
        System.out.println("PASS 12 live text delta cases: composition, commit, correction, Arabic, deletion and code points");
    }
}
