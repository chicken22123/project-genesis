package com.blueprintclient;

import com.blueprintclient.module.Module;
import com.blueprintclient.module.Modules;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;

public class BlueprintClientMod implements ClientModInitializer {
	private static final int HUD_COLOR = 0xFF8ECBFF;

	private static KeyBinding menuKey;

	@Override
	public void onInitializeClient() {
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.blueprintclient.menu",
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				KeyBinding.Category.create(Identifier.of("blueprintclient", "general"))
		));

		BlueprintConfig.load();

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				client.execute(() -> client.setScreen(new BlueprintWelcomeScreen(client.currentScreen))));

		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof TitleScreen && !(screen instanceof BlueprintTitleScreen)) {
				client.setScreen(new BlueprintTitleScreen());
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		HudRenderCallback.EVENT.register((context, tickCounter) -> renderHud(context));
	}

	private void onTick(MinecraftClient client) {
		while (menuKey.wasPressed()) {
			if (client.currentScreen == null) {
				client.setScreen(new BlueprintMenuScreen());
			}
		}

		Modules.tick(client);
	}

	private void renderHud(DrawContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options == null || client.options.hudHidden) {
			return;
		}

		int y = 6;
		for (Module module : Modules.all()) {
			if (!module.isEnabled()) {
				continue;
			}
			String line = module.hudLine(client);
			if (line != null) {
				context.drawTextWithShadow(client.textRenderer, line, 6, y, HUD_COLOR);
				y += 10;
			}
		}

		for (Module module : Modules.all()) {
			if (module.isEnabled()) {
				module.renderHud(context, client);
			}
		}
	}

	// ------------------------------------------------------------- menu key

	/** The GLFW code the menu is bound to right now. */
	public static int menuKeyCode() {
		return InputUtil.fromTranslationKey(menuKey.getBoundKeyTranslationKey()).getCode();
	}

	public static String menuKeyName() {
		return menuKey.getBoundKeyLocalizedText().getString();
	}

	/**
	 * Rebind the menu, from the button in the click GUI.
	 *
	 * <p>This goes through the real key binding rather than a setting of our
	 * own, so Minecraft saves it in options.txt and the vanilla Controls screen
	 * shows the same key.
	 */
	public static void setMenuKey(int keyCode) {
		menuKey.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(keyCode));
		KeyBinding.updateKeysByCode();

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options != null) {
			client.options.write();
		}
	}
}
