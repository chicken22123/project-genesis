package com.blueprintclient.module;

import com.blueprintclient.mixin.GameRendererAccessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.util.Objects;

/**
 * The look-and-feel modules: crosshair, particles, animations, screen effects
 * and sky. Every one of them changes only what this client draws; none of them
 * sends anything to a server or reads anything a vanilla client could not see.
 */
public final class Cosmetics {
	static final String NAMESPACE = "blueprintclient";

	private Cosmetics() {
	}

	// ------------------------------------------------------------ crosshair

	/** A crisp four-line crosshair with a centre dot, in place of vanilla's. */
	public static final class CustomCrosshair extends Module {
		private static final int GAP = 2;
		private static final int LENGTH = 4;
		private static final int LINE = 0xFFFFFFFF;
		private static final int OUTLINE = 0xA0000000;

		CustomCrosshair() {
			super("Custom Crosshair", Category.COSMETIC, "A clean four-line crosshair with a dot.");
		}

		/** Called by the crosshair HUD element in place of vanilla's drawing. */
		public static void render(DrawContext context, MinecraftClient client) {
			ClientPlayerEntity player = client.player;
			if (player == null || player.isSpectator() || !client.options.getPerspective().isFirstPerson()) {
				return;
			}
			int cx = client.getWindow().getScaledWidth() / 2;
			int cy = client.getWindow().getScaledHeight() / 2;

			// {x0, y0, x1, y1} for each arm and the dot.
			int[][] parts = {
					{cx, cy - GAP - LENGTH, cx + 1, cy - GAP},
					{cx, cy + GAP + 1, cx + 1, cy + GAP + 1 + LENGTH},
					{cx - GAP - LENGTH, cy, cx - GAP, cy + 1},
					{cx + GAP + 1, cy, cx + GAP + 1 + LENGTH, cy + 1},
					{cx, cy, cx + 1, cy + 1},
			};
			// Outline first, a pixel wider all round, so it reads on snow and sky alike.
			for (int[] p : parts) {
				context.fill(p[0] - 1, p[1] - 1, p[2] + 1, p[3] + 1, OUTLINE);
			}
			for (int[] p : parts) {
				context.fill(p[0], p[1], p[2], p[3], LINE);
			}
		}
	}

	// ------------------------------------------------------------ particles

	/** A burst of sparks on whatever you hit. */
	public static final class HitParticles extends Module {
		HitParticles() {
			super("Hit Particles", Category.COSMETIC, "A burst of sparks on every hit.");
		}

		/** Called from the attack event, on the client, when you hit an entity. */
		public static void burst(MinecraftClient client, Entity target) {
			if (client.particleManager == null) {
				return;
			}
			Random random = target.getRandom();
			double x = target.getX();
			double y = target.getBodyY(0.6);
			double z = target.getZ();
			for (int i = 0; i < 12; i++) {
				client.particleManager.addParticle(ParticleTypes.CRIT, x, y, z,
						spread(random, 0.6), spread(random, 0.4) + 0.15, spread(random, 0.6));
			}
			for (int i = 0; i < 8; i++) {
				client.particleManager.addParticle(ParticleTypes.ENCHANTED_HIT, x, y, z,
						spread(random, 0.5), spread(random, 0.4) + 0.1, spread(random, 0.5));
			}
			for (int i = 0; i < 4; i++) {
				client.particleManager.addParticle(ParticleTypes.END_ROD, x, y, z,
						spread(random, 0.12), spread(random, 0.08) + 0.04, spread(random, 0.12));
			}
		}

		private static double spread(Random random, double amount) {
			return (random.nextDouble() - 0.5) * amount;
		}
	}

	/** A ring of light that spreads out from your feet when you jump and land. */
	public static final class JumpCircles extends Module {
		private static final int POINTS = 28;
		private boolean wasOnGround = true;

