package com.plutomod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class PlutoConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("plutomod.json");
    private static PlutoConfig instance = new PlutoConfig();

    /** Position and size of one HUD element, in scaled GUI pixels. */
    public static class HudBox {
        public int x;
        public int y;
        public float scale = 1.0f;

        public HudBox() {}

        public HudBox(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    // HUD
    public boolean showFps = true;
    public boolean showPing = true;
    public boolean showSpotifyHud = true;
    public HudBox fps = new HudBox(6, 6);
    public HudBox ping = new HudBox(6, 22);
    public HudBox spotify = new HudBox(6, 38);

    // Spotify (your own app Client ID from developer.spotify.com, not a secret)
    public String spotifyClientId = "";

    // Friends
    public String friendsServerUrl = "";

    // Performance (applied by the mod only, vanilla video settings are never touched)
    public int entityDistanceCap = 128;    // 128 = no cap, in blocks
    public int particleReduction = 0;      // percent of particles dropped
    public boolean cheaperHud = true;

    public static PlutoConfig get() { return instance; }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                try (Reader r = Files.newBufferedReader(FILE)) {
                    PlutoConfig loaded = GSON.fromJson(r, PlutoConfig.class);
                    if (loaded != null) instance = loaded;
                }
            } else {
                save();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (instance.fps == null) instance.fps = new HudBox(6, 6);
        if (instance.ping == null) instance.ping = new HudBox(6, 22);
        if (instance.spotify == null) instance.spotify = new HudBox(6, 38);
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(FILE)) {
            GSON.toJson(instance, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
