package com.phonepcremote;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Cloud identity only; pairing/control remains on the local network. */
public final class CloudAuth {
    public static final String BASE_URL = "https://qareeb-control-cloud.vercel.app";

    private CloudAuth() {}

    public static JSONObject request(String path, String email, String password) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        JSONObject body = new JSONObject().put("email", email).put("password", password);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        java.io.InputStream stream = connection.getResponseCode() >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        String response = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        JSONObject result = new JSONObject(response.isEmpty() ? "{}" : response);
        result.put("httpStatus", connection.getResponseCode());
        return result;
    }

    public static JSONObject registerPairing(String accessToken, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + "/api/pairing/register").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("X-Qareeb-Token", accessToken);
        connection.setDoOutput(true);
        try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        java.io.InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        JSONObject result = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        result.put("httpStatus", connection.getResponseCode());
        return result;
    }
}
