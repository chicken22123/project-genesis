package com.blueprintclient.module;

import net.minecraft.client.render.Camera;

import org.joml.Vector4f;

/** The colour maths behind the Obsidian Sky module, shared by its two mixins. */
public final class ObsidianSky {
	/** Stars at least this bright, so they show even at noon. */
	public static final float STAR_BRIGHTNESS = 0.75f;

	// Near-black with a trace of violet - the colour of obsidian itself.
	private static final float TINT_R = 0.05f;
	private static final float TINT_G = 0.04f;
	private static final float TINT_B = 0.08f;
	/** How far toward the tint the vanilla colour is pulled. */
	private static final float AMOUNT = 0.82f;

	private ObsidianSky() {
	}

	/** Darken a packed 0xAARRGGBB sky colour. */
	public static int darken(int argb) {
		int a = (argb >>> 24) & 0xFF;
		float r = ((argb >> 16) & 0xFF) / 255.0f;
		float g = ((argb >> 8) & 0xFF) / 255.0f;
		float b = (argb & 0xFF) / 255.0f;
		int outR = Math.round(mix(r, TINT_R) * 255.0f);
		int outG = Math.round(mix(g, TINT_G) * 255.0f);
		int outB = Math.round(mix(b, TINT_B) * 255.0f);
		return (a << 24) | (outR << 16) | (outG << 8) | outB;
	}

	/** Darken a fog colour, returning a new vector rather than editing vanilla's. */
	public static Vector4f darken(Vector4f fog) {
		return new Vector4f(mix(fog.x, TINT_R), mix(fog.y, TINT_G), mix(fog.z, TINT_B), fog.w);
	}

	/**
	 * Whether the camera is inside a fluid or powder snow.
	 *
	 * <p>Compared by name, so this does not depend on which constant names the
	 * mappings give the submersion types.
	 */
	public static boolean isSubmerged(Camera camera) {
		String type = camera.getSubmersionType().name();
		return type.equals("WATER") || type.equals("LAVA") || type.equals("POWDER_SNOW");
	}

	private static float mix(float value, float tint) {
		return value + (tint - value) * AMOUNT;
	}
}
