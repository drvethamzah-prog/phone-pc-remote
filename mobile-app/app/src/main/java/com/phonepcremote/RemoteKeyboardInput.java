package com.phonepcremote;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

@android.annotation.SuppressLint("ViewConstructor") // Constructed only with a required input listener.
final class RemoteKeyboardInput extends EditText {
    interface Listener {
        boolean onEdit(LiveTextDelta edit);
        void onSpecialKey(String key);
    }

    private final Listener listener;
    private String mirrored = "";
    private boolean suppress;
    private int batchDepth;

    RemoteKeyboardInput(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        int options = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        if (Build.VERSION.SDK_INT >= 26) options |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        setImeOptions(options);
        setTextColor(Color.TRANSPARENT);
        setBackgroundColor(Color.TRANSPARENT);
        setCursorVisible(false);
        setPadding(0, 0, 0, 0);
        setMinimumHeight(0);
        setMinHeight(0);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (Build.VERSION.SDK_INT >= 26) setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable value) { flushEdit(); }
        });
    }

    private void flushEdit() {
        if (suppress || batchDepth > 0) return;
        String current = getText().toString();
        LiveTextDelta edit = LiveTextDelta.between(mirrored, current);
        mirrored = current;
        if (!edit.isEmpty() && !listener.onEdit(edit)) resetContext();
        // Keep enough context for IME corrections, never a full document or password history.
        if (getText().length() > 512 && BaseInputConnection.getComposingSpanStart(getText()) < 0) resetContext();
    }

    void resetContext() {
        suppress = true;
        mirrored = "";
        BaseInputConnection.removeComposingSpans(getText());
        getText().clear();
        setSelection(0);
        suppress = false;
    }

    void backspace() {
        if (getText().length() == 0) {
            listener.onEdit(new LiveTextDelta(1, ""));
        } else {
            int end = getText().length();
            int start = Character.offsetByCodePoints(getText(), end, -1);
            getText().delete(start, end);
            setSelection(getText().length());
        }
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo attrs) {
        InputConnection base = super.onCreateInputConnection(attrs);
        if (base == null) return null;
        batchDepth = 0;
        return new InputConnectionWrapper(base, false) {
            @Override public boolean beginBatchEdit() {
                batchDepth++;
                return super.beginBatchEdit();
            }

            @Override public boolean endBatchEdit() {
                boolean result = super.endBatchEdit();
                batchDepth = Math.max(0, batchDepth - 1);
                flushEdit();
                return result;
            }

            @Override public boolean deleteSurroundingText(int before, int after) {
                if (getText().length() == 0 && before > 0 && after == 0) {
                    return listener.onEdit(new LiveTextDelta(Math.min(before, 100), ""));
                }
                return super.deleteSurroundingText(before, after);
            }

            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                if (getText().length() == 0 && before > 0 && after == 0) {
                    return listener.onEdit(new LiveTextDelta(Math.min(before, 100), ""));
                }
                return super.deleteSurroundingTextInCodePoints(before, after);
            }

            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL && getText().length() == 0) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) backspace();
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_TAB) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        resetContext();
                        listener.onSpecialKey(event.getKeyCode() == KeyEvent.KEYCODE_ENTER ? "enter" : "tab");
                    }
                    return true;
                }
                return super.sendKeyEvent(event);
            }

            @Override public boolean performEditorAction(int action) {
                resetContext();
                listener.onSpecialKey("enter");
                return true;
            }
        };
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DEL && getText().length() == 0) {
            backspace();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
