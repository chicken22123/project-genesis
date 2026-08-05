package com.blueprintclient;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class BlueprintClientMod implements ClientModInitializer {
	private static KeyBinding menuKey;
	private int confettiCooldown;

	@Override
	public void onInitializeClient() {
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.blueprintclient.menu",
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				KeyBinding.Category.create(Identifier.of("blueprintclient", "general"))
		));

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
			if (client.currentScreen instanceof BlueprintMenuScreen) {
				client.setScreen(null);
			} else if (client.currentScreen == null) {
				client.setScreen(new BlueprintMenuScreen());
			}
		}

		if (BlueprintFeatures.confetti && client.player != null && client.world != null) {
			confettiCooldown--;
			if (confettiCooldown <= 0) {
				confettiCooldown = 4;
				var random = client.player.getRandom();
				double x = client.player.getX() + (random.nextDouble() - 0.5) * 2;
				double y = client.player.getY() + 1.5 + random.nextDouble();
				double z = client.player.getZ() + (random.nextDouble() - 0.5) * 2;
				client.particleManager.addParticle(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 0, 0.05, 0);
			}
		}
	}

	private void renderHud(net.minecraft.client.gui.DrawContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}

		int y = 6;
		if (BlueprintFeatures.fpsHud) {
			context.drawTextWithShadow(client.textRenderer, "FPS: " + client.getCurrentFps(), 6, y, 0xFF8ECBFF);
			y += 10;
		}
		if (BlueprintFeatures.coordinatesHud) {
			String coords = String.format(
					"XYZ: %.1f / %.1f / %.1f", client.player.getX(), client.player.getY(), client.player.getZ());
			context.drawTextWithShadow(client.textRenderer, coords, 6, y, 0xFF8ECBFF);
		}
	}
}
