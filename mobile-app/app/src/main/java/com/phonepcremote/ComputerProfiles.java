package com.phonepcremote;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Profile metadata and separately authenticated, encrypted per-computer credentials. */
final class ComputerProfiles {
    private final SharedPreferences prefs;
    private final CredentialVault vault;

    ComputerProfiles(SharedPreferences prefs, CredentialVault vault) {
        this.prefs = prefs;
        this.vault = vault;
    }

    List<JSONObject> list() throws IOException {
        try {
            JSONObject record = new JSONObject(prefs.getString("computer_profiles", "{\"version\":1,\"computers\":[]}"));
            if (record.getInt("version") != 1) throw new IOException("Unsupported profile version");
            JSONArray entries = record.getJSONArray("computers");
            if (entries.length() > 20) throw new IOException("Too many computers");
            ArrayList<JSONObject> result = new ArrayList<>();
            java.util.HashSet<String> ids = new java.util.HashSet<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String id = entry.getString("id");
                UUID.fromString(id);
                if (!ids.add(id) || entry.getString("host").isEmpty()
                        || entry.getInt("port") < 1 || entry.getInt("port") > 65535) {
                    throw new IOException("Invalid computer profile");
                }
                entry.getString("name");
                entry.getString("pin");
                entry.getString("ipv6");
                result.add(entry);
            }
            return result;
        } catch (Exception error) {
            throw new IOException("Computer profiles unavailable", error);
        }
    }

    String activeId() { return prefs.getString("active_computer", ""); }

    JSONObject find(String id) throws IOException {
        for (JSONObject entry : list()) if (id.equals(entry.optString("id"))) return entry;
        throw new IOException("Computer not found");
    }

    private String serialize(List<JSONObject> entries) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject entry : entries) array.put(entry);
        return new JSONObject().put("version", 1).put("computers", array).toString();
    }

    void migrateCurrent(String token) throws IOException {
        list();
        if (!activeId().isEmpty()) find(activeId());
        if (token.isEmpty() || !activeId().isEmpty() || prefs.getString("host", "").isEmpty()) return;
        saveConfirmed(prefs.getString("host", ""), prefs.getInt("port", 8765),
                prefs.getString("tls_fingerprint", ""), prefs.getString("local_ipv6", "[]"),
                prefs.getString("host", ""), token, 0);
    }

    void saveConfirmed(String host, int port, String pin, String ipv6, String name,
                       String token, long lastConnected) throws IOException {
        try {
            List<JSONObject> entries = list();
            String id = activeId();
            JSONObject current = null;
            for (JSONObject entry : entries) if (id.equals(entry.optString("id"))) current = entry;
            if (current == null) {
                if (entries.size() >= 20) throw new IOException("Computer limit reached");
                id = UUID.randomUUID().toString();
                current = new JSONObject().put("id", id);
                entries.add(current);
            }
            current.put("host", host).put("port", port).put("pin", pin).put("ipv6", ipv6)
                    .put("name", name.isEmpty() ? host : name).put("lastConnected", lastConnected);
            SharedPreferences.Editor editor = prefs.edit()
                    .putString("computer_profiles", serialize(entries)).putString("active_computer", id)
                    .putString("computer_token:" + id, vault.encode("computer_token:" + id, token))
                    .putString("token", vault.encode("token", token))
                    .putString("host", host).putInt("port", port).putString("tls_fingerprint", pin)
                    .putString("local_ipv6", ipv6);
            if (!editor.commit()) throw new IOException("Profile not persisted");
        } catch (Exception error) {
            throw new IOException("Computer profiles unavailable", error);
        }
    }

    String select(String id) throws IOException {
        JSONObject entry = find(id);
        String token = vault.read("computer_token:" + id);
        if (token.isEmpty()) throw new IOException("Computer credential missing");
        if (!prefs.edit().putString("active_computer", id).putString("token", vault.encode("token", token))
                .putString("host", entry.optString("host")).putInt("port", entry.optInt("port"))
                .putString("tls_fingerprint", entry.optString("pin"))
                .putString("local_ipv6", entry.optString("ipv6")).commit()) {
            throw new IOException("Selection not persisted");
        }
        return token;
    }

    void newComputer() throws IOException {
        SharedPreferences.Editor editor = prefs.edit().remove("active_computer").remove("token").remove("tls_fingerprint")
                .remove("local_ipv6").remove("host").remove("port");
        for (String key : prefs.getAll().keySet()) if (key.startsWith("pending_token:new:")) editor.remove(key);
        if (!editor.commit()) {
            throw new IOException("Selection not cleared");
        }
    }

    void forget(String id) throws IOException {
        try {
            List<JSONObject> entries = list();
            JSONObject forgotten = find(id);
            boolean removed = entries.removeIf(entry -> id.equals(entry.optString("id")));
            if (!removed) throw new IOException("Computer not found");
            SharedPreferences.Editor editor = prefs.edit().putString("computer_profiles", serialize(entries))
                    .remove("computer_token:" + id)
                    .remove("pending_token:" + forgotten.optString("host") + ":" + forgotten.optInt("port"));
            for (String key : prefs.getAll().keySet()) {
                if (key.startsWith("pending_token:" + id + ":")) editor.remove(key);
            }
            if (id.equals(activeId())) {
                editor.remove("active_computer").remove("token").remove("tls_fingerprint")
                        .remove("local_ipv6").remove("host").remove("port");
            }
            if (!editor.commit()) throw new IOException("Deletion not persisted");
        } catch (Exception error) {
            throw new IOException("Computer profiles unavailable", error);
        }
    }
}
