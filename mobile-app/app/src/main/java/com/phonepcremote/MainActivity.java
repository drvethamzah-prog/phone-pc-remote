package com.phonepcremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends Activity {
    private static final int DEFAULT_PORT = 8765;
    private static final String DEFAULT_PAIRING_CODE = "111111";
    private static final String EMULATOR_HOST = "10.0.2.2";
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 7000;
    private static final int DISCOVERY_CONNECT_TIMEOUT_MS = 350;
    private static final int DISCOVERY_READ_TIMEOUT_MS = 600;
    private static final int DISCOVERY_SCAN_THREADS = 64;
    private static final int DISCOVERY_SCAN_SECONDS = 12;
    private int COLOR_BACKGROUND = 0xFFF1F5F3;
    private int COLOR_SURFACE = 0xFFFFFFFF;
    private int COLOR_TEXT = 0xFF202728;
    private int COLOR_MUTED = 0xFF626C6C;
    private int COLOR_BORDER = 0xFFDCE3E0;
    private int COLOR_PRIMARY = 0xFF08776B;
    private int COLOR_PRIMARY_DARK = 0xFF08776B;
    private int COLOR_DANGER = 0xFFB12D38;

    private SharedPreferences prefs;
    private CredentialVault credentialVault;
    private ComputerProfiles computerProfiles;
    private LinearLayout computerList;
    private final ThreadLocal<String> controlTlsPin = new ThreadLocal<>();
    private boolean credentialFailure;
    private ExecutorService networkExecutor;
    private ExecutorService controlExecutor;
    private final ControlConnection controlConnection = new ControlConnection(2000, this::createLocalSocket, this::secureLocalSocket);
    private final ArrayDeque<PendingControl> pendingControls = new ArrayDeque<>();
    private boolean controlWorkerRunning;
    private long controlRetryAfter;
    private long latencyUpdatedAt;
    private final AdaptiveLatency adaptiveLatency = new AdaptiveLatency();
    private Handler mainHandler;
    private boolean focusCheckInProgress;
    private String lastRemoteFocus = "";
    private final Runnable focusTask = new Runnable() {
        @Override public void run() {
            if (!foreground || isDestroyed()) return;
            checkRemoteInputFocus();
            mainHandler.postDelayed(this, 750);
        }
    };

    private EditText hostInput;
    private View connectionModeRow;
    private EditText portInput;
    private EditText pairingCodeInput;
    private EditText accountEmailInput;
    private RemoteKeyboardInput textInput;
    private TextView statusText;
    private TextView latencyText;
    private TextView responseText;
    private String deviceId;
    private String deviceName;
    private String token;
    private TextView connectionLabel;
    private TextView noticeText;
    private LinearLayout connectionNotice;
    private TextView connectionNoticeTitle;
    private LinearLayout navigation;
    private final View[] screens = new View[3];
    private final Button[] navButtons = new Button[3];
    private ImageButton keyboardButton;
    private ImageButton stopButton;
    private Button ctrlButton;
    private Button altButton;
    private boolean ctrlPressed;
    private boolean altPressed;
    private boolean connected;
    private boolean controlsStopped;
    private boolean keyboardVisible;
    private int selectedScreen;
    private String computerName = "";
    private int languageReturnScreen = -1;
    private boolean pairingInProgress;
    private volatile int pairingGeneration;
    private volatile ControlConnection pairingConnection;
    private final ReconnectPolicy reconnectPolicy = new ReconnectPolicy();
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String networkFingerprint = "";
    private volatile Network transportNetwork;
    private boolean foreground;
    private boolean recoveryPending;
    private boolean healthCheckInProgress;
    private int recoveryGeneration;
    private final Runnable recoveryTask = () -> {
        recoveryPending = false;
        if (foreground && !isDestroyed() && !connected && !pairingInProgress) reconnectWithToken(true);
    };
    private final Runnable healthTask = new Runnable() {
        @Override public void run() {
            if (!foreground || isDestroyed()) return;
            checkSessionHealth();
            mainHandler.postDelayed(this, 5000);
        }
    };

    interface ResponseHandler {
        void onResponse(JSONObject response);
    }

    private class PendingControl {
        final String tlsPin = prefs.getString("tls_fingerprint", "");
        final String host;
        final int port;
        final JSONObject payload;
        final ResponseHandler handler;
        final boolean quiet;
        final int generation;
        long queuedAt = System.nanoTime();

        PendingControl(String host, int port, JSONObject payload, ResponseHandler handler, boolean quiet, int generation) {
            this.host = host;
            this.port = port;
            this.payload = payload;
            this.handler = handler;
            this.quiet = quiet;
            this.generation = generation;
        }
    }

    private static class DiscoveryResult {
        final JSONObject response;
        final String host;
        final int port;

        DiscoveryResult(JSONObject response, String host, int port) {
            this.response = response;
            this.host = host;
            this.port = port;
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        String language = base.getSharedPreferences("phone_pc_remote", MODE_PRIVATE).getString("ui_language", "en");
        if (!"system".equals(language)) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            Locale locale = Locale.forLanguageTag(language);
            config.setLocale(locale);
            config.setLayoutDirection(locale);
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }

    private boolean isArabic() {
        Configuration config = getResources().getConfiguration();
        return "ar".equals((Build.VERSION.SDK_INT >= 24 ? config.getLocales().get(0) : config.locale).getLanguage());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("phone_pc_remote", MODE_PRIVATE);
        adaptiveLatency.setEnabled(prefs.getBoolean("adaptive_connection", false));
        computerName = getString(R.string.ui_001);
        languageReturnScreen = getIntent().getIntExtra("language_return_screen", -1);
        getIntent().removeExtra("language_return_screen");
        networkExecutor = Executors.newFixedThreadPool(4);
        controlExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        deviceId = getOrCreateDeviceId();
        deviceName = buildDeviceName();
        credentialVault = new CredentialVault(prefs);
        computerProfiles = new ComputerProfiles(prefs, credentialVault);
        try {
            token = credentialVault.read("token");
            computerProfiles.migrateCurrent(token);
        } catch (IOException error) {
            token = "";
            credentialFailure = true;
        }
        if ((getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) {
            COLOR_BACKGROUND = 0xFF222A27;
            COLOR_SURFACE = 0xFF191C1D;
            COLOR_TEXT = 0xFFEDF1F0;
            COLOR_MUTED = 0xFFA6B1AE;
            COLOR_BORDER = 0xFF363E3B;
            COLOR_PRIMARY = COLOR_PRIMARY_DARK = 0xFF6BDDC8;
            COLOR_DANGER = 0xFFFF8992;
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 26) {
            boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(dark ? flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            getWindow().setNavigationBarColor(COLOR_SURFACE);
        }
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        setContentView(prefs.getString("cloud_access_token", "").isEmpty() ? buildAuthGate() : buildUi());
        setStatus(credentialFailure ? getString(R.string.credential_storage_error) : initialStatusMessage());
        watchLocalNetwork();
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        reconnectPolicy.reset();
        scheduleRecovery();
        mainHandler.removeCallbacks(healthTask);
        mainHandler.postDelayed(healthTask, 5000);
        mainHandler.postDelayed(focusTask, 750);
    }

    @Override
    protected void onStop() {
        foreground = false;
        mainHandler.removeCallbacks(healthTask);
        mainHandler.removeCallbacks(focusTask);
        cancelRecoveryAndRequests();
        super.onStop();
    }

    private void cancelRecoveryAndRequests() {
        lastRemoteFocus = "";
        adaptiveLatency.reset();
        recoveryGeneration++;
        mainHandler.removeCallbacks(recoveryTask);
        recoveryPending = false;
        pairingGeneration++;
        pairingInProgress = false;
        ControlConnection pairing = pairingConnection;
        if (pairing != null) pairing.close();
        controlConnection.disconnect();
        synchronized (pendingControls) { pendingControls.clear(); }
        connected = false;
        resetKeyboardContext();
        refreshConnectionLabel();
    }

    private void scheduleRecovery() {
        if (!foreground || isDestroyed() || connected || pairingInProgress || recoveryPending
                || token == null || token.isEmpty()) return;
        long delay = reconnectPolicy.nextDelay(java.util.concurrent.ThreadLocalRandom.current().nextInt(251));
        if (delay < 0) return;
        recoveryPending = true;
        mainHandler.postDelayed(recoveryTask, delay);
    }

    private void checkSessionHealth() {
        if (!connected || healthCheckInProgress || pairingInProgress) return;
        final int generation = pairingGeneration;
        final String host = hostInput.getText().toString().trim();
        final int port = parsePort();
        final String savedToken = token;
        healthCheckInProgress = true;
        networkExecutor.execute(() -> {
            Exception failure = null;
            try (ControlConnection connection = new ControlConnection(2000, this::createLocalSocket, this::secureLocalSocket)) {
                String nonce = UUID.randomUUID().toString();
                JSONObject request = new JSONObject().put("type", "identify").put("deviceId", deviceId).put("nonce", nonce);
                JSONObject response = new JSONObject(connection.exchange(localAddress(host).getHostAddress(), port, request.toString()));
                if (!DiscoveryAuthentication.verifies(savedToken, nonce, port, response.optString("discoveryProof"))) {
                    throw new IOException(getString(R.string.server_identity_unverified));
                }
            } catch (Exception exc) { failure = exc; }
            final Exception error = failure;
            mainHandler.post(() -> {
                healthCheckInProgress = false;
                if (!foreground || isDestroyed() || generation != pairingGeneration || error == null) return;
                cancelRecoveryAndRequests();
                hideRemoteKeyboard();
                setStatus(connectionErrorMessage(host, port, "hello", error));
                reconnectPolicy.reset();
                scheduleRecovery();
            });
        });
    }

    private void checkRemoteInputFocus() {
        if (!connected || pairingInProgress || controlsStopped || selectedScreen != 0
                || focusCheckInProgress || !prefs.getBoolean("auto_keyboard_focus", true)) return;
        final int generation = pairingGeneration;
        final String host = prefs.getString("host", "");
        final int port = prefs.getInt("port", DEFAULT_PORT);
        final String pin = prefs.getString("tls_fingerprint", "");
        final JSONObject request;
        try { request = authenticatedPayload("input_focus"); }
        catch (Exception ignored) { return; }
        focusCheckInProgress = true;
        networkExecutor.execute(() -> {
            JSONObject result = null;
            try {
                if (generation == pairingGeneration) result = sendTcp(host, port, request, 1000, 1000, pin);
            } catch (Exception ignored) { /* Optional capability; never reconnect on probe failure. */ }
            final JSONObject response = result;
            mainHandler.post(() -> {
                focusCheckInProgress = false;
                if (!foreground || isDestroyed() || generation != pairingGeneration || !connected
                        || controlsStopped || selectedScreen != 0
                        || !prefs.getBoolean("auto_keyboard_focus", true) || response == null
                        || !"input_focus".equals(response.optString("type"))
                        || !response.optBoolean("supported")) return;
                String focus = response.optBoolean("editable") ? response.optString("focusId") : "";
                if (!focus.isEmpty() && !focus.equals(lastRemoteFocus)) openRemoteKeyboard();
                lastRemoteFocus = focus;
            });
        });
    }

    private void watchLocalNetwork() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                mainHandler.post(MainActivity.this::refreshLocalNetwork);
            }
            @Override public void onLost(Network network) {
                mainHandler.post(() -> {
                    if (isDestroyed()) return;
                    // onLost is authoritative; a synchronous OS snapshot can still be stale here.
                    if (network.equals(transportNetwork)) {
                        transportNetwork = null;
                        networkFingerprint = "";
                        cancelRecoveryAndRequests();
                        reconnectPolicy.reset();
                        setStatus(getString(R.string.local_network_lost));
                        scheduleRecovery();
                    } else {
                        refreshLocalNetwork();
                    }
                });
            }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                mainHandler.post(MainActivity.this::refreshLocalNetwork);
            }
        };
        // Observe local-only Wi-Fi too: it may not be Android's Internet/default network.
        connectivityManager.registerNetworkCallback(new NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback);
    }

    private void refreshLocalNetwork() {
        if (isDestroyed()) return;
        String fingerprint = "";
        try {
            Network network = localNetwork();
            LinkProperties properties = connectivityManager.getLinkProperties(network);
            if (properties == null || properties.getLinkAddresses().isEmpty()) return;
            fingerprint = network.toString() + ":" + properties.getLinkAddresses().toString();
        } catch (IOException ignored) { }
        if (fingerprint.equals(networkFingerprint)) return;
        networkFingerprint = fingerprint;
        cancelRecoveryAndRequests();
        reconnectPolicy.reset();
        if (fingerprint.isEmpty()) {
            setStatus(getString(R.string.local_network_lost));
        } else {
            scheduleRecovery();
        }
    }

    private Network localNetwork() throws IOException {
        if (connectivityManager == null) throw new IOException(getString(R.string.local_network_required));
        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(network);
        if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            throw new IOException(getString(R.string.local_network_required));
        }
        if (isLocalTransport(capabilities)) return network;
        for (Network candidate : connectivityManager.getAllNetworks()) {
            if (isLocalTransport(connectivityManager.getNetworkCapabilities(candidate))) return candidate;
        }
        throw new IOException(getString(R.string.local_network_required));
    }

    private boolean isLocalTransport(NetworkCapabilities capabilities) {
        if (capabilities == null) return false;
        return LocalTransportPolicy.allows(prefs.getString("connection_mode", "auto"),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                Build.VERSION.SDK_INT >= 31 && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
    }

    private void showConnectionMode() {
        String[] codes = {"auto", "usb"};
        int selected = "usb".equals(prefs.getString("connection_mode", "auto")) ? 1 : 0;
        new AlertDialog.Builder(this).setTitle(R.string.transport_mode)
                .setSingleChoiceItems(new String[]{getString(R.string.transport_auto), getString(R.string.transport_usb)},
                        selected, (dialog, which) -> {
                            if (which == 1 && Build.VERSION.SDK_INT < 31) {
                                setStatus(getString(R.string.transport_usb_requires_android));
                                dialog.dismiss();
                                return;
                            }
                            if (!codes[which].equals(prefs.getString("connection_mode", "auto"))) {
                                cancelRecoveryAndRequests();
                                prefs.edit().putString("connection_mode", codes[which]).apply();
                                if (connectionModeRow != null) {
                                    TextView detail = connectionModeRow.findViewWithTag("setting_value");
                                    if (detail != null) detail.setText(which == 1 ? R.string.transport_usb_short : R.string.transport_auto_short);
                                }
                                networkFingerprint = "";
                                reconnectPolicy.reset();
                                refreshLocalNetwork();
                                if (!connected) scheduleRecovery();
                            }
                            dialog.dismiss();
                        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private Socket createLocalSocket() throws IOException {
        Network network = localNetwork();
        transportNetwork = network;
        return network.getSocketFactory().createSocket();
    }

    private Socket secureLocalSocket(Socket socket, String host, int port) throws IOException {
        String pin = controlTlsPin.get();
        if (pin == null) pin = prefs.getString("tls_fingerprint", "");
        return pin.isEmpty() ? socket : PinnedTls.upgrade(socket, host, port, pin);
    }

    private void showTlsIdentity() {
        EditText input = input(getString(R.string.tls_fingerprint), prefs.getString("tls_fingerprint", ""), InputType.TYPE_CLASS_TEXT);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.tls_fingerprint)
                .setView(input).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String pin = PinnedTls.normalize(input.getText().toString());
                cancelRecoveryAndRequests();
                if (!prefs.edit().putString("tls_fingerprint", pin).commit()) {
                    input.setError(getString(R.string.tls_save_failed));
                    return;
                }
                dialog.dismiss();
                setStatus(getString(R.string.tls_saved));
            } catch (IllegalArgumentException error) {
                input.setError(getString(R.string.tls_invalid));
            }
        }));
        dialog.show();
    }

    private void scanPairingQr() {
        new com.google.zxing.integration.android.IntentIntegrator(this)
                .setCaptureActivity(PairingCaptureActivity.class)
                .setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                .setOrientationLocked(false)
                .setPrompt(getString(R.string.qr_scan)).setBeepEnabled(false).initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        com.google.zxing.integration.android.IntentResult result =
                com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (result.getContents() == null) return;
        try {
            QrEnrollment qr = QrEnrollment.parse(result.getContents(), System.currentTimeMillis() / 1000);
            new AlertDialog.Builder(this).setTitle(R.string.qr_confirm)
                    .setMessage(qr.host + ":" + qr.port + "\nSHA-256\n" + qr.fingerprint)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        try {
                            QrEnrollment.parse(result.getContents(), System.currentTimeMillis() / 1000);
                            cancelRecoveryAndRequests();
                            computerProfiles.newComputer();
                            token = "";
                            if (!prefs.edit().putString("tls_fingerprint", qr.fingerprint)
                                    .putString("host", qr.host).putInt("port", qr.port).remove("token")
                                    .remove("local_ipv6").remove(pendingTokenKey(qr.host, qr.port)).commit()) {
                                setStatus(getString(R.string.tls_save_failed));
                                return;
                            }
                            token = "";
                            hostInput.setText(qr.host);
                            portInput.setText(String.valueOf(qr.port));
                            beginHandshake(qr.code, false);
                        } catch (Exception error) {
                            setStatus(getString(R.string.qr_invalid));
                        }
                    }).show();
        } catch (Exception error) {
            setStatus(getString(R.string.qr_invalid));
        }
    }

    private InetAddress localAddress(String host) throws IOException {
        Network network = localNetwork();
        String linkLocal = LinkLocalEndpoint.normalize(host);
        InetAddress address;
        if (!linkLocal.isEmpty()) {
            LinkProperties properties = connectivityManager.getLinkProperties(network);
            NetworkInterface localInterface = properties == null ? null : NetworkInterface.getByName(properties.getInterfaceName());
            if (localInterface == null) throw new IOException(getString(R.string.local_network_required));
            address = Inet6Address.getByAddress(null, InetAddress.getByName(linkLocal).getAddress(), localInterface);
        } else {
            address = network.getByName(host);
        }
        if (!LocalSubnet.isLocal(address)) throw new IOException(getString(R.string.local_network_required));
        return address;
    }

    @Override
    protected void onPause() {
        if (textInput != null) textInput.resetContext();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
        pairingGeneration++;
        ControlConnection pairing = pairingConnection;
        if (pairing != null) pairing.close();
        networkExecutor.shutdownNow();
        synchronized (pendingControls) {
            pendingControls.clear();
        }
        controlConnection.close();
        controlExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(isArabic() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(COLOR_SURFACE);
        LinearLayout header = row();
        header.setPadding(dp(12), dp(4), dp(8), dp(4));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Qareeb Control", 19, true);
        title.setTextDirection(View.TEXT_DIRECTION_LTR);
        title.setGravity(Gravity.START);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        brand.addView(title);
        connectionLabel = text(getString(R.string.ui_002), 13, false);
        connectionLabel.setSingleLine(true);
        connectionLabel.setGravity(Gravity.START);
        connectionLabel.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        connectionLabel.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        connectionLabel.setEllipsize(TextUtils.TruncateAt.END);
        connectionLabel.setTextColor(COLOR_MUTED);
        brand.addView(connectionLabel);
        brand.setOnClickListener(v -> showScreen(2));
        brand.setContentDescription(getString(R.string.ui_003));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(iconButton(R.drawable.ic_settings_2, getString(R.string.ui_004), v -> showSettings()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        stopButton = iconButton(R.drawable.ic_circle_stop, getString(R.string.ui_005), v -> { if (controlsStopped) controlStart(); else controlStop(); });
        stopButton.setColorFilter(COLOR_DANGER);
        header.addView(stopButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(68)));
        root.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        connectionNotice = row();
        connectionNotice.setPadding(dp(14), dp(10), dp(10), dp(10));
        connectionNotice.setBackgroundColor(COLOR_BACKGROUND);
        ImageView connectionIcon = new ImageView(this);
        connectionIcon.setImageDrawable(icon(R.drawable.ic_monitor_smartphone, COLOR_MUTED, 24));
        connectionNotice.addView(connectionIcon, new LinearLayout.LayoutParams(dp(32), dp(40)));
        LinearLayout noticeCopy = new LinearLayout(this);
        noticeCopy.setOrientation(LinearLayout.VERTICAL);
        noticeCopy.setPadding(dp(10), 0, dp(10), 0);
        connectionNoticeTitle = text(getString(R.string.ui_002), 14, true);
        noticeText = text("", 12, false);
        noticeText.setTextColor(COLOR_MUTED);
        noticeCopy.addView(connectionNoticeTitle, matchWrap());
        noticeCopy.addView(noticeText, matchWrap());
        connectionNotice.addView(noticeCopy, new LinearLayout.LayoutParams(0, -2, 1));
        Button connectionAction = button(getString(R.string.ui_008), v -> showScreen(2));
        connectionAction.setTextSize(13);
        connectionAction.setMinWidth(0);
        connectionNotice.addView(connectionAction, new LinearLayout.LayoutParams(-2, dp(48)));
        connectionNotice.setVisibility(View.GONE);
        noticeText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(connectionNotice, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(this);
        screens[0] = buildControlScreen();
        screens[1] = buildMediaScreen();
        screens[2] = buildDevicesScreen();
        for (View screen : screens) content.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        navigation = row();
        navigation.setPadding(dp(8), dp(2), dp(8), dp(2));
        String[] labels = {getString(R.string.ui_006), getString(R.string.ui_007), getString(R.string.ui_008)};
        int[] icons = {R.drawable.ic_touchpad, R.drawable.ic_music_2, R.drawable.ic_monitor_smartphone};
        for (int i = 0; i < labels.length; i++) {
            final int screen = i;
            Button tab = button(labels[i], v -> showScreen(screen));
            tab.setCompoundDrawables(null, icon(icons[i], COLOR_MUTED, 21), null, null);
            tab.setCompoundDrawablePadding(dp(2));
            tab.setPadding(0, dp(4), 0, dp(4));
            tab.setTextSize(12);
            navButtons[i] = tab;
            navigation.addView(tab, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        root.addView(navigation, new LinearLayout.LayoutParams(-1, dp(60)));
        showScreen(languageReturnScreen >= 0 ? languageReturnScreen : token == null || token.isEmpty() ? 2 : 0);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                updateKeyboardVisibility(insets.isVisible(WindowInsets.Type.ime()));
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                Rect visible = new Rect();
                root.getWindowVisibleDisplayFrame(visible);
                updateKeyboardVisibility(root.getRootView().getHeight() - visible.bottom > dp(150));
            });
        }
        return root;
    }

    private View buildControlScreen() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        TrackpadView trackpad = new TrackpadView(this);
        trackpad.setContentDescription(getString(R.string.ui_009));
        trackpad.setTag("trackpad");
        panel.addView(trackpad, new LinearLayout.LayoutParams(-1, 0, 1));
        panel.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout mouse = row();
        mouse.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        Button left = button(getString(R.string.ui_010), v -> mouseClick("left"));
        Button right = button(getString(R.string.ui_011), v -> mouseClick("right"));
        left.setCompoundDrawables(icon(R.drawable.ic_mouse, COLOR_TEXT, 18), null, null, null);
        right.setCompoundDrawables(icon(R.drawable.ic_mouse, COLOR_TEXT, 18), null, null, null);
        mouse.addView(left, new LinearLayout.LayoutParams(0, dp(48), 1));
        mouse.addView(right, new LinearLayout.LayoutParams(0, dp(48), 1));
        panel.addView(mouse);
        panel.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout tools = row();
        tools.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        tools.setPadding(dp(4), dp(2), dp(4), dp(2));
        ctrlButton = compactKey(button("Ctrl", v -> toggleModifier(true)));
        altButton = compactKey(button("Alt", v -> toggleModifier(false)));
        tools.addView(ctrlButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        tools.addView(altButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        tools.addView(compactKey(button("Tab", v -> sendToolbarKey("tab"))), new LinearLayout.LayoutParams(0, dp(46), 1));
        tools.addView(compactKey(button("Esc", v -> sendToolbarKey("esc"))), new LinearLayout.LayoutParams(0, dp(46), 1));
        tools.addView(iconButton(R.drawable.ic_corner_down_left, "Enter", v -> sendToolbarKey("enter")), new LinearLayout.LayoutParams(0, dp(46), 1));
        tools.addView(iconButton(R.drawable.ic_delete, getString(R.string.ui_012), v -> textInput.backspace()), new LinearLayout.LayoutParams(0, dp(46), 1));
        keyboardButton = iconButton(R.drawable.ic_keyboard, getString(R.string.ui_013), v -> {
            if (keyboardVisible) hideRemoteKeyboard(); else openRemoteKeyboard();
        });
        tools.addView(keyboardButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        panel.addView(tools);
        textInput = new RemoteKeyboardInput(this, new RemoteKeyboardInput.Listener() {
            @Override public boolean onEdit(LiveTextDelta edit) { return sendLiveEdit(edit); }
            @Override public void onSpecialKey(String key) { sendToolbarKey(key); }
        });
        panel.addView(textInput, new LinearLayout.LayoutParams(1, 1));
        return panel;
    }

    private View buildMediaScreen() {
        LinearLayout panel = pagePanel();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout remote = new LinearLayout(this);
        remote.setOrientation(LinearLayout.VERTICAL);
        remote.setPadding(dp(12), dp(24), dp(12), dp(20));
        FrameLayout frame = new FrameLayout(this);
        frame.addView(remote, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        frame.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(320));
            if (remote.getLayoutParams().width != width) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) remote.getLayoutParams();
                params.width = width;
                remote.setLayoutParams(params);
            }
        });
        ImageView emblem = new ImageView(this);
        emblem.setImageDrawable(icon(R.drawable.ic_music_2, COLOR_PRIMARY, 28));
        remote.addView(emblem, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView audioTitle = text(getString(R.string.ui_014), 17, true);
        audioTitle.setGravity(Gravity.CENTER);
        remote.addView(audioTitle, matchWrap());
        LinearLayout transport = row();
        transport.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        transport.setPadding(0, dp(24), 0, dp(24));
        transport.addView(remoteCircle(R.drawable.ic_skip_back, getString(R.string.ui_015), false, v -> media("previous")), remoteSize(60));
        LinearLayout.LayoutParams playSize = remoteSize(88);
        playSize.setMargins(dp(14), 0, dp(14), 0);
        transport.addView(remoteCircle(R.drawable.ic_play, getString(R.string.ui_016), true, v -> media("play_pause")), playSize);
        transport.addView(remoteCircle(R.drawable.ic_skip_forward, getString(R.string.ui_017), false, v -> media("next")), remoteSize(60));
        remote.addView(transport, matchWrap());
        LinearLayout volume = new LinearLayout(this);
        volume.setOrientation(LinearLayout.VERTICAL);
        volume.setBackground(roundedBackground(COLOR_BACKGROUND, COLOR_BORDER, 1, 8));
        volume.addView(remoteKey(R.drawable.ic_volume_2, getString(R.string.ui_018), v -> media("volume_up")), new LinearLayout.LayoutParams(-1, -2));
        volume.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        volume.addView(remoteKey(R.drawable.ic_volume_x, getString(R.string.ui_019), v -> media("mute")), new LinearLayout.LayoutParams(-1, -2));
        volume.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        volume.addView(remoteKey(R.drawable.ic_volume_1, getString(R.string.ui_020), v -> media("volume_down")), new LinearLayout.LayoutParams(-1, -2));
        remote.addView(volume, matchWrap());
        remote.addView(section(getString(R.string.ui_021)));
        LinearLayout shortcuts = row();
        shortcuts.addView(remoteKey(R.drawable.ic_copy, getString(R.string.ui_022), v -> shortcut("copy")), weight());
        shortcuts.addView(remoteKey(R.drawable.ic_clipboard_paste, getString(R.string.ui_023), v -> shortcut("paste")), weight());
        remote.addView(shortcuts, matchWrap());
        remote.addView(remoteKey(R.drawable.ic_monitor_smartphone, "Alt+Tab", v -> shortcut("alt_tab")), matchWrap());
        panel.addView(frame, new LinearLayout.LayoutParams(-1, -2));
        return scrollable(panel);
    }

    private LinearLayout.LayoutParams remoteSize(int size) {
        return new LinearLayout.LayoutParams(dp(size), dp(size));
    }

    private ImageButton remoteCircle(int image, String label, boolean primary, View.OnClickListener listener) {
        ImageButton button = iconButton(image, label, listener);
        button.setImageDrawable(icon(image, primary ? COLOR_SURFACE : COLOR_TEXT, primary ? 34 : 24));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(COLOR_BORDER),
                roundedBackground(primary ? COLOR_PRIMARY : COLOR_BACKGROUND, COLOR_BORDER, primary ? 0 : 1, 100), null));
        return button;
    }

    private Button remoteKey(int image, String label, View.OnClickListener listener) {
        Button key = outlinedButton(label, listener);
        key.setMinHeight(dp(56));
        key.setCompoundDrawablesRelative(icon(image, COLOR_TEXT, 21), null, null, null);
        key.setCompoundDrawablePadding(dp(8));
        key.setPadding(dp(14), dp(10), dp(14), dp(10));
        return key;
    }

    private View buildDevicesScreen() {
        LinearLayout panel = pagePanel();
        panel.addView(section(getString(R.string.ui_024)));
        panel.addView(section(getString(R.string.computers_title)));
        computerList = new LinearLayout(this);
        computerList.setOrientation(LinearLayout.VERTICAL);
        panel.addView(computerList, matchWrap());
        panel.addView(settingsRow(R.drawable.ic_monitor_smartphone, getString(R.string.computer_add), "", COLOR_PRIMARY,
                v -> startNewComputer()), matchWrap());
        panel.addView(settingsRow(R.drawable.ic_settings_2, getString(R.string.ui_025), languageName(), COLOR_TEXT, v -> showLanguagePicker()), matchWrap());
        panel.addView(settingsRow(R.drawable.ic_settings_2, getString(R.string.tls_fingerprint),
                prefs.getString("tls_fingerprint", "").isEmpty() ? "" : "TLS", COLOR_TEXT, v -> showTlsIdentity()), matchWrap());
        panel.addView(settingsRow(R.drawable.ic_monitor_smartphone, getString(R.string.qr_scan), "", COLOR_TEXT, v -> scanPairingQr()), matchWrap());
        connectionModeRow = settingsRow(R.drawable.ic_monitor_smartphone, getString(R.string.transport_mode),
                getString("usb".equals(prefs.getString("connection_mode", "auto")) ? R.string.transport_usb_short : R.string.transport_auto_short),
                COLOR_TEXT, v -> showConnectionMode());
        panel.addView(connectionModeRow, matchWrap());
        statusText = text("", 14, false);
        statusText.setPadding(0, dp(8), 0, dp(8));
        latencyText = text(getString(R.string.ui_026), 13, false);
        latencyText.setTextColor(COLOR_MUTED);
        panel.addView(latencyText, compactWrap());
        String savedHost = prefs.getString("host", "");
        String initialHost = savedHost.isEmpty() && isLikelyEmulator() ? EMULATOR_HOST : savedHost;
        hostInput = input(getString(R.string.ui_027), initialHost, InputType.TYPE_CLASS_TEXT);
        hostInput.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        portInput = input(getString(R.string.ui_028), String.valueOf(prefs.getInt("port", DEFAULT_PORT)), InputType.TYPE_CLASS_NUMBER);
        pairingCodeInput = input(getString(R.string.ui_029), DEFAULT_PAIRING_CODE, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        accountEmailInput = input("Account email (required)", prefs.getString("account_email", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        accountEmailInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        accountEmailInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) prefs.edit().putString("account_email", accountEmailInput.getText().toString().trim()).apply();
        });
        panel.addView(text("Account email (required for pairing)", 13, false), compactWrap());
        panel.addView(accountEmailInput, matchWrap());
        EditText cloudPasswordInput = input("Cloud password (for account sign-in)", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        cloudPasswordInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        panel.addView(cloudPasswordInput, matchWrap());
        LinearLayout cloudAuthButtons = row();
        cloudAuthButtons.addView(outlinedButton("Create cloud account", v -> cloudAuth(cloudPasswordInput, false)), weight());
        cloudAuthButtons.addView(outlinedButton("Sign in", v -> cloudAuth(cloudPasswordInput, true)), weight());
        panel.addView(cloudAuthButtons, matchWrap());
        panel.addView(text(getString(R.string.ui_030), 13, false), compactWrap());
        panel.addView(hostInput, matchWrap());
        portInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        pairingCodeInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout fields = row();
        LinearLayout portColumn = new LinearLayout(this);
        portColumn.setOrientation(LinearLayout.VERTICAL);
        portColumn.addView(text(getString(R.string.ui_028), 13, false), compactWrap());
        portColumn.addView(portInput, matchWrap());
        LinearLayout codeColumn = new LinearLayout(this);
        codeColumn.setOrientation(LinearLayout.VERTICAL);
        codeColumn.addView(text(getString(R.string.ui_029), 13, false), compactWrap());
        codeColumn.addView(pairingCodeInput, matchWrap());
        fields.addView(portColumn, weight());
        fields.addView(codeColumn, weight());
        panel.addView(fields, matchWrap());
        Button smart = button(getString(R.string.ui_031), v -> smartConnect());
        styleButton(smart, COLOR_PRIMARY, COLOR_SURFACE, true);
        smart.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_monitor_smartphone, COLOR_SURFACE, 20), null, null, null);
        panel.addView(smart, matchWrap());
        LinearLayout pairButtons = row();
        pairButtons.addView(outlinedButton(getString(R.string.ui_032), v -> pairWithCode()), weight());
        pairButtons.addView(outlinedButton(getString(R.string.ui_033), v -> reconnectWithToken()), weight());
        panel.addView(pairButtons, matchWrap());
        LinearLayout discovery = row();
        discovery.addView(outlinedButton(getString(R.string.ui_034), v -> discoverServer()), weight());
        discovery.addView(outlinedButton(getString(R.string.ui_035), v -> quickConnectionTest()), weight());
        panel.addView(discovery, matchWrap());
        if (isLikelyEmulator()) panel.addView(settingsRow(R.drawable.ic_monitor, getString(R.string.ui_036), "", COLOR_MUTED, v -> useEmulatorHost()));
        panel.addView(section(getString(R.string.ui_037)));
        panel.addView(settingsRow(R.drawable.ic_monitor_smartphone, getString(R.string.ui_038), "", COLOR_TEXT, v -> trustedDevices()));
        panel.addView(settingsRow(R.drawable.ic_circle_play, getString(R.string.ui_039), "", COLOR_TEXT, v -> controlStart()));
        panel.addView(settingsRow(R.drawable.ic_keyboard, getString(R.string.ui_040), "", COLOR_TEXT, v -> rotatePairingCode()));
        panel.addView(settingsRow(R.drawable.ic_delete, getString(R.string.ui_041), "", COLOR_TEXT, v -> confirmForget(true)));
        panel.addView(settingsRow(R.drawable.ic_delete, getString(R.string.ui_042), "", COLOR_DANGER, v -> confirmForget(false)));
        panel.addView(section(getString(R.string.ui_043)));
        LinearLayout powerRow = row();
        Button shutdownButton = outlinedButton(getString(R.string.ui_044), v -> confirmShutdownComputer());
        shutdownButton.setTextColor(COLOR_DANGER);
        powerRow.addView(shutdownButton, weight());
        powerRow.addView(outlinedButton(getString(R.string.ui_045), v -> cancelShutdown()), weight());
        panel.addView(powerRow, matchWrap());
        LinearLayout diagnosticPanel = new LinearLayout(this);
        diagnosticPanel.setOrientation(LinearLayout.VERTICAL);
        diagnosticPanel.addView(statusText, matchWrap());
        diagnosticPanel.setVisibility(View.GONE);
        panel.addView(settingsRow(R.drawable.ic_settings_2, getString(R.string.ui_046), "", COLOR_MUTED, v -> diagnosticPanel.setVisibility(diagnosticPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), matchWrap());
        LinearLayout diagnostics = row();
        diagnostics.addView(button("Ping", v -> sendSimple("ping")), weight());
        diagnostics.addView(button(getString(R.string.ui_047), v -> sendSimple("status")), weight());
        diagnosticPanel.addView(diagnostics, matchWrap());
        responseText = text("", 12, false);
        responseText.setTextColor(COLOR_MUTED);
        responseText.setTextIsSelectable(true);
        responseText.setVisibility(View.GONE);
        diagnosticPanel.addView(button(getString(R.string.ui_048), v -> responseText.setVisibility(responseText.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), matchWrap());
        diagnosticPanel.addView(responseText, matchWrap());
        panel.addView(diagnosticPanel, matchWrap());
        try {
            TextView version = text(getPackageManager().getPackageInfo(getPackageName(), 0).versionName, 12, false);
            version.setTextColor(COLOR_MUTED);
            panel.addView(version, matchWrap());
        } catch (Exception ignored) { }
        return scrollable(panel);
    }

    private View buildAuthGate() {
        LinearLayout panel = pagePanel();
        panel.addView(section("Qareeb Control"));
        panel.addView(text("Sign in or create an account before using local pairing.", 16, false), compactWrap());
        accountEmailInput = input("Email address (required)", prefs.getString("account_email", ""), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        accountEmailInput.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        panel.addView(accountEmailInput, matchWrap());
        EditText password = input("Password", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout buttons = row();
        buttons.addView(outlinedButton("Create new account", v -> cloudAuth(password, false)), weight());
        buttons.addView(outlinedButton("Sign in", v -> cloudAuth(password, true)), weight());
        panel.addView(password, matchWrap());
        panel.addView(buttons, matchWrap());
        statusText = text("", 14, false);
        panel.addView(statusText, compactWrap());
        return scrollable(panel);
    }

    private LinearLayout pagePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(4), dp(18), dp(20));
        return panel;
    }

    private String languageName() {
        String language = prefs.getString("ui_language", "en");
        return "ar".equals(language) ? "العربية" : "en".equals(language) ? "English" : getString(R.string.language_system);
    }

    private void showLanguagePicker() {
        String[] codes = {"system", "ar", "en"};
        String current = prefs.getString("ui_language", "en");
        int selected = "system".equals(current) ? 0 : "ar".equals(current) ? 1 : 2;
        new AlertDialog.Builder(this).setTitle(R.string.language_title)
                .setSingleChoiceItems(new String[]{getString(R.string.language_system), "العربية", "English"}, selected, (dialog, which) -> {
                    if (!current.equals(codes[which])) {
                        String host = hostInput.getText().toString().trim();
                        try { saveConnectionInputs(host, Integer.parseInt(portInput.getText().toString().trim())); }
                        catch (NumberFormatException ignored) { }
                        prefs.edit().putString("ui_language", codes[which]).apply();
                        getIntent().putExtra("language_return_screen", selectedScreen);
                        dialog.dismiss();
                        recreate();
                    } else dialog.dismiss();
                }).setNegativeButton(R.string.language_cancel, null).show();
    }

    private Button outlinedButton(String label, View.OnClickListener listener) {
        Button result = button(label, listener);
        result.setMinHeight(dp(48));
        result.setBackground(new RippleDrawable(ColorStateList.valueOf(COLOR_BORDER), roundedBackground(COLOR_BACKGROUND, COLOR_BORDER, 1, 8), null));
        return result;
    }

    private View settingsRow(int image, String title, String value, int color, View.OnClickListener listener) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout line = row();
        line.setMinimumHeight(dp(56));
        line.setPadding(dp(4), dp(8), dp(4), dp(8));
        ImageView symbol = new ImageView(this);
        symbol.setImageDrawable(icon(image, color, 21));
        line.addView(symbol, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView label = text(title, 14, false);
        label.setTextColor(color);
        label.setPadding(dp(10), 0, dp(10), 0);
        line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (!value.isEmpty()) {
            TextView detail = text(value, 13, false);
            detail.setTag("setting_value");
            detail.setTextColor(COLOR_MUTED);
            line.addView(detail, new LinearLayout.LayoutParams(-2, -2));
        }
        ImageView arrow = new ImageView(this);
        arrow.setImageDrawable(icon(R.drawable.ic_chevron_down, COLOR_MUTED, 16));
        arrow.setRotation(isArabic() ? 90 : -90);
        line.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(COLOR_BORDER), null, roundedBackground(Color.WHITE, 0, 0, 0)));
        line.setOnClickListener(listener);
        line.setFocusable(true);
        wrapper.addView(line, new LinearLayout.LayoutParams(-1, -2));
        wrapper.addView(divider(), new LinearLayout.LayoutParams(-1, dp(1)));
        return wrapper;
    }

    private View scrollable(LinearLayout panel) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(panel);
        return scroll;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(COLOR_BORDER);
        return view;
    }

    private Drawable icon(int resource, int color, int size) {
        Drawable drawable = getDrawable(resource).mutate();
        drawable.setTint(color);
        drawable.setBounds(0, 0, dp(size), dp(size));
        return drawable;
    }

    private ImageButton iconButton(int resource, String label, View.OnClickListener listener) {
        ImageButton button = new ImageButton(this);
        button.setImageDrawable(icon(resource, COLOR_TEXT, 22));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(COLOR_BORDER), roundedBackground(COLOR_SURFACE, 0, 0, 6), null));
        button.setOnClickListener(listener);
        return button;
    }

    private void showScreen(int index) {
        if (textInput != null) resetKeyboardContext();
        if (index != 0) hideRemoteKeyboard();
        selectedScreen = index;
        for (int i = 0; i < screens.length; i++) {
            screens[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            navButtons[i].setSelected(i == index);
            styleButton(navButtons[i], i == index ? COLOR_BACKGROUND : COLOR_SURFACE, i == index ? COLOR_PRIMARY : COLOR_MUTED, false);
            navButtons[i].setPadding(0, dp(3), 0, dp(3));
            navButtons[i].setTextSize(12);
            for (Drawable drawable : navButtons[i].getCompoundDrawables()) {
                if (drawable != null) drawable.setTint(i == index ? COLOR_PRIMARY : COLOR_MUTED);
            }
        }
        if (index == 0 && connected && !controlsStopped && prefs.getBoolean("keyboard_open", true)
                && !prefs.getBoolean("auto_keyboard_focus", true)) {
            mainHandler.postDelayed(this::openRemoteKeyboard, 150);
        }
    }

    private void updateKeyboardVisibility(boolean visible) {
        keyboardVisible = visible && selectedScreen == 0;
        navigation.setVisibility(keyboardVisible ? View.GONE : View.VISIBLE);
        keyboardButton.setBackground(roundedBackground(keyboardVisible ? COLOR_BACKGROUND : COLOR_SURFACE, 0, 0, 6));
        keyboardButton.setColorFilter(keyboardVisible ? COLOR_PRIMARY : COLOR_TEXT);
    }

    private void hideRemoteKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null && textInput != null) manager.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
    }

    private void resetKeyboardContext() {
        textInput.resetContext();
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null && textInput.hasFocus()) manager.restartInput(textInput);
    }

    private void refreshConnectionLabel() {
        connectionLabel.setText(pairingInProgress ? getString(R.string.ui_049) : !connected ? getString(R.string.ui_002) : computerName + (controlsStopped ? getString(R.string.ui_050) : getString(R.string.ui_051)));
        connectionLabel.setTextColor(!connected || controlsStopped ? COLOR_MUTED : COLOR_PRIMARY);
        stopButton.setImageDrawable(icon(controlsStopped ? R.drawable.ic_circle_play : R.drawable.ic_circle_stop, controlsStopped ? COLOR_PRIMARY : COLOR_DANGER, 22));
        stopButton.clearColorFilter();
        stopButton.setContentDescription(controlsStopped ? getString(R.string.ui_052) : getString(R.string.ui_005));
        if (Build.VERSION.SDK_INT >= 26) stopButton.setTooltipText(stopButton.getContentDescription());
        if (screens[0] != null) screens[0].findViewWithTag("trackpad").invalidate();
        refreshComputerList();
    }

    private void refreshComputerList() {
        if (computerList == null || computerProfiles == null) return;
        computerList.removeAllViews();
        try {
            java.util.List<JSONObject> entries = computerProfiles.list();
            if (entries.isEmpty()) computerList.addView(text(getString(R.string.computer_empty), 14, false), matchWrap());
            for (JSONObject entry : entries) {
                String id = entry.optString("id");
                boolean active = id.equals(computerProfiles.activeId());
                String state = getString(active && connected ? R.string.computer_connected : R.string.computer_saved);
                computerList.addView(settingsRow(R.drawable.ic_monitor, entry.optString("name"), state,
                        active && connected ? COLOR_PRIMARY : COLOR_TEXT, v -> showComputerActions(id)), matchWrap());
                TextView endpoint = text(entry.optString("host") + ":" + entry.optInt("port"), 12, false);
                endpoint.setTextColor(COLOR_MUTED);
                endpoint.setTextDirection(View.TEXT_DIRECTION_LTR);
                computerList.addView(endpoint, compactWrap());
                long last = entry.optLong("lastConnected");
                if (last > 0) {
                    TextView seen = text(getString(R.string.computer_last_connected,
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                    .format(new java.util.Date(last))), 12, false);
                    seen.setTextColor(COLOR_MUTED);
                    computerList.addView(seen, compactWrap());
                }
            }
        } catch (IOException error) {
            computerList.addView(text(getString(R.string.computer_storage_error), 14, false), matchWrap());
        }
    }

    private void showComputerActions(String id) {
        try {
            JSONObject entry = computerProfiles.find(id);
            new AlertDialog.Builder(this).setTitle(entry.optString("name"))
                    .setItems(new String[]{getString(R.string.computer_connect), getString(R.string.computer_forget)},
                            (dialog, which) -> {
                                if (which == 0) connectComputer(id);
                                else new AlertDialog.Builder(this).setTitle(R.string.computer_forget)
                                        .setMessage(getString(R.string.computer_forget_confirm, entry.optString("name")))
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .setPositiveButton(R.string.computer_forget, (confirmation, button) -> forgetComputer(id)).show();
                            }).show();
        } catch (IOException error) { setStatus(getString(R.string.computer_storage_error)); }
    }

    private void connectComputer(String id) {
        cancelRecoveryAndRequests();
        hideRemoteKeyboard();
        ctrlPressed = altPressed = false;
        updateModifiers();
        token = "";
        try {
            token = computerProfiles.select(id);
            credentialFailure = false;
            hostInput.setText(prefs.getString("host", ""));
            portInput.setText(String.valueOf(prefs.getInt("port", DEFAULT_PORT)));
            computerName = computerProfiles.find(id).optString("name");
            reconnectPolicy.reset();
            reconnectWithToken(false);
        } catch (IOException error) {
            credentialFailure = true;
            setStatus(getString(R.string.computer_storage_error));
        }
    }

    private void startNewComputer() {
        cancelRecoveryAndRequests();
        hideRemoteKeyboard();
        try {
            computerProfiles.newComputer();
            token = "";
            hostInput.setText("");
            portInput.setText(String.valueOf(DEFAULT_PORT));
            pairingCodeInput.setText(DEFAULT_PAIRING_CODE);
            computerName = getString(R.string.ui_001);
            ctrlPressed = altPressed = false;
            updateModifiers();
            setStatus(getString(R.string.computer_new_ready));
        } catch (IOException error) {
            credentialFailure = true;
            setStatus(getString(R.string.computer_storage_error));
        }
    }

    private void forgetComputer(String id) {
        boolean active = id.equals(computerProfiles.activeId());
        if (active) { cancelRecoveryAndRequests(); hideRemoteKeyboard(); }
        try {
            computerProfiles.forget(id);
            if (active) {
                token = "";
                hostInput.setText("");
                portInput.setText(String.valueOf(DEFAULT_PORT));
                computerName = getString(R.string.ui_001);
            }
            setStatus(getString(R.string.computer_forgotten));
        } catch (IOException error) { setStatus(getString(R.string.computer_storage_error)); }
    }

    private void acceptServerState(JSONObject response) {
        if (response.has("computerName")) computerName = response.optString("computerName", getString(R.string.ui_001));
        if (response.has("controlEnabled")) {
            controlsStopped = !response.optBoolean("controlEnabled", true);
            if (controlsStopped) { resetKeyboardContext(); hideRemoteKeyboard(); }
        }
        refreshConnectionLabel();
    }

    private void toggleModifier(boolean ctrl) {
        resetKeyboardContext();
        if (ctrl) ctrlPressed = !ctrlPressed; else altPressed = !altPressed;
        updateModifiers();
    }

    private void updateModifiers() {
        ctrlButton.setSelected(ctrlPressed);
        altButton.setSelected(altPressed);
        styleButton(ctrlButton, ctrlPressed ? COLOR_BACKGROUND : COLOR_SURFACE, ctrlPressed ? COLOR_PRIMARY : COLOR_TEXT, false);
        styleButton(altButton, altPressed ? COLOR_BACKGROUND : COLOR_SURFACE, altPressed ? COLOR_PRIMARY : COLOR_TEXT, false);
        compactKey(ctrlButton);
        compactKey(altButton);
    }

    private Button compactKey(Button button) {
        button.setSingleLine(true);
        button.setTextSize(13);
        button.setPadding(0, dp(4), 0, dp(4));
        return button;
    }

    private String takeModifiers() {
        String modifiers = ctrlPressed && altPressed ? "ctrl+alt" : ctrlPressed ? "ctrl" : altPressed ? "alt" : "";
        ctrlPressed = altPressed = false;
        updateModifiers();
        return modifiers;
    }

    private void sendToolbarKey(String key) {
        resetKeyboardContext();
        keyPress(key, takeModifiers(), 1, true);
    }

    private void showSettings() {
        LinearLayout panel = pagePanel();
        Switch autoKeyboard = new Switch(this);
        autoKeyboard.setText(getString(R.string.auto_keyboard_focus));
        autoKeyboard.setTextColor(COLOR_TEXT);
        autoKeyboard.setChecked(prefs.getBoolean("auto_keyboard_focus", true));
        panel.addView(autoKeyboard, matchWrap());
        Switch adaptive = new Switch(this);
        adaptive.setText(getString(R.string.adaptive_connection));
        adaptive.setTextColor(COLOR_TEXT);
        adaptive.setChecked(prefs.getBoolean("adaptive_connection", false));
        panel.addView(adaptive, matchWrap());
        panel.addView(settingsRow(R.drawable.ic_settings_2, getString(R.string.ui_025), languageName(), COLOR_TEXT, v -> showLanguagePicker()), matchWrap());
        Switch keyboard = new Switch(this);
        keyboard.setText(getString(R.string.ui_053));
        keyboard.setTextColor(COLOR_TEXT);
        keyboard.setChecked(prefs.getBoolean("keyboard_open", true));
        panel.addView(keyboard, matchWrap());
        TextView value = text(getString(R.string.ui_054) + prefs.getFloat("mouse_sensitivity", 1f), 14, false);
        panel.addView(value, matchWrap());
        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(28);
        sensitivity.setProgress(Math.round(prefs.getFloat("mouse_sensitivity", 1f) * 10) - 2);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) { value.setText(String.format(Locale.US, getString(R.string.ui_055), (progress + 2) / 10f)); }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        panel.addView(sensitivity, matchWrap());
        new AlertDialog.Builder(this).setTitle(getString(R.string.ui_004)).setView(panel).setNegativeButton(getString(R.string.ui_056), null)
                .setPositiveButton(getString(R.string.ui_057), (dialog, which) -> {
                    prefs.edit().putBoolean("keyboard_open", keyboard.isChecked()).apply();
                    prefs.edit().putBoolean("auto_keyboard_focus", autoKeyboard.isChecked()).apply();
                    lastRemoteFocus = "";
                    prefs.edit().putBoolean("adaptive_connection", adaptive.isChecked()).apply();
                    adaptiveLatency.setEnabled(adaptive.isChecked());
                    updateMouseSensitivity((sensitivity.getProgress() + 2) / 10.0);
                }).show();
    }

    private void confirmForget(boolean localOnly) {
        new AlertDialog.Builder(this).setTitle(getString(R.string.ui_058)).setMessage(getString(R.string.ui_059))
                .setNegativeButton(getString(R.string.ui_056), null).setPositiveButton(getString(R.string.ui_060), (dialog, which) -> {
                    if (localOnly) clearLocalPairing(); else forgetThisDevice();
                }).show();
    }

    private void sendSimple(String type) {
        try {
            sendCommand(new JSONObject().put("type", type), response -> {
                if ("pong".equals(response.optString("type"))) {
                    setStatus(getString(R.string.ui_061));
                } else if ("status".equals(response.optString("type"))) {
                    acceptServerState(response);
                    setStatus(getString(R.string.ui_062) + response.optString("computerName") + getString(R.string.ui_063) + response.optInt("trustedDeviceCount"));
                }
            }, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_064) + exc.getMessage());
        }
    }

    private void discoverServer() {
        setStatus(getString(R.string.ui_065));
        String existingHost = hostInput.getText().toString().trim();
        int selectedPort = parsePort();
        networkExecutor.execute(() -> {
            try {
                DiscoveryResult result = findServerByUdp(existingHost, selectedPort);
                if (result == null) {
                    mainHandler.post(() -> setStatus(getString(R.string.ui_066)));
                    result = findServerByTcpScan(existingHost, selectedPort);
                }
                DiscoveryResult finalResult = result;
                mainHandler.post(() -> publishDiscoveryResult(finalResult));
            } catch (Exception exc) {
                mainHandler.post(() -> setStatus(getString(R.string.ui_067) + exc.getMessage()));
            }
        });
    }

    private DiscoveryResult findServerByUdp(String existingHost, int port) throws Exception {
        return findServerByUdp(existingHost, port, "");
    }

    private DiscoveryResult findServerByUdp(String existingHost, int port, String trustedToken) throws Exception {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            localNetwork().bindSocket(socket);
            socket.setBroadcast(true);
            socket.setSoTimeout(1200);

            JSONObject request = new JSONObject();
            request.put("type", "phone_pc_remote_discovery");
            request.put("version", 1);
            request.put("deviceName", deviceName);
            String nonce = UUID.randomUUID().toString();
            if (!trustedToken.isEmpty()) {
                request.put("deviceId", deviceId);
                request.put("nonce", nonce);
            }
            byte[] requestBytes = request.toString().getBytes(StandardCharsets.UTF_8);

            for (String target : buildDiscoveryProbeTargets(existingHost)) {
                if (!"255.255.255.255".equals(target) && !LocalSubnet.isLocal(InetAddress.getByName(target))) continue;
                DatagramPacket packet = new DatagramPacket(requestBytes, requestBytes.length, InetAddress.getByName(target), port);
                socket.send(packet);
            }

            long deadline = System.currentTimeMillis() + 2600;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket responsePacket = new DatagramPacket(new byte[4096], 4096);
                try {
                    socket.receive(responsePacket);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }
                String responseTextValue = new String(responsePacket.getData(), responsePacket.getOffset(), responsePacket.getLength(), StandardCharsets.UTF_8);
                JSONObject response;
                try { response = new JSONObject(responseTextValue); }
                catch (Exception malformed) { continue; }
                if (!"discovery_response".equals(response.optString("type"))) {
                    continue;
                }
                if (!"phone_pc_remote".equals(response.optString("protocol"))) {
                    continue;
                }
                // The packet source is reachable on this interface; an advertised IP may not be.
                String foundHost = responsePacket.getAddress().getHostAddress();
                if (!LocalSubnet.isLocal(responsePacket.getAddress())) continue;
                int foundPort = response.optInt("port", port);
                if (foundPort < 1 || foundPort > 65535) continue;
                if (!trustedToken.isEmpty() && !DiscoveryAuthentication.verifies(trustedToken, nonce,
                        foundPort, response.optString("discoveryProof"))) continue;
                return new DiscoveryResult(response, foundHost, foundPort);
            }
            return null;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private DiscoveryResult findServerByTcpScan(String existingHost, int port) throws Exception {
        List<String> candidates = buildTcpScanCandidates(existingHost);
        if (candidates.isEmpty()) {
            return null;
        }

        AtomicReference<DiscoveryResult> found = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(candidates.size());
        ExecutorService scanner = Executors.newFixedThreadPool(DISCOVERY_SCAN_THREADS);
        for (String candidate : candidates) {
            scanner.execute(() -> {
                try {
                    if (found.get() != null) {
                        return;
                    }
                    JSONObject payload = new JSONObject().put("type", "status");
                    JSONObject response = sendTcp(candidate, port, payload, DISCOVERY_CONNECT_TIMEOUT_MS, DISCOVERY_READ_TIMEOUT_MS);
                    if ("status".equals(response.optString("type"))) {
                        found.compareAndSet(null, new DiscoveryResult(response, candidate, response.optInt("port", port)));
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(DISCOVERY_SCAN_SECONDS, TimeUnit.SECONDS);
        scanner.shutdownNow();
        return found.get();
    }

    private void publishDiscoveryResult(DiscoveryResult result) {
        if (result == null || result.host == null || result.host.isEmpty()) {
            setStatus(getString(R.string.ui_068));
            return;
        }
        hostInput.setText(result.host);
        portInput.setText(String.valueOf(result.port));
        saveConnectionInputs(result.host, result.port);
        String computer = result.response.optString("computerName", getString(R.string.ui_069));
        setStatus(getString(R.string.ui_070) + computer + getString(R.string.ui_071));
        responseText.setText(formatResponseForDisplay(result.response));
    }

    private List<String> buildDiscoveryProbeTargets(String existingHost) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add("255.255.255.255");
        if (existingHost != null && !existingHost.isEmpty()) {
            targets.add(existingHost);
        }
        if (isLikelyEmulator()) {
            targets.add(EMULATOR_HOST);
        }
        addWifiBroadcastTargets(targets);
        return new ArrayList<>(targets);
    }

    private List<String> buildTcpScanCandidates(String existingHost) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (existingHost != null && !existingHost.isEmpty() && !"0.0.0.0".equals(existingHost)) {
            candidates.add(existingHost);
        }
        if (isLikelyEmulator()) {
            candidates.add(EMULATOR_HOST);
        }
        addWifiSubnetCandidates(candidates);
        return new ArrayList<>(candidates);
    }

    private void addWifiBroadcastTargets(Set<String> targets) {
        try {
            LinkProperties properties = connectivityManager.getLinkProperties(localNetwork());
            if (properties == null) return;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (!(address.getAddress() instanceof Inet4Address)) continue;
                String broadcast = new LocalSubnet((Inet4Address) address.getAddress(), address.getPrefixLength()).broadcast();
                if (!broadcast.isEmpty()) targets.add(broadcast);
            }
        } catch (Exception ignored) {
        }
    }

    private void addWifiSubnetCandidates(LinkedHashSet<String> candidates) {
        try {
            LinkProperties properties = connectivityManager.getLinkProperties(localNetwork());
            if (properties == null) return;
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    candidates.addAll(new LocalSubnet((Inet4Address) address.getAddress(), address.getPrefixLength()).scanCandidates());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void useEmulatorHost() {
        hostInput.setText(EMULATOR_HOST);
        portInput.setText(String.valueOf(DEFAULT_PORT));
        saveConnectionInputs(EMULATOR_HOST, DEFAULT_PORT);
        setStatus(getString(R.string.ui_072));
    }

    private void quickConnectionTest() {
        if (hostInput.getText().toString().trim().isEmpty() && isLikelyEmulator()) {
            hostInput.setText(EMULATOR_HOST);
            portInput.setText(String.valueOf(DEFAULT_PORT));
        }
        String host = hostInput.getText().toString().trim();
        int port = parsePort();
        if (host.isEmpty()) {
            setStatus(getString(R.string.ui_073));
            return;
        }
        setStatus(getString(R.string.ui_074));
        networkExecutor.execute(() -> {
            try (ControlConnection probe = new ControlConnection(2000, this::createLocalSocket, this::secureLocalSocket)) {
                String ping = new JSONObject().put("type", "ping").put("keepAlive", true).toString();
                JSONObject warmup = new JSONObject(probe.exchange(host, port, ping));
                boolean persistent = warmup.optBoolean("persistentConnection");
                double total = 0;
                double maximum = 0;
                for (int i = 0; i < 10; i++) {
                    if (!persistent) probe.disconnect();
                    long start = System.nanoTime();
                    JSONObject response = new JSONObject(probe.exchange(host, port, ping));
                    if (!"pong".equals(response.optString("type"))) {
                        throw new IllegalStateException(getString(R.string.ui_075));
                    }
                    double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                    total += elapsed;
                    maximum = Math.max(maximum, elapsed);
                }
                String result = String.format(Locale.US, getString(R.string.ui_076), total / 10, maximum);
                mainHandler.post(() -> {
                    latencyText.setText(result);
                    setStatus(persistent ? getString(R.string.ui_077) : getString(R.string.ui_078));
                });
            } catch (Exception exc) {
                mainHandler.post(() -> setStatus(connectionErrorMessage(host, port, "ping", exc)));
            }
        });
    }

    private void smartConnect() {
        String host = hostInput.getText().toString().trim();
        String code = pairingCodeInput.getText().toString().trim();
        if (host.isEmpty()) {
            discoverServer();
            return;
        }
        if (!code.isEmpty()) {
            pairWithCode();
            return;
        }
        if (token != null && !token.isEmpty()) {
            reconnectWithToken(false);
            return;
        }
        quickConnectionTest();
    }

    private void pairWithCode() {
        String code = pairingCodeInput.getText().toString().trim().toUpperCase(Locale.US);
        String accountEmail = accountEmailInput == null ? prefs.getString("account_email", "") : accountEmailInput.getText().toString().trim();
        if (!accountEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            setStatus("Account email is required before pairing");
            if (accountEmailInput != null) accountEmailInput.requestFocus();
            return;
        }
        prefs.edit().putString("account_email", accountEmail).apply();
        if (code.isEmpty()) {
            if (token != null && !token.isEmpty()) {
                reconnectWithToken(false);
            } else {
                setStatus(getString(R.string.ui_079));
            }
            return;
        }
        beginHandshake(code, false);
    }

    private void cloudAuth(EditText passwordInput, boolean signIn) {
        String email = accountEmailInput == null ? "" : accountEmailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            setStatus("Enter a valid account email first");
            accountEmailInput.requestFocus();
            return;
        }
        if (password.length() < 8) {
            setStatus("Cloud password must contain at least 8 characters");
            passwordInput.requestFocus();
            return;
        }
        setStatus(signIn ? "Signing in to cloud..." : "Creating cloud account...");
        networkExecutor.execute(() -> {
            try {
                JSONObject result = CloudAuth.request(signIn ? "/api/auth/sign-in" : "/api/auth/register", email, password);
                int status = result.optInt("httpStatus", 500);
                mainHandler.post(() -> {
                    if (status >= 200 && status < 300) {
                        if (signIn && result.has("access_token")) prefs.edit().putString("cloud_access_token", result.optString("access_token")).apply();
                        if (result.optBoolean("verificationRequired", false)) {
                            setStatus("Account created. Check your email to verify it.");
                        } else {
                            setStatus(signIn ? "Cloud sign-in successful" : "Cloud account created");
                            if (signIn && result.has("access_token")) {
                                runOnUiThread(() -> setContentView(buildUi()));
                            }
                        }
                    } else {
                        String error = result.optString("error", "cloud_request_failed");
                        setStatus("Cloud error: " + error);
                    }
                });
            } catch (Exception exc) {
                mainHandler.post(() -> setStatus("Cloud connection failed: " + exc.getMessage()));
            }
        });
    }

    private void beginHandshake(String code, boolean quiet) {
        if (credentialFailure) {
            setStatus(getString(R.string.credential_storage_error));
            return;
        }
        if (!quiet) {
            recoveryGeneration++;
            mainHandler.removeCallbacks(recoveryTask);
            recoveryPending = false;
            reconnectPolicy.reset();
        }
        if (pairingInProgress) {
            if (!quiet) setStatus(getString(R.string.ui_080));
            return;
        }
        String host = hostInput.getText().toString().trim();
        int port = parsePort();
        try {
            if (!code.isEmpty() && !computerProfiles.activeId().isEmpty()) {
                JSONObject previous = computerProfiles.find(computerProfiles.activeId());
                if (!host.equals(previous.optString("host")) || port != previous.optInt("port")) {
                    String retainedPin = prefs.getString("tls_fingerprint", "");
                    computerProfiles.newComputer();
                    token = "";
                    if (!prefs.edit().putString("tls_fingerprint", retainedPin).commit()) {
                        throw new IOException("Pin not persisted");
                    }
                }
            }
        } catch (IOException error) {
            credentialFailure = true;
            setStatus(getString(R.string.computer_storage_error));
            return;
        }
        saveConnectionInputs(host, port);
        if (host.isEmpty()) {
            setStatus(getString(R.string.ui_073));
            return;
        }
        String pendingKey = pendingTokenKey(host, port);
        String candidate;
        try {
            candidate = readPendingToken(host, port);
            if (!code.isEmpty() && candidate.isEmpty()) {
                candidate = createClientToken();
                credentialVault.write(pendingKey, candidate);
            }
        } catch (IOException error) {
            credentialFailure = true;
            setStatus(getString(R.string.credential_storage_error));
            return;
        }
        PairingHandshake handshake = new PairingHandshake(token, candidate);
        final String pairingPin = prefs.getString("tls_fingerprint", "");
        AtomicReference<String> ipv6Endpoints = new AtomicReference<>("[]");
        AtomicReference<String> confirmedName = new AtomicReference<>(host);
        int generation = ++pairingGeneration;
        pairingInProgress = true;
        connected = false;
        resetKeyboardContext();
        refreshConnectionLabel();
        synchronized (pendingControls) {
            pendingControls.clear();
        }
        setStatus(getString(R.string.ui_081));
        networkExecutor.execute(() -> {
            try {
                String confirmedToken = handshake.connect((offeredToken, requestedToken) -> {
                    try (ControlConnection connection = new ControlConnection(READ_TIMEOUT_MS, this::createLocalSocket,
                            (socket, address, targetPort) -> pairingPin.isEmpty() ? socket : PinnedTls.upgrade(socket, address, targetPort, pairingPin))) {
                        pairingConnection = connection;
                        if (Thread.currentThread().isInterrupted() || generation != pairingGeneration) throw new IOException("Cancelled");
                        String target = localAddress(host).getHostAddress();
                        if (code.isEmpty() && !offeredToken.isEmpty()) {
                            String nonce = UUID.randomUUID().toString();
                            JSONObject identify = new JSONObject().put("type", "identify").put("deviceId", deviceId)
                                    .put("nonce", nonce).put("keepAlive", true);
                            JSONObject identity = new JSONObject(connection.exchange(target, port, identify.toString()));
                            if (!DiscoveryAuthentication.verifies(offeredToken, nonce, port, identity.optString("discoveryProof"))) {
                                if (!requestedToken.isEmpty() && DiscoveryAuthentication.verifies(requestedToken, nonce, port,
                                        identity.optString("discoveryProof"))) {
                                    return new PairingHandshake.Reply("error", "", "unauthorized");
                                }
                                throw new IOException(getString(R.string.server_identity_unverified));
                            }
                        }
                        JSONObject payload = baseHelloPayload();
                        payload.put("token", offeredToken);
                        // Pairing uses its own disposable socket, not the input stream.
                        payload.put("keepAlive", false);
                        payload.put("requestId", UUID.randomUUID().toString());
                        if (!code.isEmpty()) {
                            payload.put("pairingCode", code);
                            payload.put("requestedToken", requestedToken);
                        }
                        JSONObject response = new JSONObject(connection.exchange(target, port, payload.toString()));
                        String type = response.optString("type");
                        if (("paired".equals(type) || "hello_ok".equals(type))
                                && !deviceId.equals(response.optString("deviceId"))) {
                            throw new IOException("Unexpected device response");
                        }
                        if ("paired".equals(type) || "hello_ok".equals(type)) {
                            ipv6Endpoints.set(rememberedIpv6(response));
                            confirmedName.set(response.optString("computerName", host));
                        }
                        return new PairingHandshake.Reply(type, response.optString("token"), response.optString("reason"));
                    } catch (IOException exc) {
                        throw exc;
                    } catch (Exception exc) {
                        throw new IOException("Invalid pairing response", exc);
                    } finally {
                        if (generation == pairingGeneration) pairingConnection = null;
                    }
                }, () -> mainHandler.post(() -> {
                    if (generation == pairingGeneration && !isDestroyed()) {
                        setStatus(getString(R.string.ui_082));
                    }
                }));
                mainHandler.post(() -> {
                    if (generation != pairingGeneration || isDestroyed()) return;
                    pairingInProgress = false;
                    try {
                        computerProfiles.saveConfirmed(host, port, pairingPin,
                                ipv6Endpoints.get(), confirmedName.get(), confirmedToken, System.currentTimeMillis());
                        token = confirmedToken;
                        computerName = confirmedName.get();
                    } catch (IOException error) {
                        credentialFailure = true;
                        setStatus(getString(R.string.credential_storage_error));
                        return;
                    }
                    connected = true;
                    registerCloudPairing();
                    reconnectPolicy.reset();
                    prefs.edit().putString("local_ipv6", ipv6Endpoints.get()).apply();
                    prefs.edit().remove(pendingKey).apply();
                    responseText.setText("");
                    setStatus(getString(R.string.ui_083));
                    showScreen(languageReturnScreen >= 0 ? languageReturnScreen : 0);
                    languageReturnScreen = -1;
                    sendSimple("status");
                });
            } catch (Exception exc) {
                mainHandler.post(() -> {
                    if (generation != pairingGeneration || isDestroyed()) return;
                    pairingInProgress = false;
                    responseText.setText("");
                    if (exc instanceof PairingHandshake.Rejected) {
                        setStatus("unauthorized".equals(exc.getMessage())
                                ? getString(R.string.ui_084)
                                : getString(R.string.ui_085) + exc.getMessage());
                    } else {
                        setStatus(connectionErrorMessage(host, port, "hello", exc));
                        if (code.isEmpty()) rediscoverTrustedServer(host, port);
                    }
                });
            }
        });
    }

    private void registerCloudPairing() {
        String accessToken = prefs.getString("cloud_access_token", "");
        String email = accountEmailInput == null ? prefs.getString("account_email", "") : accountEmailInput.getText().toString().trim();
        if (accessToken.isEmpty() || email.isEmpty()) return;
        networkExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("deviceId", hostInput.getText().toString().trim()).put("phoneId", deviceId)
                        .put("deviceName", computerName).put("phoneName", deviceName).put("email", email).put("platform", "android");
                CloudAuth.registerPairing(accessToken, body);
            } catch (Exception ignored) { }
        });
    }

    private void rediscoverTrustedServer(String host, int port) {
        if (!foreground || token == null || token.isEmpty() || recoveryPending) return;
        final int generation = ++recoveryGeneration;
        final String savedToken = token;
        final String savedIpv6 = prefs.getString("local_ipv6", "[]");
        recoveryPending = true;
        networkExecutor.execute(() -> {
            DiscoveryResult result = null;
            try { result = findServerByCachedIpv6(savedIpv6, savedToken, port); }
            catch (Exception ignored) { }
            if (result == null) {
                try { result = findServerByUdp(host, port, savedToken); }
                catch (Exception ignored) { }
            }
            final DiscoveryResult found = result;
            mainHandler.post(() -> {
                if (isDestroyed() || !foreground || generation != recoveryGeneration) return;
                recoveryPending = false;
                if (found != null) {
                    hostInput.setText(found.host);
                    portInput.setText(String.valueOf(found.port));
                    saveConnectionInputs(found.host, found.port);
                }
                scheduleRecovery();
            });
        });
    }

    private String rememberedIpv6(JSONObject response) {
        JSONArray addresses = response.optJSONArray("localIpv6");
        JSONArray safe = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; addresses != null && i < Math.min(addresses.length(), 8); i++) {
            String address = LinkLocalEndpoint.normalize(addresses.optString(i));
            if (!address.isEmpty() && seen.add(address)) safe.put(address);
        }
        return safe.toString();
    }

    private DiscoveryResult findServerByCachedIpv6(String savedAddresses, String savedToken, int port) throws Exception {
        JSONArray addresses = new JSONArray(savedAddresses);
        for (int i = 0; i < Math.min(addresses.length(), 8); i++) {
            String host = LinkLocalEndpoint.normalize(addresses.optString(i));
            if (host.isEmpty()) continue;
            try (ControlConnection connection = new ControlConnection(1000, this::createLocalSocket, this::secureLocalSocket)) {
                String nonce = UUID.randomUUID().toString();
                JSONObject request = new JSONObject().put("type", "identify").put("deviceId", deviceId).put("nonce", nonce);
                JSONObject response = new JSONObject(connection.exchange(localAddress(host).getHostAddress(), port, request.toString()));
                if (DiscoveryAuthentication.verifies(savedToken, nonce, port, response.optString("discoveryProof"))) {
                    return new DiscoveryResult(response, host, port);
                }
            } catch (IOException ignored) { }
        }
        return null;
    }

    private String pendingTokenKey(String host, int port) {
        String id = computerProfiles.activeId();
        return "pending_token:" + (id.isEmpty() ? "new" : id) + ":" + host + ":" + port;
    }

    private String readPendingToken(String host, int port) throws IOException {
        String key = pendingTokenKey(host, port);
        String candidate = credentialVault.read(key);
        String legacyKey = "pending_token:" + host + ":" + port;
        if (candidate.isEmpty() && prefs.contains(legacyKey)) {
            candidate = credentialVault.read(legacyKey);
            credentialVault.write(key, candidate);
            if (!prefs.edit().remove(legacyKey).commit()) throw new IOException("Pending migration not persisted");
        }
        return candidate;
    }

    private String createClientToken() {
        return "phone-" + UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString();
    }

    private void reconnectWithToken() {
        reconnectWithToken(false);
    }

    private void reconnectWithToken(boolean quiet) {
        if (credentialFailure) {
            if (!quiet) setStatus(getString(R.string.credential_storage_error));
            return;
        }
        if (pairingInProgress || recoveryPending) return;
        String pendingKey = pendingTokenKey(hostInput.getText().toString().trim(), parsePort());
        String pendingToken;
        try {
            pendingToken = readPendingToken(hostInput.getText().toString().trim(), parsePort());
        } catch (IOException error) {
            credentialFailure = true;
            setStatus(getString(R.string.credential_storage_error));
            return;
        }
        if ((token == null || token.isEmpty()) && pendingToken.isEmpty()) {
            if (!quiet) {
                setStatus(getString(R.string.ui_086));
            }
            return;
        }
        String cached = prefs.getString("local_ipv6", "[]");
        if (token != null && !token.isEmpty() && !"[]".equals(cached)
                && LinkLocalEndpoint.normalize(hostInput.getText().toString().trim()).isEmpty()) {
            final int generation = ++recoveryGeneration;
            final int port = parsePort();
            final String savedToken = token;
            recoveryPending = true;
            networkExecutor.execute(() -> {
                DiscoveryResult found = null;
                try { found = findServerByCachedIpv6(cached, savedToken, port); }
                catch (Exception ignored) { }
                final DiscoveryResult result = found;
                mainHandler.post(() -> {
                    if (isDestroyed() || !foreground || generation != recoveryGeneration) return;
                    recoveryPending = false;
                    if (result != null) {
                        hostInput.setText(result.host);
                        saveConnectionInputs(result.host, result.port);
                    }
                    beginHandshake("", quiet);
                });
            });
            return;
        }
        beginHandshake("", quiet);
    }

    private JSONObject baseHelloPayload() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("type", "hello");
        payload.put("deviceId", deviceId);
        payload.put("deviceName", deviceName);
        payload.put("clientVersion", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        String accountEmail = accountEmailInput == null ? prefs.getString("account_email", "") : accountEmailInput.getText().toString().trim();
        payload.put("accountEmail", accountEmail);
        // The desktop must explicitly confirm the account link before this becomes true.
        payload.put("confirmAccountLink", prefs.getBoolean("account_link_confirmed", false));
        return payload;
    }

    private JSONObject authenticatedPayload(String type) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("type", type);
        payload.put("deviceId", deviceId);
        payload.put("token", token == null ? "" : token);
        return payload;
    }

    private void mouseMove(int dx, int dy) {
        try {
            JSONObject payload = authenticatedPayload("mouse_move");
            payload.put("dx", dx);
            payload.put("dy", dy);
            sendCommand(payload, null, true);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_087) + exc.getMessage());
        }
    }

    private void mouseClick(String button) {
        resetKeyboardContext();
        try {
            JSONObject payload = authenticatedPayload("mouse_click");
            payload.put("button", button);
            sendCommand(payload, null, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_088) + exc.getMessage());
        }
    }

    private void scroll(int amount) {
        try {
            JSONObject payload = authenticatedPayload("scroll");
            payload.put("amount", amount);
            sendCommand(payload, null, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_089) + exc.getMessage());
        }
    }

    private void openRemoteKeyboard() {
        if (selectedScreen != 0 || !connected || controlsStopped || pairingInProgress) {
            return;
        }
        if (!keyboardVisible) resetKeyboardContext();
        textInput.requestFocus();
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private boolean sendLiveEdit(LiveTextDelta edit) {
        if (!connected || controlsStopped || pairingInProgress) return false;
        try {
            if (ctrlPressed || altPressed) {
                String modifiers = takeModifiers();
                if (edit.backspaces == 0 && edit.text.length() == 1 && edit.text.charAt(0) < 128
                        && Character.isLetterOrDigit(edit.text.charAt(0))) {
                    keyPress(edit.text.toLowerCase(Locale.US), modifiers, 1, true);
                } else {
                    setStatus(getString(R.string.ui_090));
                }
                mainHandler.post(this::resetKeyboardContext);
                return false;
            }
            ArrayList<PendingControl> batch = new ArrayList<>();
            String host = hostInput.getText().toString().trim();
            int port = parsePort();
            for (int count = edit.backspaces; count > 0; count -= 5) {
                JSONObject payload = authenticatedPayload("key_press").put("key", "backspace").put("count", Math.min(5, count));
                batch.add(new PendingControl(host, port, payload, null, true, pairingGeneration));
            }
            // The server permits a minimum of 20 code points per text message.
            for (int start = 0; start < edit.text.length();) {
                int end = edit.text.offsetByCodePoints(start, Math.min(20, edit.text.codePointCount(start, edit.text.length())));
                JSONObject payload = authenticatedPayload("type_text").put("text", edit.text.substring(start, end));
                batch.add(new PendingControl(host, port, payload, null, true, pairingGeneration));
                start = end;
            }
            synchronized (pendingControls) {
                if (System.nanoTime() < controlRetryAfter || pendingControls.size() + batch.size() > 64) {
                    setStatus(getString(R.string.ui_091));
                    return false;
                }
                for (PendingControl command : batch) enqueueControl(command);
            }
            return true;
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_092) + exc.getMessage());
            return false;
        }
    }

    private void keyPress(String key, String modifiers) {
        keyPress(key, modifiers, 1, false);
    }

    private void keyPress(String key, String modifiers, int count, boolean quiet) {
        try {
            JSONObject payload = authenticatedPayload("key_press");
            payload.put("key", key);
            payload.put("modifiers", modifiers);
            payload.put("count", count);
            sendCommand(payload, null, quiet);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_093) + exc.getMessage());
        }
    }

    private void media(String control) {
        try {
            JSONObject payload = authenticatedPayload("media_control");
            payload.put("control", control);
            sendCommand(payload, null, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_094) + exc.getMessage());
        }
    }

    private void shortcut(String shortcut) {
        resetKeyboardContext();
        try {
            JSONObject payload = authenticatedPayload("shortcut");
            payload.put("shortcut", shortcut);
            sendCommand(payload, null, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_095) + exc.getMessage());
        }
    }

    private void trustedDevices() {
        try {
            sendCommand(authenticatedPayload("trusted_devices"), response -> {
                if (!"trusted_devices".equals(response.optString("type"))) return;
                org.json.JSONArray devices = response.optJSONArray("devices");
                ArrayList<String> names = new ArrayList<>();
                if (devices != null) for (int i = 0; i < devices.length(); i++) {
                    JSONObject device = devices.optJSONObject(i);
                    if (device != null) names.add(device.optString("deviceName", getString(R.string.ui_096)) + (deviceId.equals(device.optString("deviceId")) ? getString(R.string.ui_097) : ""));
                }
                new AlertDialog.Builder(this).setTitle(getString(R.string.ui_038))
                        .setItems(names.toArray(new String[0]), null).setPositiveButton(getString(R.string.ui_098), null).show();
            }, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_099) + exc.getMessage());
        }
    }

    private void updateMouseSensitivity(double mouseSensitivity) {
        try {
            JSONObject payload = authenticatedPayload("update_settings");
            payload.put("mouseSensitivity", mouseSensitivity);
            sendCommand(payload, response -> {
                if ("settings_updated".equals(response.optString("type"))) {
                    prefs.edit().putFloat("mouse_sensitivity", (float) mouseSensitivity).apply();
                    setStatus(getString(R.string.ui_100));
                }
            }, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_101) + exc.getMessage());
        }
    }

    private void rotatePairingCode() {
        try {
            sendCommand(authenticatedPayload("rotate_pairing_code"), response -> {
                String code = response.optString("pairingCode", "");
                setStatus(code.isEmpty() ? getString(R.string.ui_102) : getString(R.string.ui_103) + code);
            }, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_104) + exc.getMessage());
        }
    }

    private void forgetThisDevice() {
        try {
            JSONObject payload = authenticatedPayload("forget_device");
            payload.put("targetDeviceId", deviceId);
            sendCommand(payload, response -> {
                if (response.optBoolean("removed", false)) {
                    String id = computerProfiles.activeId();
                    if (!id.isEmpty()) { forgetComputer(id); return; }
                    token = "";
                    prefs.edit().remove("token").apply();
                    connected = false;
                    resetKeyboardContext();
                    hideRemoteKeyboard();
                    setStatus(getString(R.string.ui_105));
                }
            }, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_106) + exc.getMessage());
        }
    }

    private void clearLocalPairing() {
        if (pairingInProgress) {
            setStatus(getString(R.string.ui_107));
            return;
        }
        String id = computerProfiles.activeId();
        if (!id.isEmpty()) { forgetComputer(id); return; }
        cancelRecoveryAndRequests();
        token = "";
        connected = false;
        resetKeyboardContext();
        hideRemoteKeyboard();
        synchronized (pendingControls) { pendingControls.clear(); }
        controlConnection.disconnect();
        prefs.edit().remove("token")
                .remove("local_ipv6")
                .remove(pendingTokenKey(hostInput.getText().toString().trim(), parsePort())).apply();
        pairingCodeInput.setText(DEFAULT_PAIRING_CODE);
        setStatus(getString(R.string.ui_108));
    }

    private void confirmShutdownComputer() {
        if (token == null || token.isEmpty()) {
            setStatus(getString(R.string.ui_109));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.ui_110))
                .setMessage(getString(R.string.ui_111))
                .setNegativeButton(getString(R.string.ui_056), null)
                .setPositiveButton(getString(R.string.ui_112), (dialog, which) -> shutdownComputer())
                .show();
    }

    private void shutdownComputer() {
        try {
            JSONObject payload = authenticatedPayload("shutdown_computer");
            payload.put("confirm", "SHUTDOWN");
            payload.put("delaySeconds", 30);
            sendCommand(payload, response -> {
                int delaySeconds = response.optInt("delaySeconds", 30);
                if (response.optBoolean("dryRun", false)) {
                    setStatus(getString(R.string.ui_113));
                } else {
                    setStatus(getString(R.string.ui_114) + delaySeconds + getString(R.string.ui_115));
                }
            }, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_116) + exc.getMessage());
        }
    }

    private void cancelShutdown() {
        try {
            sendCommand(authenticatedPayload("cancel_shutdown"), response -> {
                if (response.optBoolean("dryRun", false)) {
                    setStatus(getString(R.string.ui_117));
                } else {
                    setStatus(getString(R.string.ui_118));
                }
            }, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_119) + exc.getMessage());
        }
    }

    private void controlStart() {
        try {
            sendCommand(authenticatedPayload("control_start"), response -> {
                if (!"control_started".equals(response.optString("type"))) return;
                controlsStopped = false;
                connected = true;
                resetKeyboardContext();
                refreshConnectionLabel();
                setStatus(getString(R.string.ui_120));
            }, false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_121) + exc.getMessage());
        }
    }

    private void controlStop() {
        controlsStopped = true;
        ctrlPressed = altPressed = false;
        updateModifiers();
        resetKeyboardContext();
        hideRemoteKeyboard();
        synchronized (pendingControls) { pendingControls.clear(); }
        refreshConnectionLabel();
        try {
            sendCommand(authenticatedPayload("control_stop"), response -> setStatus(getString(R.string.ui_122)), false);
        } catch (Exception exc) {
            setStatus(getString(R.string.ui_123) + exc.getMessage());
        }
    }

    private void sendCommand(JSONObject payload, ResponseHandler handler, boolean quiet) {
        sendCommand(payload, handler, quiet, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private void sendCommand(JSONObject payload, ResponseHandler handler, boolean quiet, int connectTimeoutMs, int readTimeoutMs) {
        if (pairingInProgress) {
            if (!quiet) setStatus(getString(R.string.ui_124));
            return;
        }
        int generation = pairingGeneration;
        String host = hostInput.getText().toString().trim();
        int port = parsePort();
        try {
            if (!payload.optString("token").isEmpty() && !computerProfiles.activeId().isEmpty()) {
                JSONObject selected = computerProfiles.find(computerProfiles.activeId());
                if (!host.equals(selected.optString("host")) || port != selected.optInt("port")) {
                    cancelRecoveryAndRequests();
                    setStatus(getString(R.string.computer_reconnect_required));
                    return;
                }
            }
        } catch (IOException error) {
            setStatus(getString(R.string.computer_storage_error));
            return;
        }
        saveConnectionInputs(host, port);
        if (host.isEmpty()) {
            setStatus(getString(R.string.ui_073));
            return;
        }
        String type = payload.optString("type");
        boolean inputCommand = type.equals("mouse_move") || type.equals("mouse_click") || type.equals("scroll")
                || type.equals("key_press") || type.equals("type_text") || type.equals("shortcut") || type.equals("media_control");
        if (inputCommand && (!connected || controlsStopped)) {
            if (!quiet) setStatus(controlsStopped ? getString(R.string.ui_125) : getString(R.string.ui_126));
            return;
        }
        if (type.equals("mouse_move") || type.equals("mouse_click") || type.equals("scroll")
                || type.equals("key_press") || type.equals("type_text") || type.equals("shortcut")
                || type.equals("media_control") || type.equals("control_start") || type.equals("control_stop")) {
            enqueueControl(new PendingControl(host, port, payload, handler, quiet, generation));
            return;
        }
        final String commandPin = prefs.getString("tls_fingerprint", "");
        networkExecutor.execute(() -> {
            if (generation != pairingGeneration) return;
            try {
                JSONObject response = sendTcp(host, port, payload, connectTimeoutMs, readTimeoutMs, commandPin);
                mainHandler.post(() -> {
                    if (generation != pairingGeneration || isDestroyed()) return;
                    if (!quiet) {
                        responseText.setText(formatResponseForDisplay(response));
                    }
                    String responseType = response.optString("type");
                    if ("error".equals(responseType)) {
                        setStatus(getString(R.string.ui_127) + response.optString("reason"));
                    } else if (!quiet) {
                        setStatus(getString(R.string.ui_128) + responseType);
                    }
                    if (handler != null) {
                        handler.onResponse(response);
                    }
                });
            } catch (Exception exc) {
                mainHandler.post(() -> {
                    if (generation != pairingGeneration || isDestroyed()) return;
                    if (!quiet) {
                        responseText.setText("");
                    }
                    setStatus(connectionErrorMessage(host, port, payload.optString("type"), exc));
                });
            }
        });
    }

    private void enqueueControl(PendingControl command) {
        synchronized (pendingControls) {
            if (System.nanoTime() < controlRetryAfter) return;
            PendingControl tail = pendingControls.peekLast();
            try {
                command.payload.put("keepAlive", true);
                if (tail != null && "mouse_move".equals(command.payload.optString("type"))
                        && "mouse_move".equals(tail.payload.optString("type"))
                        && tail.host.equals(command.host) && tail.port == command.port
                        && tail.payload.optString("token").equals(command.payload.optString("token"))) {
                    int dx = tail.payload.optInt("dx") + command.payload.optInt("dx");
                    int dy = tail.payload.optInt("dy") + command.payload.optInt("dy");
                    if (Math.abs(dx) <= 200 && Math.abs(dy) <= 200) {
                        tail.payload.put("dx", dx).put("dy", dy);
                        tail.queuedAt = command.queuedAt;
                        return;
                    }
                }
            } catch (Exception exc) {
                setStatus(getString(R.string.ui_129));
                return;
            }
            if (pendingControls.size() >= 64) {
                setStatus(getString(R.string.ui_130));
                return;
            }
            pendingControls.addLast(command);
            pendingControls.notifyAll();
            if (!controlWorkerRunning) {
                controlWorkerRunning = true;
                controlExecutor.execute(this::drainControls);
            }
        }
    }

    private void drainControls() {
        while (!Thread.currentThread().isInterrupted()) {
            PendingControl command;
            synchronized (pendingControls) {
                PendingControl first = pendingControls.peekFirst();
                int window = adaptiveLatency.windowMs();
                if (window > 0 && first != null
                        && "mouse_move".equals(first.payload.optString("type"))
                        && pendingControls.size() == 1) {
                    try {
                        pendingControls.wait(window);
                    } catch (InterruptedException interrupted) {
                        controlWorkerRunning = false;
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                command = pendingControls.pollFirst();
                if (command == null) {
                    controlWorkerRunning = false;
                    return;
                }
            }
            if (command.generation != pairingGeneration) continue;
            if ("mouse_move".equals(command.payload.optString("type"))
                    && System.nanoTime() - command.queuedAt > 250_000_000L) continue;
            try {
                controlTlsPin.set(command.tlsPin);
                long start = System.nanoTime();
                JSONObject response = new JSONObject(controlConnection.exchange(localAddress(command.host).getHostAddress(), command.port, command.payload.toString()));
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                if (command.generation == pairingGeneration && !"error".equals(response.optString("type"))) {
                    adaptiveLatency.record(elapsed);
                }
                boolean updateLatency = System.nanoTime() - latencyUpdatedAt > 1_000_000_000L;
                if (updateLatency) latencyUpdatedAt = System.nanoTime();
                if (updateLatency || !command.quiet || command.handler != null || "error".equals(response.optString("type"))) {
                    mainHandler.post(() -> {
                        if (isDestroyed() || command.generation != pairingGeneration) return;
                        if (updateLatency) latencyText.setText(String.format(Locale.US, getString(R.string.ui_131), elapsed));
                        if ("error".equals(response.optString("type"))) {
                            String reason = response.optString("reason");
                            resetKeyboardContext();
                            if ("unauthorized".equals(reason)) connected = false;
                            if ("control_disabled".equals(reason)) controlsStopped = true;
                            synchronized (pendingControls) { pendingControls.clear(); }
                            setStatus(getString(R.string.ui_127) + reason);
                        }
                        else if (updateLatency || !command.quiet) setStatus(getString(R.string.ui_132));
                        if (command.handler != null) command.handler.onResponse(response);
                    });
                }
            } catch (Exception exc) {
                controlConnection.disconnect();
                synchronized (pendingControls) {
                    if (command.generation == pairingGeneration) {
                        pendingControls.clear();
                        controlRetryAfter = System.nanoTime() + 1_000_000_000L;
                    }
                    controlWorkerRunning = false;
                    if (!pendingControls.isEmpty()) {
                        controlWorkerRunning = true;
                        controlExecutor.execute(this::drainControls);
                    }
                }
                mainHandler.post(() -> {
                    if (isDestroyed() || command.generation != pairingGeneration) return;
                    connected = false;
                    resetKeyboardContext();
                    hideRemoteKeyboard();
                    latencyText.setText(getString(R.string.ui_026));
                    setStatus(connectionErrorMessage(command.host, command.port, command.payload.optString("type"), exc));
                    reconnectPolicy.reset();
                    scheduleRecovery();
                });
                return;
            } finally {
                controlTlsPin.remove();
                if (command.generation != pairingGeneration) controlConnection.disconnect();
            }
        }
    }

    private String connectionErrorMessage(String host, int port, String type, Exception exc) {
        if (exc instanceof javax.net.ssl.SSLException) return getString(R.string.tls_failed);
        String action = "hello".equals(type) ? getString(R.string.ui_133) : getString(R.string.ui_134);
        if (exc instanceof ControlConnection.RequestTimeout) {
            String phase = ((ControlConnection.RequestTimeout) exc).phase;
            return "response".equals(phase)
                    ? getString(R.string.ui_135)
                    : getString(R.string.ui_136) + host + ":" + port + getString(R.string.ui_137);
        }
        if (exc instanceof SocketTimeoutException) {
            return getString(R.string.ui_138) + action + getString(R.string.ui_139) + host + ":" + port + getString(R.string.ui_140);
        }
        String detail = exc.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = exc.getClass().getSimpleName();
        }
        return getString(R.string.ui_141) + action + getString(R.string.ui_139) + host + ":" + port + " - " + detail;
    }

    private String formatResponseForDisplay(JSONObject response) {
        try {
            JSONObject display = new JSONObject(response.toString());
            if (display.has("token")) {
                display.put("token", "hidden");
            }
            return display.toString();
        } catch (Exception ignored) {
            return response.toString();
        }
    }
    private JSONObject sendTcp(String host, int port, JSONObject payload) throws Exception {
        return sendTcp(host, port, payload, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private JSONObject sendTcp(String host, int port, JSONObject payload, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return sendTcp(host, port, payload, connectTimeoutMs, readTimeoutMs, prefs.getString("tls_fingerprint", ""));
    }

    private JSONObject sendTcp(String host, int port, JSONObject payload, int connectTimeoutMs, int readTimeoutMs, String pin) throws Exception {
        Socket socket = createLocalSocket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(localAddress(host), port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            socket = pin.isEmpty() ? socket : PinnedTls.upgrade(socket, host, port, pin);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer.write(payload.toString());
            writer.write("\n");
            writer.flush();
            String line = reader.readLine();
            if (line == null || line.isEmpty()) {
                throw new IllegalStateException(getString(R.string.ui_142));
            }
            return new JSONObject(line);
        } finally {
            socket.close();
        }
    }

    private int parsePort() {
        try {
            return Integer.parseInt(portInput.getText().toString().trim());
        } catch (Exception ignored) {
            return DEFAULT_PORT;
        }
    }

    private void saveConnectionInputs(String host, int port) {
        if (!host.equals(prefs.getString("host", "")) || port != prefs.getInt("port", DEFAULT_PORT)) {
            prefs.edit().putString("host", host).putInt("port", port).apply();
        }
    }

    private String getOrCreateDeviceId() {
        String saved = prefs.getString("device_id", "");
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String id = (androidId == null || androidId.isEmpty()) ? UUID.randomUUID().toString() : androidId;
        prefs.edit().putString("device_id", id).apply();
        return id;
    }

    private String buildDeviceName() {
        String maker = safeName(Build.MANUFACTURER);
        String model = safeName(Build.MODEL);
        if (model.toLowerCase(Locale.US).startsWith(maker.toLowerCase(Locale.US))) {
            return model;
        }
        return maker + " " + model;
    }

    private boolean isLikelyEmulator() {
        String fingerprint = safeName(Build.FINGERPRINT).toLowerCase(Locale.US);
        String model = safeName(Build.MODEL).toLowerCase(Locale.US);
        String product = safeName(Build.PRODUCT).toLowerCase(Locale.US);
        String device = safeName(Build.DEVICE).toLowerCase(Locale.US);
        String brand = safeName(Build.BRAND).toLowerCase(Locale.US);
        return fingerprint.contains("generic")
                || fingerprint.contains("emulator")
                || model.contains("sdk")
                || model.contains("emulator")
                || product.contains("sdk")
                || product.contains("emulator")
                || device.contains("emulator")
                || brand.contains("generic");
    }

    private String initialStatusMessage() {
        if (token != null && !token.isEmpty()) {
            return getString(R.string.ui_143);
        }
        if (isLikelyEmulator()) {
            return getString(R.string.ui_144);
        }
        return getString(R.string.ui_145);
    }

    private String safeName(String value) {
        return value == null || value.trim().isEmpty() ? "Android" : value.trim();
    }

    private void setStatus(String message) {
        statusText.setText(message);
        boolean problem = message.startsWith("خطأ") || message.startsWith("فشل") || message.startsWith("تعذر")
                || message.startsWith("انتهت") || message.startsWith("رفض") || message.startsWith("لم يعتمد")
                || message.startsWith("تم فتح الاتصال") || message.startsWith("استخدم حرف");
        problem |= message.startsWith("Error:") || message.startsWith("Cannot ")
                || message.startsWith("Failed ") || message.startsWith("Timed out ")
                || message.startsWith("Pairing rejected:") || message.startsWith("Saved pairing was rejected")
                || message.startsWith("Request sent, but") || message.startsWith("Use an English")
                || message.contains(" error:") || message.startsWith("Search failed:");
        boolean offline = !connected && !pairingInProgress;
        connectionNoticeTitle.setText(getString(offline ? R.string.ui_002 : R.string.connection_attention));
        boolean missingNetwork = false;
        if (offline) {
            try { localNetwork(); }
            catch (IOException ignored) { missingNetwork = true; }
        }
        noticeText.setText(getString(offline
                ? (missingNetwork ? R.string.connection_no_network : R.string.connection_retry_hint)
                : R.string.connection_details_hint));
        connectionNotice.setVisibility(offline || problem ? View.VISIBLE : View.GONE);
        refreshConnectionLabel();
    }

    private TextView section(String title) {
        TextView view = text(title, 18, true);
        view.setTextColor(COLOR_PRIMARY_DARK);
        view.setPadding(0, dp(18), 0, dp(6));
        return view;
    }

    private TextView text(String content, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(sp);
        view.setTextColor(COLOR_TEXT);
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private EditText input(String hint, String value, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(value);
        editText.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        editText.setInputType(inputType);
        editText.setTextSize(16);
        editText.setTextColor(COLOR_TEXT);
        editText.setHintTextColor(COLOR_MUTED);
        editText.setBackground(roundedBackground(COLOR_SURFACE, COLOR_BORDER, 1, 6));
        editText.setPadding(dp(12), dp(10), dp(12), dp(10));
        return editText;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        styleButton(button, COLOR_SURFACE, COLOR_TEXT, false);
        button.setOnClickListener(listener);
        return button;
    }

    private void styleButton(Button button, int backgroundColor, int textColor, boolean bold) {
        button.setTextColor(textColor);
        button.setTextSize(14);
        button.setMinHeight(dp(44));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(COLOR_BORDER), roundedBackground(backgroundColor, 0, 0, 6), null));
        if (bold) {
            button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams compactWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(4));
        return params;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable roundedBackground(int fillColor, int strokeColor, int strokeWidthDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) {
            drawable.setStroke(dp(strokeWidthDp), strokeColor);
        }
        return drawable;
    }

    private class TrackpadView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable touchpadIcon = icon(R.drawable.ic_touchpad, COLOR_MUTED, 30);
        private float lastX;
        private float lastY;
        private float downX;
        private float downY;
        private float scrollLastY;
        private long downAt;
        private long lastSentAt;
        private boolean twoFingerMode;
        private boolean gestureHadTwoFingers;

        TrackpadView(Context context) {
            super(context);
            setBackgroundColor(COLOR_BACKGROUND);
            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int x = getWidth() / 2;
            int y = getHeight() / 2;
            touchpadIcon.setBounds(x - dp(15), y - dp(30), x + dp(15), y);
            touchpadIcon.draw(canvas);
            paint.setColor(COLOR_MUTED);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(14));
            canvas.drawText(controlsStopped ? getString(R.string.ui_125) : getString(R.string.ui_009), x, y + dp(25), paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    resetKeyboardContext();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    twoFingerMode = false;
                    gestureHadTwoFingers = false;
                    downX = lastX = event.getX();
                    downY = lastY = event.getY();
                    downAt = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        twoFingerMode = true;
                        gestureHadTwoFingers = true;
                        scrollLastY = averageY(event);
                        lastSentAt = 0;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() >= 2 || twoFingerMode) {
                        handleTwoFingerScroll(event);
                        return true;
                    }
                    handleMouseMove(event);
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() - 1 < 2) {
                        int fallbackIndex = event.getActionIndex() == 0 && event.getPointerCount() > 1 ? 1 : 0;
                        if (fallbackIndex < event.getPointerCount()) {
                            lastX = event.getX(fallbackIndex);
                            lastY = event.getY(fallbackIndex);
                        }
                        twoFingerMode = false;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    float totalDx = Math.abs(event.getX() - downX);
                    float totalDy = Math.abs(event.getY() - downY);
                    long duration = System.currentTimeMillis() - downAt;
                    if (!gestureHadTwoFingers && totalDx < dp(8) && totalDy < dp(8) && duration < 260) {
                        performClick();
                    }
                    twoFingerMode = false;
                    gestureHadTwoFingers = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    twoFingerMode = false;
                    gestureHadTwoFingers = false;
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            mouseClick("left");
            return true;
        }

        private void handleMouseMove(MotionEvent event) {
            long now = System.currentTimeMillis();
            if (now - lastSentAt < 24) {
                return;
            }
            int dx = Math.round(event.getX() - lastX);
            int dy = Math.round(event.getY() - lastY);
            if (Math.abs(dx) > 0 || Math.abs(dy) > 0) {
                mouseMove(dx, dy);
                lastX = event.getX();
                lastY = event.getY();
                lastSentAt = now;
            }
        }

        private void handleTwoFingerScroll(MotionEvent event) {
            long now = System.currentTimeMillis();
            if (now - lastSentAt < 48) {
                return;
            }
            float currentY = averageY(event);
            float deltaY = currentY - scrollLastY;
            if (Math.abs(deltaY) >= dp(10)) {
                int amount = Math.round(-deltaY / dp(16));
                amount = Math.max(-6, Math.min(6, amount));
                if (amount != 0) {
                    scroll(amount);
                    scrollLastY = currentY;
                    lastSentAt = now;
                }
            }
        }

        private float averageY(MotionEvent event) {
            float total = 0f;
            for (int i = 0; i < event.getPointerCount(); i++) {
                total += event.getY(i);
            }
            return total / Math.max(1, event.getPointerCount());
        }
    }
}