		JumpCircles() {
			super("Jump Circles", Category.COSMETIC, "A ring of light when you jump and land.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			wasOnGround = client.player == null || client.player.isOnGround();
		}

		@Override
		public void tick(MinecraftClient client) {
			ClientPlayerEntity player = client.player;
			if (player == null || client.particleManager == null) {
				return;
			}
			boolean onGround = player.isOnGround();
			if (wasOnGround && !onGround && player.getVelocity().y > 0.2) {
				ring(client, player, 0.075);       // took off
			} else if (!wasOnGround && onGround) {
				ring(client, player, 0.05);        // landed: a smaller ring
			}
			wasOnGround = onGround;
		}

		private static void ring(MinecraftClient client, ClientPlayerEntity player, double speed) {
			double y = player.getY() + 0.05;
			for (int i = 0; i < POINTS; i++) {
				double angle = Math.PI * 2 * i / POINTS;
				double dx = Math.cos(angle);
				double dz = Math.sin(angle);
				client.particleManager.addParticle(ParticleTypes.END_ROD,
						player.getX() + dx * 0.35, y, player.getZ() + dz * 0.35,
						dx * speed, 0.0, dz * speed);
			}
		}
	}

	// ----------------------------------------------------------- animation

	/** A slower, smoother arm swing. The work happens in LivingEntityMixin. */
	public static final class SlowSwing extends Module {
		SlowSwing() {
			super("Slow Swing", Category.COSMETIC, "Your arm swings slower and smoother.");
		}
	}

	// ----------------------------------------------------------------- glint

	/**
	 * Base for a module that is really a built-in resource pack: switching it
	 * on enables the pack, off disables it, and Minecraft reloads its textures.
	 */
	abstract static class PackModule extends Module {
		private final String packId;

		PackModule(String name, Category category, String description, String packPath) {
			super(name, category, description);
			this.packId = NAMESPACE + ":" + packPath;
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			setPack(client, true);
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			setPack(client, false);
		}

		private void setPack(MinecraftClient client, boolean on) {
			if (client.options == null) {
				return;
			}
			ResourcePackManager manager = client.getResourcePackManager();
			boolean changed = on ? manager.enable(packId) : manager.disable(packId);
			if (changed) {
				// Saves the pack list to options.txt and reloads resources if it moved.
				client.options.refreshResourcePacks(manager);
			}
		}
	}

	/** Silver enchantment glint instead of purple. */
	public static final class ObsidianGlint extends PackModule {
		public static final String PACK = "obsidian_glint";

		ObsidianGlint() {
			super("Obsidian Glint", Category.COSMETIC, "Silver enchantment glint. Reloads textures.", PACK);
		}
	}

	// ------------------------------------------------------------------ fov

	/** Field of view out to the widest vanilla allows. */
	public static final class WideFov extends Module {
		private static final int FOV = 110;
		private Integer saved;

		WideFov() {
			super("Wide FOV", Category.VISUAL, "Field of view at " + FOV + ", the widest there is.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.options == null) {
				return;
			}
			SimpleOption<Integer> fov = client.options.getFov();
			saved = fov.getValue();
			fov.setValue(FOV);
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			if (client.options != null && saved != null) {
				client.options.getFov().setValue(saved);
			}
			saved = null;
		}
	}

	/** No zoom-in when sprinting or zoom-out under Speed. */
	public static final class StaticFov extends Module {
		private Double saved;

		StaticFov() {
			super("Static FOV", Category.VISUAL, "FOV stays put when you sprint or get Speed.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.options == null) {
				return;
			}
			SimpleOption<Double> effects = client.options.getFovEffectScale();
			saved = effects.getValue();
			effects.setValue(0.0);
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			if (client.options != null && saved != null) {
				client.options.getFovEffectScale().setValue(saved);
			}
			saved = null;
		}
	}

	// -------------------------------------------------------- screen effects

	/** Frames blend into the next, for smooth trails when you turn. */
	public static final class MotionBlur extends Module {
		MotionBlur() {
			super("Motion Blur", Category.VISUAL, "Smooth trails when you turn. Experimental.");
		}
	}

	/** A cinematic grade: more contrast and colour, a soft dark vignette. */
	public static final class ColorGrading extends Module {
		ColorGrading() {
			super("Color Grading", Category.VISUAL, "Richer colour and contrast, a soft vignette.");
		}
	}

