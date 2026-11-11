package com.plutomod.friends;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.plutomod.config.PlutoConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Talks to a Pluto friends server (see the server folder) to manage friends and send messages. */
public final class FriendsClient {
    private FriendsClient() {}

    public static volatile int version = 0;          // goes up whenever the data the screen shows changes
    public static volatile String status = "Not connected";

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    private static final Path SAVE_FILE = FabricLoader.getInstance().getConfigDir().resolve("plutomod-friends.json");
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PlutoMod-Friends");
        t.setDaemon(true);
        return t;
    });

    private static volatile String token = "";
    private static volatile String myName = "";
    private static volatile List<String> friends = List.of();
    private static volatile List<String> onlineFriends = List.of();
    private static volatile List<String> incoming = List.of();
    private static final Map<String, List<String>> chats = new ConcurrentHashMap<>();
    private static long sinceId = 0;
    private static boolean firstPoll = true;
    private static String lastSnapshot = "";

    private static class Saved {
        String token;
        String name;
    }

    // ---------- public API ----------

    public static void init() {
        try {
            if (Files.exists(SAVE_FILE)) {
                Saved s = GSON.fromJson(Files.readString(SAVE_FILE), Saved.class);
                if (s != null && s.token != null && !s.token.isEmpty()) {
                    token = s.token;
                    myName = s.name == null ? "" : s.name;
                    status = "Connecting...";
                }
            }
        } catch (Exception ignored) {
        }
        EXEC.scheduleWithFixedDelay(FriendsClient::poll, 2, 3, TimeUnit.SECONDS);
    }

    public static boolean isRegistered() { return !token.isEmpty(); }

    public static String myName() { return myName; }

    public static String minecraftName() {
        return MinecraftClient.getInstance().getSession().getUsername();
    }

    public static List<String> friendNames() { return friends; }

    public static boolean isOnline(String name) { return onlineFriends.contains(name); }

    public static List<String> incoming() { return incoming; }

    public static List<String> chatLines(String friend) {
        List<String> l = chats.get(friend);
        return l == null ? Collections.emptyList() : new ArrayList<>(l);
    }

    public static void register() {
        String name = minecraftName();
        EXEC.execute(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("name", name);
            JsonObject o = request("POST", "/register", body);
            if (o == null || !o.has("token")) return;
            token = o.get("token").getAsString();
            myName = name;
            save();
            status = "Connected as " + name;
            version++;
            poll();
        });
    }

    public static void logout() {
        token = "";
        myName = "";
        friends = List.of();
        onlineFriends = List.of();
        incoming = List.of();
        chats.clear();
        sinceId = 0;
        firstPoll = true;
        status = "Not connected";
        try {
            Files.deleteIfExists(SAVE_FILE);
        } catch (Exception ignored) {
        }
        version++;
    }

    public static void sendRequest(String name) { action("/friends/request", "to", name); }

    public static void accept(String name) { action("/friends/accept", "from", name); }

    public static void remove(String name) { action("/friends/remove", "name", name); }

    public static void sendMessage(String to, String text) {
        if (!isRegistered()) {
            status = "Set up Friends in the Pluto menu first";
            return;
        }
        EXEC.execute(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("to", to);
            body.addProperty("text", text);
            if (request("POST", "/message", body) != null) poll();
        });
    }

    // ---------- internals ----------

    private static void action(String path, String field, String value) {
        EXEC.execute(() -> {
            JsonObject body = new JsonObject();
            body.addProperty(field, value);
            if (request("POST", path, body) != null) poll();
        });
    }

    private static void poll() {
        if (!isRegistered()) return;
        JsonObject o = request("GET", "/state?since=" + sinceId, null);
        if (o == null) return;
        status = "Connected as " + myName;

        List<String> f = new ArrayList<>();
        List<String> on = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("friends")) {
            JsonObject fo = e.getAsJsonObject();
            String n = fo.get("name").getAsString();
            f.add(n);
            if (fo.get("online").getAsBoolean()) on.add(n);
        }
        List<String> in = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("incoming")) in.add(e.getAsString());

        boolean changed = false;
        JsonArray msgs = o.getAsJsonArray("messages");
        for (JsonElement e : msgs) {
            JsonObject m = e.getAsJsonObject();
            long id = m.get("id").getAsLong();
            String from = m.get("from").getAsString();
            String to = m.get("to").getAsString();
            String text = m.get("text").getAsString();
            boolean mine = from.equalsIgnoreCase(myName);
            String other = mine ? to : from;
            chats.computeIfAbsent(other, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add((mine ? "You" : from) + ": " + text);
            if (id > sinceId) sinceId = id;
            changed = true;
            if (!mine && !firstPoll) notifyIncoming(from, text);
        }
        firstPoll = false;

        String snapshot = f + "|" + on + "|" + in;
        if (!snapshot.equals(lastSnapshot)) {
            lastSnapshot = snapshot;
            changed = true;
        }
        friends = f;
        onlineFriends = on;
        incoming = in;
        if (changed) version++;
    }

    private static void notifyIncoming(String from, String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.inGameHud != null) {
                mc.inGameHud.getChatHud().addMessage(
                        Text.literal("[Pluto] " + from + ": " + text).formatted(Formatting.AQUA));
            }
        });
    }

    private static JsonObject request(String method, String path, JsonObject body) {
        try {
            String base = PlutoConfig.get().friendsServerUrl.trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (base.isEmpty()) {
                status = "Enter the server address first";
                return null;
            }
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json");
            if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
            HttpRequest req = method.equals("POST")
                    ? b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString())).build()
                    : b.GET().build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject o = parse(r.body());
            if (r.statusCode() >= 400) {
                status = o.has("error") ? o.get("error").getAsString() : "Server error " + r.statusCode();
                return null;
            }
            return o;
        } catch (IllegalArgumentException e) {
            status = "That server address isn't valid";
            return null;
        } catch (Exception e) {
            status = "Can't reach the friends server";
            return null;
        }
    }

    private static JsonObject parse(String s) {
        try {
            return JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static void save() {
        try {
            Saved s = new Saved();
            s.token = token;
            s.name = myName;
            Files.writeString(SAVE_FILE, GSON.toJson(s));
        } catch (Exception ignored) {
        }
    }
}
