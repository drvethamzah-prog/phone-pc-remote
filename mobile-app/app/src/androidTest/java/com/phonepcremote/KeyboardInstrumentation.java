package com.phonepcremote;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/** Runs against real Android Editable/InputConnection; no external test framework required. */
public final class KeyboardInstrumentation extends Instrumentation {
    private final StringBuilder report = new StringBuilder();
    private Bundle arguments;
    private int assertions;

    @Override public void runOnMainSync(Runnable runnable) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        super.runOnMainSync(() -> { try { runnable.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Override public void onCreate(Bundle args) { arguments = args; start(); }

    private void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        assertions++;
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (arguments != null && "true".equals(arguments.getString("noticeOnly"))) {
                testConnectionNotice();
                result.putString("stream", "\nPASS connection notice\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (arguments != null && "true".equals(arguments.getString("scannerOnly"))) {
                testScannerLaunch();
                result.putString("stream", "\nPASS scanner launch\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            testCredentialVault();
            testComputerProfiles();
            runOnMainSync(this::testInputConnection);
            if (arguments != null && arguments.containsKey("port")) testDesktopTransport();
            result.putString("stream", "\nPASS " + assertions + " assertions\n" + report);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL " + error + "\n" + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void testConnectionNotice() throws Exception {
        String language = arguments.getString("language", "en");
        getTargetContext().getSharedPreferences("phone_pc_remote", Context.MODE_PRIVATE)
                .edit().putString("ui_language", language).apply();
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        runOnMainSync(() -> {
            try {
                Method cancel = MainActivity.class.getDeclaredMethod("cancelRecoveryAndRequests");
                cancel.setAccessible(true);
                cancel.invoke(activity);
                Method show = MainActivity.class.getDeclaredMethod("showScreen", int.class);
                show.setAccessible(true);
                show.invoke(activity, 0);
                Method status = MainActivity.class.getDeclaredMethod("setStatus", String.class);
                status.setAccessible(true);
                status.invoke(activity, "Failed pairing with fe80::1234:8765 - diagnostic exception detail");
                Field notice = MainActivity.class.getDeclaredField("noticeText");
                notice.setAccessible(true);
                TextView message = (TextView) notice.get(activity);
                check(message.isShown(), "Offline notice missing");
                check(!message.getText().toString().contains("fe80"), "Technical endpoint leaked into banner");
                Field diagnostic = MainActivity.class.getDeclaredField("statusText");
                diagnostic.setAccessible(true);
                check(((TextView) diagnostic.get(activity)).getText().toString().contains("fe80"),
                        "Diagnostic detail lost");
                check(!((TextView) diagnostic.get(activity)).isShown(), "Diagnostic detail not collapsed");
            } catch (Exception error) { throw new AssertionError(error); }
        });
        waitForIdleSync();
        Thread.sleep(700);
        android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
        check(screenshot != null, "Notice screenshot missing");
        java.io.File path = new java.io.File(getTargetContext().getExternalFilesDir(null), "connection-notice-" + language + ".png");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(path)) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
        screenshot.recycle();
        runOnMainSync(activity::finish);
    }

    private void testScannerLaunch() throws Exception {
        ActivityMonitor monitor = addMonitor("com.phonepcremote.PairingCaptureActivity", null, false);
        Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Map<String, ?> saved = getTargetContext().getSharedPreferences("phone_pc_remote", Context.MODE_PRIVATE).getAll();
        runOnMainSync(() -> {
            try {
                Method scan = MainActivity.class.getDeclaredMethod("scanPairingQr");
                scan.setAccessible(true);
                scan.invoke(activity);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        Activity scanner = waitForMonitorWithTimeout(monitor, 5000);
        check(scanner != null, "Scanner activity did not launch");
        Thread.sleep(2000);
        check(scanner.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_PORTRAIT, "Scanner must open portrait");
        if ("deny".equals(arguments.getString("cameraDecision"))) {
            android.view.accessibility.AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            check(root != null, "Permission dialog unavailable");
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId(
                    "com.android.permissioncontroller:id/permission_deny_button");
            check(!buttons.isEmpty(), "Deny permission button missing");
            check(buttons.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),
                    "Camera denial failed");
            Thread.sleep(1000);
        }
        check(!scanner.isDestroyed(), "Scanner destroyed unexpectedly");
        runOnMainSync(() -> {
            View cancel = scanner.findViewById(R.id.qr_cancel);
            check(cancel != null && cancel.isShown(), "Visible cancel button missing");
            check(cancel.performClick(), "Cancel button failed");
        });
        waitForIdleSync();
        check(scanner.isFinishing() || scanner.isDestroyed(), "Cancel did not finish scanner");
        check(saved.equals(getTargetContext().getSharedPreferences("phone_pc_remote", Context.MODE_PRIVATE).getAll()),
                "Scanner cancel modified saved settings");
        runOnMainSync(activity::finish);
        removeMonitor(monitor);
    }

    private void testCredentialVault() throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("credential_vault_test", Context.MODE_PRIVATE);
        String alias = "qareeb.test." + java.util.UUID.randomUUID();
        java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        try {
            prefs.edit().clear().putString("token", "legacy-test-secret")
                    .putString("pending_token:old-host:8765", "pending-test-secret").commit();
            CredentialVault vault = new CredentialVault(prefs, alias);
            check("legacy-test-secret".equals(vault.read("token")), "legacy token migration changed secret");
            check("pending-test-secret".equals(vault.read("pending_token:old-host:8765")), "pending secret migration failed");
            check(!"legacy-test-secret".equals(prefs.getString("token", "")), "legacy plaintext retained");
            check(!"pending-test-secret".equals(prefs.getString("pending_token:old-host:8765", "")), "pending plaintext retained");
            String first = prefs.getString("token", "");
            vault.write("token", "legacy-test-secret");
            check(!first.equals(prefs.getString("token", "")), "encryption reused IV");
            check("legacy-test-secret".equals(new CredentialVault(prefs, alias).read("token")), "reload failed");
            String encrypted = prefs.getString("token", "");
            prefs.edit().putString("pending_token:old-host:8765", encrypted).commit();
            boolean rejected = false;
            try { vault.read("pending_token:old-host:8765"); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "ciphertext moved between preference keys");
            check(encrypted.equals(prefs.getString("pending_token:old-host:8765", "")), "failure deleted credential");
            prefs.edit().remove("pending_token:old-host:8765").putString("token", "enc:v1:invalid!").commit();
            rejected = false;
            try { vault.read("token"); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected, "corruption accepted");
            check("enc:v1:invalid!".equals(prefs.getString("token", "")), "corrupt credential overwritten");
            prefs.edit().putString("token", encrypted).commit();
            store.deleteEntry(alias);
            rejected = false;
            try { vault.write("token", "replacement"); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected && !store.containsAlias(alias), "missing key silently recreated");
            check(encrypted.equals(prefs.getString("token", "")), "missing key replaced stored secret");
        } finally {
            prefs.edit().clear().commit();
            if (store.containsAlias(alias)) store.deleteEntry(alias);
        }
    }

    private void testComputerProfiles() throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("computer_profiles_test", Context.MODE_PRIVATE);
        String alias = "qareeb.profiles.test." + java.util.UUID.randomUUID();
        java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        try {
            prefs.edit().clear().putString("host", "192.168.1.10").putInt("port", 8765)
                    .putString("tls_fingerprint", "pin-one").putString("local_ipv6", "[]")
                    .putString("token", "first-computer-secret").commit();
            CredentialVault vault = new CredentialVault(prefs, alias);
            ComputerProfiles profiles = new ComputerProfiles(prefs, vault);
            profiles.migrateCurrent(vault.read("token"));
            String first = profiles.activeId();
            check(profiles.list().size() == 1, "legacy computer not migrated");
            profiles.migrateCurrent(vault.read("token"));
            check(profiles.list().size() == 1, "migration duplicated computer");
            profiles.newComputer();
            check(vault.read("token").isEmpty() && prefs.getString("tls_fingerprint", "").isEmpty(), "new computer inherited credentials");
            profiles.saveConfirmed("192.168.1.20", 9000, "pin-two", "[\"fe80::20\"]",
                    "Second PC", "second-computer-secret", 123);
            String second = profiles.activeId();
            check(!first.equals(second) && profiles.list().size() == 2, "computers merged");
            check(!prefs.getString("computer_profiles", "").contains("secret"), "metadata leaked token");
            check("first-computer-secret".equals(profiles.select(first)), "wrong first credential");
            check("192.168.1.10".equals(prefs.getString("host", "")) && prefs.getInt("port", 0) == 8765
                    && "pin-one".equals(prefs.getString("tls_fingerprint", "")), "first endpoint/pin not restored");
            check("second-computer-secret".equals(profiles.select(second)), "wrong second credential");
            check("pin-two".equals(prefs.getString("tls_fingerprint", "")) && prefs.getInt("port", 0) == 9000
                    && prefs.getString("local_ipv6", "").contains("fe80::20"), "second context not restored");
            profiles = new ComputerProfiles(prefs, vault);
            check(profiles.list().size() == 2 && second.equals(profiles.activeId()), "profile restart lost selection");
            profiles.saveConfirmed("192.168.1.21", 9000, "pin-two", "[]", "Second renamed", "second-computer-secret", 456);
            check(profiles.list().size() == 2 && "192.168.1.21".equals(profiles.find(second).optString("host")), "address change duplicated computer");
            String encrypted = prefs.getString("computer_token:" + second, "");
            String firstEncrypted = prefs.getString("computer_token:" + first, "");
            prefs.edit().putString("computer_token:" + first, encrypted).commit();
            boolean rejected = false;
            try { profiles.select(first); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected && second.equals(profiles.activeId()), "swapped ciphertext changed selection");
            prefs.edit().putString("computer_token:" + first, firstEncrypted).commit();
            vault.write("pending_token:" + first + ":192.168.1.10:8765", "pending-first-secret");
            vault.write("pending_token:192.168.1.10:8765", "legacy-pending-secret");
            profiles.forget(first);
            check(!prefs.contains("pending_token:" + first + ":192.168.1.10:8765"), "forgotten scoped pending secret retained");
            check(!prefs.contains("pending_token:192.168.1.10:8765"), "forgotten legacy pending secret retained");
            check(profiles.list().size() == 1 && !prefs.contains("computer_token:" + first), "forgotten computer secret retained");
            check("second-computer-secret".equals(vault.read("token")) && second.equals(profiles.activeId()), "forget inactive disturbed active");
            profiles.forget(second);
            check(profiles.list().isEmpty() && vault.read("token").isEmpty() && profiles.activeId().isEmpty(), "forget active retained access");
            prefs.edit().putString("computer_profiles", "broken").commit();
            rejected = false;
            try { profiles.migrateCurrent("secret"); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected && "broken".equals(prefs.getString("computer_profiles", "")), "corrupt profiles silently reset");
        } finally {
            prefs.edit().clear().commit();
            if (store.containsAlias(alias)) store.deleteEntry(alias);
        }
    }

    private void testInputConnection() {
        StringBuilder remote = new StringBuilder();
        ArrayList<LiveTextDelta> events = new ArrayList<>();
        RemoteKeyboardInput input = new RemoteKeyboardInput(getTargetContext(), new RemoteKeyboardInput.Listener() {
            @Override public boolean onEdit(LiveTextDelta edit) {
                events.add(edit);
                int remove = Math.min(edit.backspaces, remote.codePointCount(0, remote.length()));
                remote.setLength(remote.offsetByCodePoints(remote.length(), -remove));
                remote.append(edit.text);
                return true;
            }
            @Override public void onSpecialKey(String key) { remote.append("<").append(key).append(">"); }
        });
        InputConnection ime = input.onCreateInputConnection(new EditorInfo());
        ime.setComposingText("s", 1);
        check(remote.toString().equals("s"), "first composing letter was delayed");
        ime.setComposingText("sa", 1);
        check(remote.toString().equals("sa"), "second composing letter");
        int count = events.size();
        ime.commitText("sa", 1);
        check(remote.toString().equals("sa") && count == events.size(), "commit duplicated composition");
        ime.commitText(" ", 1);
        ime.setComposingText("teh", 1);
        ime.commitText("the", 1);
        check(remote.toString().equals("sa the"), "autocorrection replacement");
        ime.deleteSurroundingText(1, 0);
        check(remote.toString().equals("sa th"), "surrounding deletion");
        input.resetContext(); remote.setLength(0); events.clear();
        ime = input.onCreateInputConnection(new EditorInfo());
        ime.setComposingText("\u0633", 1);
        check(remote.toString().equals("\u0633"), "first Arabic letter was delayed");
        ime.setComposingText("\u0633\u0644", 1);
        check(remote.toString().equals("\u0633\u0644"), "second Arabic letter");
        ime.setComposingText("\u0633\u0644\u0627\u0645", 1);
        count = events.size();
        ime.finishComposingText();
        check(remote.toString().equals("\u0633\u0644\u0627\u0645") && count == events.size(), "finish duplicated Arabic");
        input.backspace();
        check(remote.toString().equals("\u0633\u0644\u0627"), "toolbar deletion lost IME context");
        input.resetContext();
        check(remote.toString().equals("\u0633\u0644\u0627"), "context reset deleted remote text");
        ime = input.onCreateInputConnection(new EditorInfo());
        ime.deleteSurroundingText(1, 0);
        check(remote.toString().equals("\u0633\u0644"), "backspace at empty local buffer");
        count = events.size();
        ime.deleteSurroundingText(0, 0);
        check(events.size() == count, "zero deletion emitted backspace");
        input.resetContext(); remote.setLength(0);
        ime.beginBatchEdit(); ime.beginBatchEdit();
        ime.setComposingText("a", 1); ime.setComposingText("ab", 1);
        check(remote.length() == 0, "batch leaked intermediate state");
        ime.endBatchEdit();
        check(remote.length() == 0, "nested batch ended early");
        ime.endBatchEdit();
        check(remote.toString().equals("ab"), "batch not flushed");
        ime.commitText("ab", 1);
        check(remote.toString().equals("ab"), "batch commit duplicated");
        input.resetContext(); remote.setLength(0);
        ime.commitText("\uD83D\uDE00", 1);
        ime.deleteSurroundingTextInCodePoints(1, 0);
        check(remote.length() == 0, "Unicode code point deletion");
        ime.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        ime.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        check(remote.toString().equals("<enter>"), "Enter duplicated key up");
        ime.performEditorAction(EditorInfo.IME_ACTION_DONE);
        check(remote.toString().equals("<enter><enter>"), "IME editor action");
        RemoteKeyboardInput blocked = new RemoteKeyboardInput(getTargetContext(), new RemoteKeyboardInput.Listener() {
            @Override public boolean onEdit(LiveTextDelta edit) { return false; }
            @Override public void onSpecialKey(String key) { }
        });
        blocked.onCreateInputConnection(new EditorInfo()).commitText("not sent", 1);
        check(blocked.getText().length() == 0, "rejected input retained stale context");
        report.append("Android IME: composition, commit, Arabic, correction, deletion, nested batches, Enter, reset, rejected input.\n");
    }

    private Object field(Activity activity, String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }

    private void invoke(Activity activity, String name, Class<?>[] types, Object... args) {
        runOnMainSync(() -> {
            try { Method method = MainActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity, args); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
    }

    private JSONObject snapshot(int port) throws Exception {
        try (ControlConnection connection = new ControlConnection(1500)) {
            return new JSONObject(connection.exchange("10.0.2.2", port, "{\"type\":\"test_snapshot\"}"));
        }
    }

    private void expectRemote(int port, String text) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (text.equals(snapshot(port).optString("text"))) { assertions++; return; }
            Thread.sleep(25);
        }
        throw new AssertionError("Desktop text mismatch: " + snapshot(port));
    }

    private View findAction(View view, String label) {
        if (view.isClickable() && (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                || view instanceof TextView && label.contentEquals(((TextView) view).getText()))) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View match = findAction(group.getChildAt(i), label);
                if (match != null) return match;
            }
        }
        return null;
    }

    private void testRemoteButtons(Activity activity, int port) throws Exception {
        invoke(activity, "showScreen", new Class<?>[]{int.class}, 1);
        int[] labels = {R.string.ui_015, R.string.ui_016, R.string.ui_017, R.string.ui_018,
                R.string.ui_019, R.string.ui_020, R.string.ui_022, R.string.ui_023};
        int[] keys = {0xB1, 0xB3, 0xB0, 0xAF, 0xAD, 0xAE, 0x43, 0x56, 0x09};
        View root = ((View[]) field(activity, "screens"))[1];
        for (int i = 0; i < keys.length; i++) {
            String label = i < labels.length ? activity.getString(labels[i]) : "Alt+Tab";
            int before = snapshot(port).getJSONArray("events").length();
            runOnMainSync(() -> {
                View action = findAction(root, label);
                if (action == null) throw new AssertionError("Missing remote button: " + label);
                action.performClick();
            });
            JSONObject event = null;
            for (int attempt = 0; attempt < 60; attempt++) {
                org.json.JSONArray events = snapshot(port).getJSONArray("events");
                if (events.length() > before) { event = events.getJSONObject(before); break; }
                Thread.sleep(25);
            }
            check(event != null && event.optInt("key") == keys[i], "Wrong remote command: " + label);
            if (i >= 6) check(event.getJSONArray("modifiers").getInt(0) == (i == 8 ? 0x12 : 0x11), "Shortcut modifier: " + label);
        }
        invoke(activity, "showScreen", new Class<?>[]{int.class}, 0);
        report.append("Remote: nine real button clicks; transport, volume, mute, copy, paste, Alt+Tab accepted.\n");
    }

    private void testDesktopTransport() throws Exception {
        int port = Integer.parseInt(arguments.getString("port"));
        SharedPreferences prefs = getTargetContext().getSharedPreferences("phone_pc_remote", Context.MODE_PRIVATE);
        Map<String, ?> saved = prefs.getAll();
        Activity activity = null;
        try {
            prefs.edit().clear().putString("device_id", "dashboard-instrumentation").putString("host", "10.0.2.2")
                    .putString("ui_language", arguments.getString("language", "ar"))
                    .putInt("port", port).putString("token", "dashboard-test-token").putBoolean("keyboard_open", false).commit();
            Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(intent);
            for (int i = 0; i < 100 && !(boolean) field(activity, "connected"); i++) Thread.sleep(50);
            check((boolean) field(activity, "connected"), "real activity did not reconnect: "
                    + ((TextView) field(activity, "statusText")).getText());
            check(!snapshot(port).optBoolean("helloKeepAlive", true), "pairing must use a one-shot connection");
            boolean arabic = "ar".equals(activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
            check(activity.getString(R.string.ui_008).equals(arabic ? "\u0627\u0644\u0623\u062c\u0647\u0632\u0629" : "Devices"), "localized navigation");
            check(activity.getWindow().getDecorView().getLayoutDirection() == (arabic ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR), "locale direction");
            check("dashboard-test-token".equals(new CredentialVault(prefs).read("token")), "language changed pairing token");
            check(!"dashboard-test-token".equals(prefs.getString("token", "")), "token persisted as plaintext");
            String cachedIpv6 = prefs.getString("local_ipv6", "");
            check(cachedIpv6.contains("fe80:") && !cachedIpv6.contains("2001:"), "IPv6 endpoint cache not filtered");
            Method resolve = MainActivity.class.getDeclaredMethod("localAddress", String.class); resolve.setAccessible(true);
            java.net.Inet6Address scoped = (java.net.Inet6Address) resolve.invoke(activity, "fe80::1234%999");
            check(scoped.getScopeId() > 0 && scoped.getScopeId() != 999, "desktop scope reused on Android");
            report.append("Locale: ").append(arguments.getString("language", "ar")).append("; labels, direction, pairing preserved.\n");
            waitForIdleSync();
            RemoteKeyboardInput input = (RemoteKeyboardInput) field(activity, "textInput");
            InputConnection[] ime = new InputConnection[1];
            runOnMainSync(() -> { input.requestFocus(); ime[0] = input.onCreateInputConnection(new EditorInfo()); });
            runOnMainSync(() -> ime[0].setComposingText("s", 1)); expectRemote(port, "s");
            runOnMainSync(() -> ime[0].setComposingText("sa", 1)); expectRemote(port, "sa");
            runOnMainSync(() -> ime[0].commitText("sa", 1)); expectRemote(port, "sa");
            runOnMainSync(() -> { ime[0].commitText(" ", 1); ime[0].setComposingText("\u0633", 1); });
            expectRemote(port, "sa \u0633");
            runOnMainSync(() -> ime[0].setComposingText("\u0633\u0644\u0627\u0645", 1));
            expectRemote(port, "sa \u0633\u0644\u0627\u0645");
            runOnMainSync(() -> ime[0].commitText("\u0633\u0644\u0627\u0645", 1));
            runOnMainSync(input::backspace); expectRemote(port, "sa \u0633\u0644\u0627");
            invoke(activity, "controlStop", new Class<?>[0]);
            for (int i = 0; i < 60 && snapshot(port).optBoolean("controlEnabled"); i++) Thread.sleep(25);
            check(!snapshot(port).optBoolean("controlEnabled"), "stop did not reach server");
            runOnMainSync(() -> ime[0].commitText("blocked", 1)); expectRemote(port, "sa \u0633\u0644\u0627");
            invoke(activity, "controlStart", new Class<?>[0]);
            for (int i = 0; i < 60 && (boolean) field(activity, "controlsStopped"); i++) Thread.sleep(25);
            check(!(boolean) field(activity, "controlsStopped"), "resume did not acknowledge");
            runOnMainSync(() -> ime[0].commitText("!", 1)); expectRemote(port, "sa \u0633\u0644\u0627!");
            for (int screen : new int[]{1, 2, 0}) {
                invoke(activity, "showScreen", new Class<?>[]{int.class}, screen);
                View[] screens = (View[]) field(activity, "screens");
                check(screens[screen].getVisibility() == View.VISIBLE, "navigation screen missing");
            }
            testRemoteButtons(activity, port);
            testConnectionRecovery(activity, port, input);
            testNetworkChange(activity);
            testComputerSwitch(activity, prefs);
            check(snapshot(port).optInt("errors") == 0, "server rejected commands");
            report.append("Desktop transport: real Activity + IME to isolated Python server, every letter before commit, no duplication, Arabic, stop/resume, tabs.\n");
            if ("true".equals(arguments.getString("preview"))) {
                invoke(activity, "openRemoteKeyboard", new Class<?>[0]);
                Bundle progress = new Bundle(); progress.putString("stream", "PREVIEW_READY\n"); sendStatus(1, progress);
                for (int i = 0; i < 300 && !snapshot(port).optBoolean("closePreview"); i++) Thread.sleep(1000);
            }
        } finally {
            if (activity != null) { Activity target = activity; runOnMainSync(target::finish); waitForIdleSync(); }
            SharedPreferences.Editor restore = prefs.edit().clear();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof String) restore.putString(key, (String) value);
                else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                else if (value instanceof Long) restore.putLong(key, (Long) value);
                else if (value instanceof Float) restore.putFloat(key, (Float) value);
                else if (value instanceof Set) restore.putStringSet(key, (Set<String>) value);
            }
            restore.commit();
        }
    }

    private void testComputerSwitch(Activity activity, SharedPreferences prefs) throws Exception {
        ComputerProfiles profiles = (ComputerProfiles) field(activity, "computerProfiles");
        String real = profiles.activeId();
        String oldPin = prefs.getString("tls_fingerprint", "");
        try {
            prefs.edit().putString("tls_fingerprint", "queued-pin").commit();
            Class<?> commandType = Class.forName("com.phonepcremote.MainActivity$PendingControl");
            java.lang.reflect.Constructor<?> constructor = commandType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object command = constructor.newInstance(activity, "10.0.2.2", 8765, new JSONObject(), null, true, 0);
            prefs.edit().putString("tls_fingerprint", "another-computer-pin").commit();
            Field pinField = commandType.getDeclaredField("tlsPin");
            pinField.setAccessible(true);
            check("queued-pin".equals(pinField.get(command)), "queued request borrowed another computer pin");
        } finally { prefs.edit().putString("tls_fingerprint", oldPin).commit(); }
        check(profiles.list().size() == 1 && !real.isEmpty(), "confirmed computer not listed");
        invoke(activity, "showScreen", new Class<?>[]{int.class}, 2);
        invoke(activity, "startNewComputer", new Class<?>[0]);
        check(!(boolean) field(activity, "connected") && "".equals(field(activity, "token")), "add computer retained active session");
        check(profiles.list().size() == 1, "add computer deleted existing pairing");
        profiles.saveConfirmed("192.168.1.254", 9001, "", "[]", "Other saved computer", "other-computer-secret", 0);
        String other = profiles.activeId();
        invoke(activity, "connectComputer", new Class<?>[]{String.class}, real);
        for (int i = 0; i < 200 && !(boolean) field(activity, "connected"); i++) Thread.sleep(50);
        check((boolean) field(activity, "connected") && real.equals(profiles.activeId()), "selected computer did not reconnect");
        check("dashboard-test-token".equals(field(activity, "token")), "switch used other computer token");
        check(profiles.list().size() == 2, "switch lost saved computer");
        invoke(activity, "showScreen", new Class<?>[]{int.class}, 2);
        waitForIdleSync();
        android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
        check(screenshot != null, "computer list screenshot unavailable");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(
                getTargetContext().getExternalFilesDir(null), "computers-" + arguments.getString("language", "en") + ".png"))) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { screenshot.recycle(); }
        invoke(activity, "forgetComputer", new Class<?>[]{String.class}, other);
        check((boolean) field(activity, "connected") && profiles.list().size() == 1, "forget inactive interrupted connection");
        check(!prefs.contains("computer_token:" + other), "forgotten secret retained in real Activity");
        invoke(activity, "showScreen", new Class<?>[]{int.class}, 2);
        report.append("Computers: real Activity add/switch/reconnect/forget inactive; isolated credentials and current pairing retained.\n");
    }

    private void testConnectionRecovery(Activity activity, int port, RemoteKeyboardInput input) throws Exception {
        Method discover = MainActivity.class.getDeclaredMethod("findServerByUdp", String.class, int.class, String.class);
        discover.setAccessible(true);
        Object found = discover.invoke(activity, "10.0.2.2", port, "dashboard-test-token");
        check(found != null, "authenticated UDP discovery failed");
        Field host = found.getClass().getDeclaredField("host"); host.setAccessible(true);
        check("10.0.2.2".equals(host.get(found)), "discovery did not use packet source");
        check(discover.invoke(activity, "10.0.2.2", port, "wrong-token") == null, "untrusted server accepted");
        String before = snapshot(port).optString("text");
        try (ControlConnection connection = new ControlConnection(1500)) {
            connection.exchange("10.0.2.2", port, "{\"type\":\"test_drop_next_input\"}");
        }
        runOnMainSync(() -> input.onCreateInputConnection(new EditorInfo()).commitText("X", 1));
        expectRemote(port, before + "X");
        boolean disconnected = false;
        for (int i = 0; i < 100; i++) {
            if (!(boolean) field(activity, "connected")) { disconnected = true; break; }
            Thread.sleep(20);
        }
        check(disconnected, "lost input response did not disconnect");
        for (int i = 0; i < 150 && !(boolean) field(activity, "connected"); i++) Thread.sleep(50);
        check((boolean) field(activity, "connected"), "automatic handshake recovery failed");
        check(snapshot(port).optString("text").equals(before + "X"), "uncertain input was replayed");
        invoke(activity, "onStop", new Class<?>[0]);
        check(!(boolean) field(activity, "connected") && !(boolean) field(activity, "recoveryPending"), "background recovery not cancelled");
        invoke(activity, "onStart", new Class<?>[0]);
        for (int i = 0; i < 100 && !(boolean) field(activity, "connected"); i++) Thread.sleep(50);
        check((boolean) field(activity, "connected"), "foreground lifecycle recovery failed");
        invoke(activity, "cancelRecoveryAndRequests", new Class<?>[0]);
        runOnMainSync(() -> {
            try { ((android.widget.EditText) field(activity, "hostInput")).setText("127.0.0.2"); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        invoke(activity, "reconnectWithToken", new Class<?>[0]);
        for (int i = 0; i < 400 && !(boolean) field(activity, "connected"); i++) Thread.sleep(50);
        check((boolean) field(activity, "connected"), "stale server IP was not rediscovered");
        check("10.0.2.2".contentEquals(((android.widget.EditText) field(activity, "hostInput")).getText()), "new endpoint not saved");
        report.append("Recovery: authenticated UDP, wrong-secret rejection, lost input reply, no replay, foreground lifecycle.\n");
    }

    private void shell(String command) throws Exception {
        try (java.io.InputStream stream = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) { }
        }
    }

    private void testNetworkChange(Activity activity) throws Exception {
        if (!android.os.Build.MODEL.contains("sdk")) throw new AssertionError("Network switch test is emulator-only");
        try {
            shell("svc wifi disable");
            for (int i = 0; i < 200 && (boolean) field(activity, "connected"); i++) Thread.sleep(50);
            check(!(boolean) field(activity, "connected"), "Wi-Fi loss did not invalidate session");
            boolean blocked = false;
            Method network = MainActivity.class.getDeclaredMethod("localNetwork"); network.setAccessible(true);
            try { network.invoke(activity); }
            catch (java.lang.reflect.InvocationTargetException expected) { blocked = expected.getCause() instanceof java.io.IOException; }
            check(blocked, "cellular/no-network connection was permitted");
        } finally {
            shell("svc wifi enable");
            shell("cmd wifi connect-network AndroidWifi open");
        }
        for (int i = 0; i < 600 && !(boolean) field(activity, "connected"); i++) Thread.sleep(50);
        check((boolean) field(activity, "connected"), "real Wi-Fi return did not reconnect");
        report.append("Network callbacks: real emulator Wi-Fi off/on; local-only gate; automatic recovery.\n");
    }
}
