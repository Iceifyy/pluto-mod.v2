package com.plutomod.gui;

import com.plutomod.config.PlutoConfig;
import com.plutomod.hud.HudRenderer;
import com.plutomod.hud.HudRenderer.Element;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Drag a HUD element to move it. Drag its bottom-right square (or scroll over it) to resize it.
 */
public class HudEditorScreen extends Screen {
    private static final int HANDLE = 6;
    private final Screen parent;
    private Element dragging = null;
    private Element resizing = null;
    private int grabDx, grabDy;
    private boolean wasDown = false;

    public HudEditorScreen(Screen parent) {
        super(Text.literal("HUD Layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 - 62, height - 30, 60, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> resetLayout())
                .dimensions(width / 2 + 2, height - 30, 60, 20).build());
    }

    private void resetLayout() {
        PlutoConfig c = PlutoConfig.get();
        c.fps = new PlutoConfig.HudBox(6, 6);
        c.ping = new PlutoConfig.HudBox(6, 22);
        c.spotify = new PlutoConfig.HudBox(6, 38);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x77000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        HudRenderer.renderPreview(ctx, client);

        for (Element e : Element.values()) {
            int[] b = HudRenderer.bounds(client, e);
            int color = (e == dragging || e == resizing) ? Theme.TEXT : Theme.LIGHT_BLUE;
            ctx.fill(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1], color);
            ctx.fill(b[0] - 1, b[1] + b[3], b[0] + b[2] + 1, b[1] + b[3] + 1, color);
            ctx.fill(b[0] - 1, b[1], b[0], b[1] + b[3], color);
            ctx.fill(b[0] + b[2], b[1], b[0] + b[2] + 1, b[1] + b[3], color);
            ctx.fill(b[0] + b[2] - HANDLE, b[1] + b[3] - HANDLE, b[0] + b[2] + 1, b[1] + b[3] + 1, color);
        }

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Drag to move. Drag the corner square or scroll to resize."),
                width / 2, height - 48, Theme.TEXT);

        handleMouse(mouseX, mouseY);
    }

    private void handleMouse(int mx, int my) {
        long window = client.getWindow().getHandle();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (down && !wasDown) {
            Element[] all = Element.values();
            for (int i = all.length - 1; i >= 0; i--) {
                int[] b = HudRenderer.bounds(client, all[i]);
                boolean onHandle = mx >= b[0] + b[2] - HANDLE && mx <= b[0] + b[2] + 1
                        && my >= b[1] + b[3] - HANDLE && my <= b[1] + b[3] + 1;
                boolean inside = mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3];
                if (onHandle) {
                    resizing = all[i];
                    break;
                }
                if (inside) {
                    dragging = all[i];
                    grabDx = mx - b[0];
                    grabDy = my - b[1];
                    break;
                }
            }
        }
        if (!down) {
            dragging = null;
            resizing = null;
        }

        if (dragging != null) {
            int[] b = HudRenderer.bounds(client, dragging);
            PlutoConfig.HudBox box = HudRenderer.box(dragging);
            box.x = clamp(mx - grabDx, 0, Math.max(0, width - b[2]));
            box.y = clamp(my - grabDy, 0, Math.max(0, height - b[3]));
        }
        if (resizing != null) {
            PlutoConfig.HudBox box = HudRenderer.box(resizing);
            int base = HudRenderer.baseWidth(client, resizing);
            box.scale = clampF((mx - box.x) / (float) base, 0.5f, 4.0f);
        }
        wasDown = down;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        for (Element e : Element.values()) {
            int[] b = HudRenderer.bounds(client, e);
            if (mouseX >= b[0] && mouseX <= b[0] + b[2] && mouseY >= b[1] && mouseY <= b[1] + b[3]) {
                PlutoConfig.HudBox box = HudRenderer.box(e);
                box.scale = clampF(box.scale + (float) vertical * 0.1f, 0.5f, 4.0f);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public void close() {
        PlutoConfig.save();
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
