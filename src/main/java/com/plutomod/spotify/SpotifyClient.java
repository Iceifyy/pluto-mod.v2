package com.plutomod.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.plutomod.config.PlutoConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Spotify Web API client using the PKCE login flow (no client secret needed).
 * Reading what's playing works on any account; skip/pause/play need Spotify Premium.
 */
public final class SpotifyClient {
    private SpotifyClient() {}

    public static final String REDIRECT_URI = "http://127.0.0.1:8888/callback";
    private static final int PORT = 8888;
    private static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Path TOKEN_FILE = FabricLoader.getInstance().getConfigDir().resolve("plutomod-spotify.json");
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PlutoMod-Spotify");
        t.setDaemon(true);
        return t;
    });

    private static volatile String accessToken = "";
    private static volatile String refreshToken = "";
    private static volatile long expiresAt = 0;
    private static volatile boolean loginInProgress = false;

    public static volatile String status = "Not connected";
    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile boolean playing = false;

    private static class Tokens {
        String refresh;
        String access;
        long expiresAt;
    }

    // ---------- public API ----------

    public static void init() {
        try {
            if (Files.exists(TOKEN_FILE)) {
                Tokens t = GSON.fromJson(Files.readString(TOKEN_FILE), Tokens.class);
                if (t != null && t.refresh != null && !t.refresh.isEmpty()) {
                    refreshToken = t.refresh;
                    accessToken = t.access == null ? "" : t.access;
                    expiresAt = t.expiresAt;
                    status = "Connected";
                }
            }
        } catch (Exception ignored) {
        }
        EXEC.scheduleWithFixedDelay(SpotifyClient::poll, 3, 5, TimeUnit.SECONDS);
    }

    public static boolean isConnected() {
        return !refreshToken.isEmpty();
    }

    public static String nowPlayingText() {
        if (title.isEmpty()) return "";
        return artist.isEmpty() ? title : title + " - " + artist;
    }

    public static void connect() {
        String clientId = PlutoConfig.get().spotifyClientId.trim();
        if (clientId.isEmpty()) {
            status = "Enter your Client ID first";
            return;
        }
        if (loginInProgress) return;
        loginInProgress = true;
        status = "Waiting for you to approve in the browser...";

        EXEC.execute(() -> {
            try {
                String verifier = randomString(64);
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                String state = randomString(16);

                Map<String, String> q = new LinkedHashMap<>();
                q.put("client_id", clientId);
                q.put("response_type", "code");
                q.put("redirect_uri", REDIRECT_URI);
                q.put("scope", SCOPES);
                q.put("state", state);
                q.put("code_challenge_method", "S256");
                q.put("code_challenge", challenge);
                String url = "https://accounts.spotify.com/authorize?" + form(q);

                try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))) {
                    server.setSoTimeout(120_000);

                    MinecraftClient mc = MinecraftClient.getInstance();
                    mc.execute(() -> {
                        mc.keyboard.setClipboard(url); // fallback if the browser doesn't open
                        Util.getOperatingSystem().open(URI.create(url));
                    });

                    String code = waitForCode(server, state);
                    if (code == null) {
                        status = "Login cancelled or timed out";
                        return;
                    }

                    Map<String, String> p = new LinkedHashMap<>();
                    p.put("client_id", clientId);
                    p.put("grant_type", "authorization_code");
                    p.put("code", code);
                    p.put("redirect_uri", REDIRECT_URI);
                    p.put("code_verifier", verifier);
                    if (tokenRequest(p)) {
                        status = "Connected";
                        poll();
                    }
                }
            } catch (java.net.BindException e) {
                status = "Port 8888 is busy, close the other app using it";
            } catch (Exception e) {
                status = "Login error: " + e.getClass().getSimpleName();
            } finally {
                loginInProgress = false;
            }
        });
    }

    public static void disconnect() {
        accessToken = "";
        refreshToken = "";
        expiresAt = 0;
        title = "";
        artist = "";
        playing = false;
        status = "Not connected";
        try {
            Files.deleteIfExists(TOKEN_FILE);
        } catch (Exception ignored) {
        }
    }

    public static void next() { control("POST", "/me/player/next"); }

    public static void previous() { control("POST", "/me/player/previous"); }

    public static void togglePlay() {
        control("PUT", playing ? "/me/player/pause" : "/me/player/play");
    }

    // ---------- internals ----------

    private static void control(String method, String path) {
        if (!isConnected()) return;
        EXEC.execute(() -> {
            HttpResponse<String> r = call(method, path);
            if (r == null) return;
            int c = r.statusCode();
            if (c == 403) status = "Controls need Spotify Premium";
            else if (c == 404) status = "No active device, start Spotify somewhere";
            else if (c >= 200 && c < 300) status = "Connected";
            EXEC.schedule(SpotifyClient::poll, 500, TimeUnit.MILLISECONDS);
        });
    }

    private static void poll() {
        if (!isConnected()) return;
        try {
            HttpResponse<String> r = call("GET", "/me/player/currently-playing");
            if (r == null) return;
            if (r.statusCode() == 204) {
                title = "";
                artist = "";
                playing = false;
                return;
            }
            if (r.statusCode() != 200) return;
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            playing = o.has("is_playing") && o.get("is_playing").getAsBoolean();
            JsonElement item = o.get("item");
            if (item == null || item.isJsonNull()) {
                title = "";
                artist = "";
                return;
            }
            JsonObject track = item.getAsJsonObject();
            title = track.get("name").getAsString();
            StringBuilder names = new StringBuilder();
            if (track.has("artists")) {
                JsonArray arr = track.getAsJsonArray("artists");
                for (int i = 0; i < arr.size(); i++) {
                    if (i > 0) names.append(", ");
                    names.append(arr.get(i).getAsJsonObject().get("name").getAsString());
                }
            }
            artist = names.toString();
        } catch (Exception ignored) {
        }
    }

    private static HttpResponse<String> call(String method, String path) {
        try {
            if (!ensureToken()) return null;
            HttpResponse<String> r = send(method, path);
            if (r.statusCode() == 401 && refresh()) {
                r = send(method, path);
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + path))
                .header("Authorization", "Bearer " + accessToken);
        HttpRequest req = switch (method) {
            case "POST" -> b.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.noBody()).build();
            default -> b.GET().build();
        };
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean ensureToken() {
        if (accessToken.isEmpty() || System.currentTimeMillis() > expiresAt - 30_000) {
            return refresh();
        }
        return true;
    }

    private static boolean refresh() {
        if (refreshToken.isEmpty()) return false;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("client_id", PlutoConfig.get().spotifyClientId.trim());
        p.put("grant_type", "refresh_token");
        p.put("refresh_token", refreshToken);
        return tokenRequest(p);
    }

    private static boolean tokenRequest(Map<String, String> params) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                status = "Login failed (" + res.statusCode() + "), check your Client ID and Redirect URI";
                return false;
            }
            JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
            accessToken = o.get("access_token").getAsString();
            if (o.has("refresh_token")) refreshToken = o.get("refresh_token").getAsString();
            expiresAt = System.currentTimeMillis() + o.get("expires_in").getAsLong() * 1000L;
            saveTokens();
            return true;
        } catch (Exception e) {
            status = "Network error talking to Spotify";
            return false;
        }
    }

    private static void saveTokens() {
        try {
            Tokens t = new Tokens();
            t.refresh = refreshToken;
            t.access = accessToken;
            t.expiresAt = expiresAt;
            Files.writeString(TOKEN_FILE, GSON.toJson(t));
        } catch (Exception ignored) {
        }
    }

    /** Waits for Spotify's redirect and returns the auth code, or null on failure. */
    private static String waitForCode(ServerSocket server, String expectedState) {
        try (Socket s = server.accept()) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine(); // GET /callback?code=...&state=... HTTP/1.1
            String code = null;
            String state = null;
            boolean denied = false;
            if (line != null && line.startsWith("GET ")) {
                String target = line.split(" ")[1];
                int qi = target.indexOf('?');
                if (qi >= 0) {
                    for (String pair : target.substring(qi + 1).split("&")) {
                        String[] kv = pair.split("=", 2);
                        if (kv.length < 2) continue;
                        String v = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                        switch (kv[0]) {
                            case "code" -> code = v;
                            case "state" -> state = v;
                            case "error" -> denied = true;
                        }
                    }
                }
            }
            boolean ok = !denied && code != null && expectedState.equals(state);
            String body = "<html><body style=\"background:#000;color:#5CC8FF;font-family:sans-serif;text-align:center;padding-top:20vh\">"
                    + "<h2>" + (ok ? "Pluto Mod is connected to Spotify." : "Spotify login failed.") + "</h2>"
                    + "<p>You can close this tab and go back to Minecraft.</p></body></html>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
            return ok ? code : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String randomString(int len) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        return sb.toString();
    }
}
