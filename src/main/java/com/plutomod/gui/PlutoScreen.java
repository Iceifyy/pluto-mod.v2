package com.plutomod.gui;

import com.plutomod.config.PlutoConfig;
import com.plutomod.friends.FriendsClient;
import com.plutomod.spotify.SpotifyClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.Consumer;

public class PlutoScreen extends Screen {
    private static final String[] TABS = {"HUD", "Performance", "Spotify", "Friends"};
    private static int selectedTab = 0;

    // Kept between redraws so typing isn't lost when the screen refreshes
    private static String addText = "";
    private static String msgText = "";
    private static String selectedFriend = null;

    private static final int PANEL_W = 380;
    private static final int PANEL_H = 230;
    private int left, top;
    private boolean lastSpotify;
    private int lastFriendsVersion;
    private TextFieldWidget msgField;

    public PlutoScreen() {
        super(Text.literal("Pluto Mod"));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        lastSpotify = SpotifyClient.isConnected();
        lastFriendsVersion = FriendsClient.version;

        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            String label = (i == selectedTab ? "> " : "") + TABS[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                selectedTab = index;
                clearAndInit();
            }).dimensions(left + 10 + i * 90, top + 34, 86, 20).build());
        }

        int x = left + 20;
        int y = top + 72;
        PlutoConfig cfg = PlutoConfig.get();

        switch (selectedTab) {
            case 0 -> {
                addToggle(x, y, "Show FPS", cfg.showFps, v -> cfg.showFps = v);
                addToggle(x, y + 26, "Show Ping", cfg.showPing, v -> cfg.showPing = v);
                addToggle(x, y + 52, "Show Song", cfg.showSpotifyHud, v -> cfg.showSpotifyHud = v);
                addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD Layout (move / resize)"), b -> {
                    PlutoConfig.save();
                    client.setScreen(new HudEditorScreen(this));
                }).dimensions(x, y + 78, 200, 20).build());
            }
            case 1 -> {
                addDrawableChild(new IntSlider(x, y, 200, 20, "Entity Distance", 16, 128, cfg.entityDistanceCap,
                        v -> v >= 128 ? "Off" : v + " blocks", v -> cfg.entityDistanceCap = v));
                addDrawableChild(new IntSlider(x, y + 26, 200, 20, "Particle Reduction", 0, 100, cfg.particleReduction,
                        v -> v + "%", v -> cfg.particleReduction = v));
                addToggle(x, y + 52, "Cheaper HUD", cfg.cheaperHud, v -> cfg.cheaperHud = v);
            }
            case 2 -> initSpotify(x, y, cfg);
            case 3 -> initFriends(cfg);
            default -> { }
        }
    }

    // ---------- Spotify tab ----------

    private void initSpotify(int x, int y, PlutoConfig cfg) {
        if (!lastSpotify) {
            TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, 200, 20, Text.literal("Client ID"));
            field.setMaxLength(64);
            field.setPlaceholder(Text.literal("Spotify Client ID"));
            field.setText(cfg.spotifyClientId);
            field.setChangedListener(v -> cfg.spotifyClientId = v.trim());
            addDrawableChild(field);
            addDrawableChild(ButtonWidget.builder(Text.literal("Connect Spotify"), b -> {
                PlutoConfig.save();
                SpotifyClient.connect();
            }).dimensions(x, y + 26, 200, 20).build());
        } else {
            addDrawableChild(ButtonWidget.builder(Text.literal("<<"), b -> SpotifyClient.previous())
                    .dimensions(x, y, 64, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Play/Pause"), b -> SpotifyClient.togglePlay())
                    .dimensions(x + 68, y, 64, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal(">>"), b -> SpotifyClient.next())
                    .dimensions(x + 136, y, 64, 20).build());
            addToggle(x, y + 26, "Show Song on HUD", cfg.showSpotifyHud, v -> cfg.showSpotifyHud = v);
            addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), b -> {
                SpotifyClient.disconnect();
                clearAndInit();
            }).dimensions(x, y + 52, 200, 20).build());
        }
    }

    // ---------- Friends tab ----------

    private void initFriends(PlutoConfig cfg) {
        if (!FriendsClient.isRegistered()) {
            int x = left + 20;
            int y = top + 72;
            TextFieldWidget server = new TextFieldWidget(textRenderer, x, y, 240, 20, Text.literal("Server"));
            server.setMaxLength(128);
            server.setPlaceholder(Text.literal("Friends server address, e.g. http://1.2.3.4:8080"));
            server.setText(cfg.friendsServerUrl);
            server.setChangedListener(v -> cfg.friendsServerUrl = v.trim());
            addDrawableChild(server);
            addDrawableChild(ButtonWidget.builder(Text.literal("Create Account as " + FriendsClient.minecraftName()), b -> {
                PlutoConfig.save();
                FriendsClient.register();
            }).dimensions(x, y + 26, 240, 20).build());
            return;
        }

        List<String> names = FriendsClient.friendNames();
        if (selectedFriend != null && !names.contains(selectedFriend)) selectedFriend = null;

        int lx = left + 10;
        int ty = top + 62;

        TextFieldWidget addField = new TextFieldWidget(textRenderer, lx, ty, 78, 18, Text.literal("Add"));
        addField.setMaxLength(16);
        addField.setPlaceholder(Text.literal("Add friend"));
        addField.setText(addText);
        addField.setChangedListener(v -> addText = v);
        addDrawableChild(addField);
        addDrawableChild(ButtonWidget.builder(Text.literal("Add"), b -> {
            if (!addText.isBlank()) {
                FriendsClient.sendRequest(addText.trim());
                addText = "";
                clearAndInit();
            }
        }).dimensions(lx + 82, ty, 40, 18).build());

        int ry = top + 84;
        int rows = 0;
        for (String name : FriendsClient.incoming()) {
            if (rows >= 7) break;
            addDrawableChild(ButtonWidget.builder(Text.literal("+ Accept " + name), b -> FriendsClient.accept(name))
                    .dimensions(lx, ry + rows * 18, 122, 16).build());
            rows++;
        }
        for (String name : names) {
            if (rows >= 7) break;
            String label = (FriendsClient.isOnline(name) ? "* " : "  ") + name;
            if (name.equals(selectedFriend)) label = "> " + name;
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> {
                selectedFriend = name;
                clearAndInit();
            }).dimensions(lx, ry + rows * 18, 122, 16).build());
            rows++;
        }

        if (selectedFriend != null) {
            msgField = new TextFieldWidget(textRenderer, left + 140, top + 182, 180, 18, Text.literal("Message"));
            msgField.setMaxLength(200);
            msgField.setPlaceholder(Text.literal("Message " + selectedFriend));
            msgField.setText(msgText);
            msgField.setChangedListener(v -> msgText = v);
            addDrawableChild(msgField);
            setInitialFocus(msgField);

            addDrawableChild(ButtonWidget.builder(Text.literal("Send"), b -> sendCurrentMessage())
                    .dimensions(left + 324, top + 182, 46, 18).build());
            final String friend = selectedFriend;
            addDrawableChild(ButtonWidget.builder(Text.literal("Remove"), b -> {
                FriendsClient.remove(friend);
                selectedFriend = null;
                clearAndInit();
            }).dimensions(left + 322, top + 58, 48, 16).build());
        }
    }

    private void sendCurrentMessage() {
        if (selectedFriend == null || msgText.isBlank()) return;
        FriendsClient.sendMessage(selectedFriend, msgText.trim());
        msgText = "";
        if (msgField != null) msgField.setText("");
    }

    // ---------- shared ----------

    private void addToggle(int x, int y, String name, boolean value, Consumer<Boolean> setter) {
        addDrawableChild(ButtonWidget.builder(Text.literal(name + ": " + (value ? "ON" : "OFF")), b -> {
            setter.accept(!value);
            clearAndInit();
        }).dimensions(x, y, 200, 20).build());
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xAA000000);
        ctx.fill(left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, Theme.LIGHT_BLUE);
        ctx.fill(left, top, left + PANEL_W, top + PANEL_H, Theme.PANEL);
        ctx.fill(left, top, left + PANEL_W, top + 26, Theme.PANEL_LIGHT);
        ctx.fill(left, top + 26, left + PANEL_W, top + 27, Theme.LIGHT_BLUE);

        ctx.drawText(textRenderer, Text.literal("PLUTO MOD"), left + 10, top + 9, Theme.LIGHT_BLUE, true);

        if (selectedTab == 2) {
            drawSpotifyText(ctx);
        } else if (selectedTab == 3) {
            drawFriendsText(ctx);
        }
    }

    private void drawSpotifyText(DrawContext ctx) {
        int tx = left + 20;
        if (!SpotifyClient.isConnected()) {
            ctx.drawText(textRenderer, Text.literal("Add this Redirect URI in your Spotify app:"), tx, top + 130, Theme.TEXT_DIM, false);
            ctx.drawText(textRenderer, Text.literal(SpotifyClient.REDIRECT_URI), tx, top + 142, Theme.LIGHT_BLUE, false);
            ctx.drawText(textRenderer, Text.literal(SpotifyClient.status), tx, top + 166, Theme.TEXT, false);
        } else {
            String np = SpotifyClient.nowPlayingText();
            String line = np.isEmpty() ? "Nothing playing" : textRenderer.trimToWidth(np, PANEL_W - 40);
            ctx.drawText(textRenderer, Text.literal(line), tx, top + 160, Theme.LIGHT_BLUE, false);
            ctx.drawText(textRenderer, Text.literal(SpotifyClient.status), tx, top + 176, Theme.TEXT_DIM, false);
        }
    }

    private void drawFriendsText(DrawContext ctx) {
        if (!FriendsClient.isRegistered()) {
            int tx = left + 20;
            ctx.drawText(textRenderer, Text.literal("You need a Pluto friends server (see the server folder)."), tx, top + 130, Theme.TEXT_DIM, false);
            ctx.drawText(textRenderer, Text.literal(FriendsClient.status), tx, top + 150, Theme.TEXT, false);
            return;
        }
        ctx.fill(left + 134, top + 60, left + 135, top + 178, Theme.LIGHT_BLUE_D);
        ctx.drawText(textRenderer, Text.literal(FriendsClient.status), left + 10, top + 218, Theme.TEXT_DIM, false);

        if (selectedFriend == null) {
            ctx.drawText(textRenderer, Text.literal("Pick a friend to chat."), left + 142, top + 80, Theme.TEXT_DIM, false);
            ctx.drawText(textRenderer, Text.literal("Tip: /pm <friend> <message> works in game."), left + 142, top + 94, Theme.TEXT_DIM, false);
            return;
        }
        ctx.drawText(textRenderer, Text.literal(selectedFriend), left + 142, top + 62, Theme.LIGHT_BLUE, false);
        List<String> lines = FriendsClient.chatLines(selectedFriend);
        int max = 9;
        int start = Math.max(0, lines.size() - max);
        for (int i = start; i < lines.size(); i++) {
            String l = textRenderer.trimToWidth(lines.get(i), 226);
            ctx.drawText(textRenderer, Text.literal(l), left + 142, top + 78 + (i - start) * 11, Theme.TEXT, false);
        }
    }

    @Override
    public void tick() {
        if (selectedTab == 2 && SpotifyClient.isConnected() != lastSpotify) {
            clearAndInit();
        } else if (selectedTab == 3 && FriendsClient.version != lastFriendsVersion) {
            clearAndInit();
        }
    }

    @Override
    public void removed() {
        PlutoConfig.save();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static class IntSlider extends SliderWidget {
        private final String label;
        private final int min, max;
        private final java.util.function.IntFunction<String> format;
        private final java.util.function.IntConsumer setter;

        IntSlider(int x, int y, int w, int h, String label, int min, int max, int current,
                  java.util.function.IntFunction<String> format, java.util.function.IntConsumer setter) {
            super(x, y, w, h, Text.empty(), (current - min) / (double) (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.format = format;
            this.setter = setter;
            updateMessage();
        }

        private int current() { return min + (int) Math.round(value * (max - min)); }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(label + ": " + format.apply(current())));
        }

        @Override
        protected void applyValue() {
            setter.accept(current());
        }
    }
}