	/**
	 * Keeps the game renderer's post-effect slot in step with Motion Blur and
	 * Color Grading. Only one post effect can run at a time, so both together
	 * use a third effect that does both in one pass chain.
	 *
	 * <p>It never replaces an effect it did not set - spectating a creeper
	 * still gets vanilla's creeper view - and puts its own back after vanilla
	 * clears the slot, which it does on respawn and dimension change.
	 */
	static final class PostEffects {
		private static final Identifier BLUR = Identifier.of(NAMESPACE, "motion_blur");
		private static final Identifier GRADE = Identifier.of(NAMESPACE, "color_grade");
		private static final Identifier BOTH = Identifier.of(NAMESPACE, "graded_motion_blur");

		private PostEffects() {
		}

		static void update(MinecraftClient client) {
			if (client.gameRenderer == null) {
				return;
			}
			boolean blur = Modules.MOTION_BLUR.isEnabled();
			boolean grade = Modules.COLOR_GRADING.isEnabled();
			Identifier wanted = blur && grade ? BOTH : blur ? BLUR : grade ? GRADE : null;

			GameRendererAccessor renderer = (GameRendererAccessor) client.gameRenderer;
			Identifier current = renderer.obsidian$getPostProcessorId();
			if (Objects.equals(current, wanted)) {
				return;
			}
			boolean ours = current != null && NAMESPACE.equals(current.getNamespace());
			if (wanted == null) {
				if (ours) {
					renderer.obsidian$clearPostProcessor();
				}
			} else if (current == null || ours) {
				renderer.obsidian$setPostProcessor(wanted);
			}
		}
	}

	// ------------------------------------------------------------ sky + time

	/** A near-black sky with the stars out, day and night. See SkyRenderingMixin. */
	public static final class ObsidianSkyModule extends Module {
		ObsidianSkyModule() {
			super("Obsidian Sky", Category.VISUAL, "A near-black sky with the stars always out.");
		}
	}

	/** Locks this client's clock to noon. Only your screen changes. */
	public static final class AlwaysDay extends Module {
		AlwaysDay() {
			super("Always Day", Category.VISUAL, "It is always noon - on your screen only.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			Modules.ALWAYS_NIGHT.setEnabled(false);
		}
	}

	/** Locks this client's clock to midnight. Only your screen changes. */
	public static final class AlwaysNight extends Module {
		AlwaysNight() {
			super("Always Night", Category.VISUAL, "It is always midnight - on your screen only.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			Modules.ALWAYS_DAY.setEnabled(false);
		}
	}

	// --------------------------------------------------------- low health

	/** A red pulse round the screen edges when you drop below 30% health. */
	public static final class LowHealthWarning extends Module {
		private static final float THRESHOLD = 0.3f;
		private static final int DEPTH = 44;
		private static final int STEP = 2;
		private static final int RED = 0xD01E1E;

		LowHealthWarning() {
			super("Low Health Warning", Category.VISUAL, "The screen edges pulse red when you are low.");
		}

		@Override
		public void renderOverlay(DrawContext context, MinecraftClient client) {
			ClientPlayerEntity player = client.player;
			if (player == null || player.isSpectator()) {
				return;
			}
			float max = player.getMaxHealth();
			float health = player.getHealth();
			if (max <= 0 || health <= 0 || health / max > THRESHOLD) {
				return;
			}

			// Lower health: a stronger, faster pulse.
			float severity = 1.0f - (health / max) / THRESHOLD;
			double period = 1.1 - 0.5 * severity;
			double phase = (System.currentTimeMillis() / 1000.0) % period / period;
			float pulse = (float) (0.5 + 0.5 * Math.sin(phase * Math.PI * 2));
			float peak = (70 + 90 * severity) * (0.55f + 0.45f * pulse);

			int width = client.getWindow().getScaledWidth();
			int height = client.getWindow().getScaledHeight();
			for (int i = 0; i < DEPTH; i += STEP) {
				float fade = 1.0f - (float) i / DEPTH;
				int alpha = Math.round(peak * fade * fade);
				if (alpha <= 0) {
					continue;
				}
				int color = (alpha << 24) | RED;
				context.fill(0, i, width, i + STEP, color);
				context.fill(0, height - i - STEP, width, height - i, color);
				context.fill(i, 0, i + STEP, height, color);
				context.fill(width - i - STEP, 0, width - i, height, color);
			}
		}
	}
}
