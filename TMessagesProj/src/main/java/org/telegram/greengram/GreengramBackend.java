package org.telegram.greengram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Every account lives on one Greengram instance. Its profile says where that
// instance's MTProto gateway is and which RSA key to trust; native tgnet gets
// it before init, so an account never dials Telegram.
public final class GreengramBackend {

    public static final class Profile {
        public String instance; // https://chat.example.com
        public String host;     // gateway host as published by the instance
        public String ip;       // what native connects to: it only takes addresses
        public int port;
        public String publicKey;
        public long fingerprint;
    }

    public static final class Session {
        public long userId;
        public String token;
    }

    public static final class ServerException extends IOException {
        public ServerException(String message) {
            super(message);
        }
    }

    private GreengramBackend() {
    }

    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("greengram" + account, Context.MODE_PRIVATE);
    }

    public static Profile load(int account) {
        SharedPreferences p = prefs(account);
        if (!p.contains("instance")) {
            return null;
        }
        Profile profile = new Profile();
        profile.instance = p.getString("instance", "");
        profile.host = p.getString("host", "");
        profile.ip = p.getString("ip", "");
        profile.port = p.getInt("port", 0);
        profile.publicKey = p.getString("publicKey", "");
        profile.fingerprint = p.getLong("fingerprint", 0);
        return profile;
    }

    public static void save(int account, Profile profile) {
        prefs(account).edit()
                .putString("instance", profile.instance)
                .putString("host", profile.host)
                .putString("ip", profile.ip)
                .putInt("port", profile.port)
                .putString("publicKey", profile.publicKey)
                .putLong("fingerprint", profile.fingerprint)
                .apply();
    }

    public static void clear(int account) {
        prefs(account).edit().clear().apply();
    }

    // "chat.example.com" and "https://chat.example.com/" both become https://chat.example.com
    public static String normalize(String input) {
        String value = input == null ? "" : input.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        return value;
    }

    // Called from ConnectionsManager's constructor, before native init. An
    // account without an instance gets an address nothing listens on, so the
    // login screen never reaches Telegram's servers.
    public static void applyStored(int account) {
        Profile profile = load(account);
        if (profile == null || TextUtils.isEmpty(profile.ip)) {
            ConnectionsManager.native_setCustomBackend(account, "127.0.0.1", 1, "", 0, false);
            return;
        }
        ConnectionsManager.native_setCustomBackend(account, profile.ip, profile.port, profile.publicKey, profile.fingerprint, false);
        Utilities.globalQueue.postRunnable(() -> refreshAddress(account, profile));
    }

    // The gateway's IP can change under the same name; keep the auth key, move the address.
    private static void refreshAddress(int account, Profile profile) {
        try {
            String ip = resolve(profile.host);
            if (!ip.equals(profile.ip)) {
                profile.ip = ip;
                save(account, profile);
                ConnectionsManager.native_setCustomBackend(account, profile.ip, profile.port, profile.publicKey, profile.fingerprint, false);
            }
        } catch (IOException e) {
            FileLog.e(e);
        }
    }

    private static String resolve(String host) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (address.getAddress().length == 4) {
                return address.getHostAddress();
            }
        }
        return addresses[0].getHostAddress();
    }

    // Network: call off the main thread.
    public static Profile discover(String instance) throws IOException {
        String body = request("GET", instance + "/.well-known/greengram-gateway.json", null, null, null);
        try {
            JSONObject doc = new JSONObject(body);
            if (doc.optInt("version") != 1) {
                throw new ServerException("This server runs an unsupported gateway version.");
            }
            Profile profile = new Profile();
            profile.instance = instance;
            profile.host = doc.getString("host");
            profile.port = doc.getInt("port");
            profile.publicKey = doc.getString("publicKey");
            profile.fingerprint = Long.parseLong(doc.getString("fingerprint"));
            profile.ip = resolve(profile.host);
            return profile;
        } catch (JSONException | NumberFormatException e) {
            throw new ServerException("This server doesn't look like a Greengram instance.");
        }
    }

    // "+1 555 0100", "15550100" and "(555) 0100" style input; anything with a
    // letter is a username.
    public static boolean isPhone(String account) {
        return account.matches("^\\+?[0-9][0-9 ()\\-]*$");
    }

    // Network: call off the main thread. The password only ever goes to the
    // instance over HTTPS; the gateway just gets the session cookie.
    public static Session login(String instance, String account, String password) throws IOException {
        return isPhone(account) ? loginPhone(instance, account, password) : loginUsername(instance, account, password);
    }

    // Same steps as the web client: open a sign-in flow, give the number, then the password.
    private static Session loginPhone(String instance, String phone, String password) throws IOException {
        Map<String, String> cookies = new HashMap<>();
        request("POST", instance + "/api/auth/start", json("mode", "phone"), null, cookies);
        String flow = cookies.get("signin_flow");
        if (flow == null) {
            throw new ServerException("The server didn't start a sign-in.");
        }
        String number = phone.startsWith("+") ? phone : "+" + phone;
        String state = field(request("POST", instance + "/api/auth/code", json("phone", number), "signin_flow=" + flow, cookies), "state");
        if (!"password".equals(state)) {
            throw new ServerException("No account uses this number. Create one on the web first.");
        }
        String body = request("POST", instance + "/api/auth/password", json("password", password), "signin_flow=" + flow, cookies);
        try {
            return session(new JSONObject(body).getJSONObject("user").getLong("id"), cookies);
        } catch (JSONException e) {
            throw new ServerException("Unexpected answer from the server.");
        }
    }

    // Older accounts have no number; /api/login still takes their username.
    private static Session loginUsername(String instance, String username, String password) throws IOException {
        Map<String, String> cookies = new HashMap<>();
        String body = request("POST", instance + "/api/login", json("username", username.toLowerCase(), "password", password), null, cookies);
        try {
            return session(new JSONObject(body).getLong("id"), cookies);
        } catch (JSONException e) {
            throw new ServerException("Unexpected answer from the server.");
        }
    }

    private static Session session(long userId, Map<String, String> cookies) throws ServerException {
        String token = cookies.get("session");
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            throw new ServerException("The server didn't start a session.");
        }
        Session session = new Session();
        session.userId = userId;
        session.token = token;
        return session;
    }

    private static String json(String... pairs) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                payload.put(pairs[i], pairs[i + 1]);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return payload.toString();
    }

    private static String field(String body, String name) throws ServerException {
        try {
            return new JSONObject(body).optString(name, "");
        } catch (JSONException e) {
            throw new ServerException("Unexpected answer from the server.");
        }
    }

    private static String request(String method, String url, String json, String cookie, Map<String, String> cookiesOut) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (cookie != null) {
                connection.setRequestProperty("Cookie", cookie);
            }
            if (json != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            String body = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) {
                String message = null;
                try {
                    message = new JSONObject(body).optString("error", null);
                } catch (JSONException ignore) {
                }
                throw new ServerException(TextUtils.isEmpty(message) ? "The server answered " + status + "." : message);
            }
            if (cookiesOut != null) {
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() == null || !header.getKey().equalsIgnoreCase("Set-Cookie")) {
                        continue;
                    }
                    for (String value : header.getValue()) {
                        int eq = value.indexOf('=');
                        int end = value.indexOf(';');
                        if (eq > 0) {
                            cookiesOut.put(value.substring(0, eq).trim(), value.substring(eq + 1, end < 0 ? value.length() : end).trim());
                        }
                    }
                }
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > 1 << 20) {
                    throw new IOException("answer too large");
                }
            }
            return out.toString("UTF-8");
        }
    }
}
