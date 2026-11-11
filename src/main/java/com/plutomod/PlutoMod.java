package com.plutomod;

import com.plutomod.config.PlutoConfig;
import com.plutomod.gui.PlutoScreen;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.plutomod.friends.FriendsClient;
import com.plutomod.hud.HudRenderer;
import com.plutomod.spotify.SpotifyClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class PlutoMod implements ClientModInitializer {
    public static final String MOD_ID = "plutomod";
    private static KeyBinding openMenuKey;
    private static KeyBinding spotifyNextKey;
    private static KeyBinding spotifyPrevKey;
    private static KeyBinding spotifyPlayKey;

    @Override
    public void onInitializeClient() {
        PlutoConfig.load();
        SpotifyClient.init();
        FriendsClient.init();

        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plutomod.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))
        ));

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        spotifyPlayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plutomod.spotify_play", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        spotifyNextKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plutomod.spotify_next", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        spotifyPrevKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.plutomod.spotify_prev", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (spotifyPlayKey.wasPressed()) SpotifyClient.togglePlay();
            while (spotifyNextKey.wasPressed()) SpotifyClient.next();
            while (spotifyPrevKey.wasPressed()) SpotifyClient.previous();
            while (openMenuKey.wasPressed()) {
                client.setScreen(new PlutoScreen());
            }
        });

        // /pm <friend> <message>
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("pm")
                        .then(ClientCommandManager.argument("friend", StringArgumentType.word())
                                .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String friend = StringArgumentType.getString(ctx, "friend");
                                            String message = StringArgumentType.getString(ctx, "message");
                                            FriendsClient.sendMessage(friend, message);
                                            return 1;
                                        })))));

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.MISC_OVERLAYS,
                Identifier.of(MOD_ID, "hud"),
                HudRenderer::render
        );
    }
}
