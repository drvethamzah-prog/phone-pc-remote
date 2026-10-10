package com.phonepcremote;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts pairing credentials; preference names are authenticated with each value. */
final class CredentialVault {
    private static final String PREFIX = "enc:v1:";
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    private final String alias;

    CredentialVault(SharedPreferences preferences) {
        this(preferences, "qareeb.credentials.v1");
    }

    CredentialVault(SharedPreferences preferences, String alias) {
        this.preferences = preferences;
        this.alias = alias;
    }

    private boolean credentialName(String name) {
        return "token".equals(name) || name.startsWith("pending_token:") || name.startsWith("computer_token:");
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!create) throw new IOException("Credential key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private String encrypt(String name, String value, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new IOException("Unsupported credential IV");
        byte[] packed = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    private String decrypt(String name, String value, SecretKey key) throws Exception {
        byte[] packed = Base64.decode(value.substring(PREFIX.length()), Base64.NO_WRAP);
        if (packed.length < 28) throw new IOException("Invalid protected credential");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(packed, 12)));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
    }

    void migrateAll() throws IOException {
        synchronized (LOCK) {
            try {
                Map<String, ?> values = preferences.getAll();
                boolean encrypted = false;
                boolean legacy = false;
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    if (!credentialName(entry.getKey())) continue;
                    if (!(entry.getValue() instanceof String)) throw new IOException("Invalid credential type");
                    String value = (String) entry.getValue();
                    if (value.isEmpty()) continue;
                    if (value.startsWith(PREFIX)) encrypted = true;
                    else if (value.startsWith("enc:")) throw new IOException("Unsupported credential version");
                    else legacy = true;
                }
                if (!encrypted && !legacy) return;
                SecretKey secret = key(!encrypted);
                // Validate existing ciphertext before committing any legacy migration.
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    if (credentialName(entry.getKey()) && ((String) entry.getValue()).startsWith(PREFIX)) {
                        decrypt(entry.getKey(), (String) entry.getValue(), secret);
                    }
                }
                if (!legacy) return;
                SharedPreferences.Editor editor = preferences.edit();
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    if (!credentialName(entry.getKey())) continue;
                    String value = (String) entry.getValue();
                    if (!value.isEmpty() && !value.startsWith(PREFIX)) {
                        editor.putString(entry.getKey(), encrypt(entry.getKey(), value, secret));
                    }
                }
                if (!editor.commit()) throw new IOException("Credential migration not persisted");
            } catch (Exception error) {
                throw new IOException("Credential storage unavailable", error);
            }
        }
    }

    String read(String name) throws IOException {
        synchronized (LOCK) {
            migrateAll();
            try {
                String value = preferences.getString(name, "");
                return value.isEmpty() ? "" : decrypt(name, value, key(false));
            } catch (Exception error) {
                throw new IOException("Credential storage unavailable", error);
            }
        }
    }

    void write(String name, String value) throws IOException {
        synchronized (LOCK) {
            migrateAll();
            try {
                String encrypted = value.isEmpty() ? "" : encrypt(name, value, key(true));
                if (!preferences.edit().putString(name, encrypted).commit()) {
                    throw new IOException("Credential not persisted");
                }
            } catch (Exception error) {
                throw new IOException("Credential storage unavailable", error);
            }
        }
    }

    String encode(String name, String value) throws IOException {
        synchronized (LOCK) {
            migrateAll();
            try {
                return value.isEmpty() ? "" : encrypt(name, value, key(true));
            } catch (Exception error) {
                throw new IOException("Credential storage unavailable", error);
            }
        }
    }
}
