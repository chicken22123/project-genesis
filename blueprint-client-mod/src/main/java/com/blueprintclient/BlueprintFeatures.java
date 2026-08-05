package com.blueprintclient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;

/** Shared toggle state for the Blueprint menu, plus the option changes each toggle applies. */
public final class BlueprintFeatures {
	public static boolean fullbright;
	public static boolean zoom;
	public static boolean coordinatesHud;
	public static boolean fpsHud;
	public static boolean confetti;

	private static Double savedGamma;
	private static Integer savedFov;

	private BlueprintFeatures() {
	}

	public static void toggleFullbright() {
		fullbright = !fullbright;
		SimpleOption<Double> gamma = MinecraftClient.getInstance().options.getGamma();
		if (fullbright) {
			savedGamma = gamma.getValue();
			gamma.setValue(1.0);
		} else if (savedGamma != null) {
			gamma.setValue(savedGamma);
			savedGamma = null;
		}
	}

	public static void toggleZoom() {
		zoom = !zoom;
		SimpleOption<Integer> fov = MinecraftClient.getInstance().options.getFov();
		if (zoom) {
			savedFov = fov.getValue();
			fov.setValue(30);
		} else if (savedFov != null) {
			fov.setValue(savedFov);
			savedFov = null;
		}
	}

	public static void toggleCoordinatesHud() {
		coordinatesHud = !coordinatesHud;
	}

	public static void toggleFpsHud() {
		fpsHud = !fpsHud;
	}

	public static void toggleConfetti() {
		confetti = !confetti;
	}
}
