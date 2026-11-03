package com.blueprintclient;

import com.blueprintclient.module.Cosmetics;
import com.blueprintclient.module.Module;
import com.blueprintclient.module.Modules;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import org.lwjgl.glfw.GLFW;

public class BlueprintClientMod implements ClientModInitializer {
	public static final String MOD_ID = "blueprintclient";
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

		registerCosmetics();
	}

	/**
	 * Hooks for the Cosmetic and Visual modules that need more than a tick:
	 * the crosshair replaces vanilla's HUD element, hit particles listen for
	 * your attacks, and the glint ships as a built-in resource pack.
	 */
	private void registerCosmetics() {
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, original -> (context, tickCounter) -> {
			if (Modules.CUSTOM_CROSSHAIR.isEnabled()) {
				Cosmetics.CustomCrosshair.render(context, MinecraftClient.getInstance());
			} else {
				original.render(context, tickCounter);
			}
		});

		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			// Fires on both sides in singleplayer; only the client draws particles.
			if (world == client.world && Modules.HIT_PARTICLES.isEnabled()) {
				Cosmetics.HitParticles.burst(client, entity);
			}
			return ActionResult.PASS;
		});

		FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container ->
				ResourceLoader.registerBuiltinPack(
						Identifier.of(MOD_ID, Cosmetics.ObsidianGlint.PACK), container,
						Text.literal("Obsidian Glint"), PackActivationType.NORMAL));
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

		int width = client.getWindow().getScaledWidth();
		int height = client.getWindow().getScaledHeight();

		// Full-screen effects first, so readouts draw on top of them.
		for (Module module : Modules.all()) {
			if (module.isEnabled()) {
				module.renderOverlay(context, client);
			}
		}

		for (Module module : Modules.hudElements()) {
			if (module.isEnabled()) {
				// Each element carries its own spot, set in the HUD editor.
				module.renderHudAt(
						context, client,
						Math.round(module.getHudX() * width),
						Math.round(module.getHudY() * height));
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
