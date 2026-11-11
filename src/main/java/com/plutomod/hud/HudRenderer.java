package com.plutomod.hud;

import com.plutomod.config.PlutoConfig;
import com.plutomod.gui.Theme;
import com.plutomod.spotify.SpotifyClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderTickCounter;

public final class HudRenderer {
    private HudRenderer() {}

    public enum Element { FPS, PING, SPOTIFY }

    public static final int HEIGHT = 12;

    private static int frame = 0;
    private static String fpsText = "0";
    private static String pingText = "-";

    public static PlutoConfig.HudBox box(Element e) {
        PlutoConfig c = PlutoConfig.get();
        return switch (e) {
            case FPS -> c.fps;
            case PING -> c.ping;
            case SPOTIFY -> c.spotify;
        };
    }

    /** Normal in-game drawing. */
    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        refreshCache(mc);
        for (Element e : Element.values()) {
            drawElement(ctx, mc, e, false);
        }
    }

    /** Used by the layout editor: draws every element, even disabled ones. */
    public static void renderPreview(DrawContext ctx, MinecraftClient mc) {
        refreshCache(mc);
        for (Element e : Element.values()) {
            drawElement(ctx, mc, e, true);
        }
    }

    /** Returns {x, y, width, height} on screen, including the element's scale. */
    public static int[] bounds(MinecraftClient mc, Element e) {
        String[] c = content(e, true);
        PlutoConfig.HudBox b = box(e);
        int w = baseWidth(mc, c);
        return new int[]{b.x, b.y, Math.round(w * b.scale), Math.round(HEIGHT * b.scale)};
    }

    public static int baseWidth(MinecraftClient mc, Element e) {
        return baseWidth(mc, content(e, true));
    }

    private static int baseWidth(MinecraftClient mc, String[] c) {
        return mc.textRenderer.getWidth(c[0] + " " + c[1]) + 8;
    }

    private static void drawElement(DrawContext ctx, MinecraftClient mc, Element e, boolean preview) {
        String[] c = content(e, preview);
        if (c == null) return;
        PlutoConfig.HudBox b = box(e);
        int w = baseWidth(mc, c);

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate((float) b.x, (float) b.y);
        ctx.getMatrices().scale(b.scale, b.scale);

        ctx.fill(0, 0, w, HEIGHT, 0x99000000);
        ctx.fill(0, 0, 2, HEIGHT, Theme.LIGHT_BLUE);
        ctx.drawText(mc.textRenderer, c[0], 5, 2, Theme.LIGHT_BLUE, false);
        ctx.drawText(mc.textRenderer, c[1], 5 + mc.textRenderer.getWidth(c[0] + " "), 2, Theme.TEXT, false);

        ctx.getMatrices().popMatrix();
    }

    /** {label, value} for an element, or null if it shouldn't be shown right now. */
    private static String[] content(Element e, boolean preview) {
        PlutoConfig cfg = PlutoConfig.get();
        switch (e) {
            case FPS:
                if (!cfg.showFps && !preview) return null;
                return new String[]{"FPS", fpsText};
            case PING:
                if (!cfg.showPing && !preview) return null;
                return new String[]{"Ping", pingText};
            default:
                String song = SpotifyClient.nowPlayingText();
                if (song.isEmpty()) {
                    return preview ? new String[]{"Now", "Song Title - Artist"} : null;
                }
                if (!cfg.showSpotifyHud && !preview) return null;
                MinecraftClient mc = MinecraftClient.getInstance();
                return new String[]{SpotifyClient.playing ? "Now" : "Paused", mc.textRenderer.trimToWidth(song, 160)};
        }
    }

    private static void refreshCache(MinecraftClient mc) {
        // Cheaper HUD: refresh the text only every 10 frames instead of every frame
        if (!PlutoConfig.get().cheaperHud || frame++ % 10 == 0) {
            fpsText = String.valueOf(mc.getCurrentFps());
            pingText = readPing(mc);
        }
    }

    private static String readPing(MinecraftClient mc) {
        if (mc.player == null || mc.getNetworkHandler() == null || mc.isInSingleplayer()) return "-";
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? "-" : entry.getLatency() + "ms";
    }
}
