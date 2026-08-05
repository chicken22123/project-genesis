package com.blueprintclient.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.ParticlesMode;

import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every module Blueprint ships, and the registry the menu and the HUD read.
 *
 * <p>These are all client-side comfort features - brightness, zoom, readouts.
 * Nothing here tells the server anything it would not already hear from a
 * vanilla client.
 */
public final class Modules {
	private static final List<Module> ALL = new ArrayList<>();
	private static final long SESSION_START = System.currentTimeMillis();
	private static boolean startupApplied;

	public static final Module FPS_BOOST = register(new FpsBoost());
	public static final Module FAST_GRAPHICS = register(new FastGraphics());
	public static final Module NO_CLOUDS = register(new NoClouds());
	public static final Module NO_PARTICLES = register(new NoParticles());
	public static final Module NO_ENTITY_SHADOWS = register(new NoEntityShadows());
	public static final Module NO_WEATHER = register(new NoWeatherRender());
	public static final Module NO_VIGNETTE = register(new NoVignette());
	public static final Module SHORT_RENDER = register(new ShortRenderDistance());
	public static final Module UNCAP_FPS = register(new UncapFps());

	public static final Module FULLBRIGHT = register(new Fullbright());
	public static final Module ZOOM = register(new Zoom());
	public static final Module NO_BOBBING = register(new NoBobbing());
	public static final Module CONFETTI = register(new Confetti());

	public static final Module FPS = register(new FpsDisplay());
	public static final Module COORDINATES = register(new Coordinates());
	public static final Module BIOME = register(new BiomeDisplay());
	public static final Module PING = register(new PingDisplay());
	public static final Module CLOCK = register(new ClockDisplay());
	public static final Module WORLD_TIME = register(new WorldTimeDisplay());
	public static final Module SPEED = register(new Speedometer());
	public static final Module ARMOR = register(new ArmorDisplay());
	public static final Module CPS = register(new CpsCounter());
	public static final Module SESSION = register(new SessionTimer());
	public static final Module KEYSTROKES = register(new Keystrokes());

	public static final Module AUTO_SPRINT = register(new AutoSprint());
	public static final Module AUTO_JUMP = register(new AutoJump());

	public static final Module HIDE_HUD = register(new HideHud());
	public static final Module COPY_COORDS = register(new CopyCoords());

	private Modules() {
	}

	private static <T extends Module> T register(T module) {
		ALL.add(module);
		return module;
	}

	public static List<Module> all() {
		return Collections.unmodifiableList(ALL);
	}

	public static List<Module> byCategory(Category category) {
		List<Module> found = new ArrayList<>();
		for (Module module : ALL) {
			if (module.getCategory() == category) {
				found.add(module);
			}
		}
		return found;
	}

	/** Modules in a category whose name or description matches the search box. */
	public static List<Module> search(Category category, String query) {
		if (query == null || query.isBlank()) {
			return byCategory(category);
		}
		String needle = query.toLowerCase(Locale.ROOT);
		List<Module> found = new ArrayList<>();
		for (Module module : byCategory(category)) {
			if (module.getName().toLowerCase(Locale.ROOT).contains(needle)
					|| module.getDescription().toLowerCase(Locale.ROOT).contains(needle)) {
				found.add(module);
			}
		}
		return found;
	}

	public static Module byName(String name) {
		for (Module module : ALL) {
			if (module.getName().equals(name)) {
				return module;
			}
		}
		return null;
	}

	public static void tick(MinecraftClient client) {
		applyStartup(client);
		for (Module module : ALL) {
			if (module.isEnabled()) {
				module.tick(client);
			}
		}
	}

	/** Run the enable hooks for modules restored from the config file. */
	private static void applyStartup(MinecraftClient client) {
		if (startupApplied || client.options == null) {
			return;
		}
		startupApplied = true;
		for (Module module : ALL) {
			if (module.isEnabled()) {
				module.onEnable(client);
			}
		}
	}

	// ------------------------------------------------------------- performance

	/**
	 * Base for the modules that trade detail for frames.
	 *
	 * <p>Each one records the value of every option it touches the first time it
	 * changes it, and puts all of them back when it is switched off, so turning
	 * a module on and off again leaves the video settings exactly as they were.
	 */
	private abstract static class OptionModule extends Module {
		private final Map<SimpleOption<?>, Object> saved = new LinkedHashMap<>();

		private OptionModule(String name, String description) {
			super(name, Category.PERFORMANCE, description);
		}

		protected <T> void apply(SimpleOption<T> option, T value) {
			saved.putIfAbsent(option, option.getValue());
			option.setValue(value);
		}

		protected abstract void applyOptions(MinecraftClient client);

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.options != null) {
				applyOptions(client);
			}
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			for (Map.Entry<SimpleOption<?>, Object> entry : saved.entrySet()) {
				@SuppressWarnings("unchecked")
				SimpleOption<Object> option = (SimpleOption<Object>) entry.getKey();
				option.setValue(entry.getValue());
			}
			saved.clear();
		}
	}

	private static final class FpsBoost extends OptionModule {
		private FpsBoost() {
			super("FPS Boost", "Everything below at once: the biggest frame rate win.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			var options = client.options;
			apply(options.getPreset(), GraphicsMode.FAST);
			apply(options.getCloudRenderMode(), CloudRenderMode.OFF);
			apply(options.getParticles(), ParticlesMode.MINIMAL);
			apply(options.getEntityShadows(), false);
			apply(options.getAo(), false);
			apply(options.getVignette(), false);
			// A duration, not a switch: zero means chunks pop in with no fade.
			apply(options.getChunkFade(), 0.0);
			apply(options.getBiomeBlendRadius(), 0);
			apply(options.getMipmapLevels(), 0);
			apply(options.getEntityDistanceScaling(), 0.5);
		}
	}

	private static final class FastGraphics extends OptionModule {
		private FastGraphics() {
			super("Fast Graphics", "Switches the graphics preset to Fast.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			apply(client.options.getPreset(), GraphicsMode.FAST);
		}
	}

	private static final class NoClouds extends OptionModule {
		private NoClouds() {
			super("No Clouds", "Stops drawing clouds.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			apply(client.options.getCloudRenderMode(), CloudRenderMode.OFF);
		}
	}

	private static final class NoParticles extends OptionModule {
		private NoParticles() {
			super("No Particles", "Cuts particles to the minimum.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			apply(client.options.getParticles(), ParticlesMode.MINIMAL);
		}
	}

	private static final class NoEntityShadows extends OptionModule {
		private NoEntityShadows() {
			super("No Shadows", "Drops the shadow under every entity.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			apply(client.options.getEntityShadows(), false);
		}
	}

	private static final class NoWeatherRender extends OptionModule {
		private NoWeatherRender() {
			super("No Weather", "Shrinks rain and snow rendering to nothing.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			apply(client.options.getWeatherRadius(), 0);
		}
	}

	private static final class NoVignette extends OptionModule {
		private NoVignette() {
			super("No Vignette", "Removes the dark ring around the screen.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			apply(client.options.getVignette(), false);
		}
	}

	private static final class ShortRenderDistance extends OptionModule {
		private static final int CHUNKS = 6;

		private ShortRenderDistance() {
			super("Short Render", "Pulls render distance in to " + CHUNKS + " chunks.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			apply(client.options.getViewDistance(), CHUNKS);
		}
	}

	private static final class UncapFps extends OptionModule {
		private static final int UNLIMITED = 260;

		private UncapFps() {
			super("Uncap FPS", "Turns off vsync and lifts the frame rate limit.");
		}

		@Override
		protected void applyOptions(MinecraftClient client) {
			apply(client.options.getEnableVsync(), false);
			apply(client.options.getMaxFps(), UNLIMITED);
		}
	}

	// ------------------------------------------------------------------ visual

	private static final class Fullbright extends Module {
		private Double saved;

		private Fullbright() {
			super("Fullbright", Category.VISUAL, "Turns brightness all the way up.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.options == null) {
				return;
			}
			SimpleOption<Double> gamma = client.options.getGamma();
			saved = gamma.getValue();
			gamma.setValue(1.0);
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			if (client.options != null && saved != null) {
				client.options.getGamma().setValue(saved);
			}
			saved = null;
		}
	}

	private static final class Zoom extends Module {
		private static final int ZOOM_FOV = 30;
		private Integer saved;

		private Zoom() {
			super("Zoom", Category.VISUAL, "Drops your field of view for a closer look.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.options == null) {
				return;
			}
			SimpleOption<Integer> fov = client.options.getFov();
			saved = fov.getValue();
			fov.setValue(ZOOM_FOV);
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			if (client.options != null && saved != null) {
				client.options.getFov().setValue(saved);
			}
			saved = null;
		}
	}

	private static final class NoBobbing extends Module {
		private Boolean saved;

		private NoBobbing() {
			super("No View Bobbing", Category.VISUAL, "Stops the camera swaying as you walk.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.options == null) {
				return;
			}
			SimpleOption<Boolean> bob = client.options.getBobView();
			saved = bob.getValue();
			bob.setValue(false);
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			if (client.options != null && saved != null) {
				client.options.getBobView().setValue(saved);
			}
			saved = null;
		}
	}

	private static final class Confetti extends Module {
		private int cooldown;

		private Confetti() {
			super("Confetti", Category.VISUAL, "Sprinkles particles around you.");
		}

		@Override
		public void tick(MinecraftClient client) {
			if (client.player == null || client.world == null) {
				return;
			}
			if (--cooldown > 0) {
				return;
			}
			cooldown = 4;
			var random = client.player.getRandom();
			double x = client.player.getX() + (random.nextDouble() - 0.5) * 2;
			double y = client.player.getY() + 1.5 + random.nextDouble();
			double z = client.player.getZ() + (random.nextDouble() - 0.5) * 2;
			client.particleManager.addParticle(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 0, 0.05, 0);
		}
	}

	// --------------------------------------------------------------------- hud

	private static final class FpsDisplay extends Module {
		private FpsDisplay() {
			super("FPS", Category.HUD, "Frames per second.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			return "FPS: " + client.getCurrentFps();
		}
	}

	private static final class Coordinates extends Module {
		private Coordinates() {
			super("Coordinates", Category.HUD, "Your position and the way you are facing.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			return String.format(
					Locale.ROOT,
					"XYZ: %.1f / %.1f / %.1f  %s",
					client.player.getX(),
					client.player.getY(),
					client.player.getZ(),
					client.player.getHorizontalFacing().asString());
		}
	}

	private static final class BiomeDisplay extends Module {
		private BiomeDisplay() {
			super("Biome", Category.HUD, "The biome you are standing in.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			if (client.world == null) {
				return null;
			}
			var biome = client.world.getBiomeAccess().getBiomeForNoiseGen(client.player.getBlockPos());
			String id = biome.getIdAsString();
			int colon = id.indexOf(':');
			return "Biome: " + (colon >= 0 ? id.substring(colon + 1) : id);
		}
	}

	private static final class PingDisplay extends Module {
		private PingDisplay() {
			super("Ping", Category.HUD, "Round trip time to the server.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			if (client.getNetworkHandler() == null) {
				return null;
			}
			PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
			return entry == null ? null : "Ping: " + entry.getLatency() + " ms";
		}
	}

	private static final class ClockDisplay extends Module {
		private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

		private ClockDisplay() {
			super("Clock", Category.HUD, "The real world time, so you know when to stop.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			return "Time: " + LocalTime.now().format(FORMAT);
		}
	}

	private static final class WorldTimeDisplay extends Module {
		private WorldTimeDisplay() {
			super("World Time", Category.HUD, "In-game day number and clock.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			if (client.world == null) {
				return null;
			}
			long time = client.world.getTimeOfDay();
			long day = time / 24000L;
			long ticks = time % 24000L;
			long hour = (ticks / 1000L + 6L) % 24L;
			long minute = (ticks % 1000L) * 60L / 1000L;
			return String.format(Locale.ROOT, "Day %d - %02d:%02d", day, hour, minute);
		}
	}

	private static final class Speedometer extends Module {
		private double lastX;
		private double lastZ;
		private double speed;
		private boolean primed;

		private Speedometer() {
			super("Speed", Category.HUD, "How fast you are moving, in blocks per second.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			primed = false;
			speed = 0;
		}

		@Override
		public void tick(MinecraftClient client) {
			if (client.player == null) {
				return;
			}
			double x = client.player.getX();
			double z = client.player.getZ();
			if (primed) {
				double dx = x - lastX;
				double dz = z - lastZ;
				double measured = Math.sqrt(dx * dx + dz * dz) * 20.0;
				// A teleport or a dimension change would otherwise show as a
				// nonsense number for one tick.
				speed = measured > 100.0 ? 0.0 : measured;
			}
			lastX = x;
			lastZ = z;
			primed = true;
		}

		@Override
		public String hudLine(MinecraftClient client) {
			return String.format(Locale.ROOT, "Speed: %.1f b/s", speed);
		}
	}

	private static final class ArmorDisplay extends Module {
		private static final EquipmentSlot[] SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
		};

		private ArmorDisplay() {
			super("Armor", Category.HUD, "Durability left on each armour piece.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			StringBuilder line = new StringBuilder("Armor:");
			for (EquipmentSlot slot : SLOTS) {
				ItemStack stack = client.player.getEquippedStack(slot);
				if (stack.isEmpty()) {
					line.append(" --");
				} else if (!stack.isDamageable()) {
					line.append(" ok");
				} else {
					int left = stack.getMaxDamage() - stack.getDamage();
					line.append(' ').append(left * 100 / Math.max(1, stack.getMaxDamage())).append('%');
				}
			}
			return line.toString();
		}
	}

	private static final class CpsCounter extends Module {
		private final Deque<Long> left = new ArrayDeque<>();
		private final Deque<Long> right = new ArrayDeque<>();
		private boolean leftDown;
		private boolean rightDown;

		private CpsCounter() {
			super("CPS", Category.HUD, "Left and right clicks in the last second.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			// Polled per frame rather than per tick: 20 ticks a second cannot
			// see fast clicking.
			long handle = client.getWindow().getHandle();
			long now = System.currentTimeMillis();
			leftDown = record(left, leftDown, GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT), now);
			rightDown = record(right, rightDown, GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT), now);
			return "CPS: " + left.size() + " / " + right.size();
		}

		private static boolean record(Deque<Long> clicks, boolean wasDown, int state, long now) {
			boolean down = state == GLFW.GLFW_PRESS;
			if (down && !wasDown) {
				clicks.addLast(now);
			}
			while (!clicks.isEmpty() && clicks.peekFirst() < now - 1000L) {
				clicks.pollFirst();
			}
			return down;
		}
	}

	private static final class SessionTimer extends Module {
		private SessionTimer() {
			super("Session", Category.HUD, "How long this session has been running.");
		}

		@Override
		public String hudLine(MinecraftClient client) {
			long seconds = (System.currentTimeMillis() - SESSION_START) / 1000L;
			return String.format(
					Locale.ROOT, "Session: %02d:%02d:%02d", seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L);
		}
	}

	private static final class Keystrokes extends Module {
		private static final int KEY_SIZE = 16;
		private static final int GAP = 2;
		private static final int IDLE_BG = 0x90050D1A;
		private static final int PRESSED_BG = 0xFF3D8BFF;
		private static final int BORDER = 0xFF1B3A63;
		private static final int IDLE_TEXT = 0xFFE8F4FF;
		private static final int PRESSED_TEXT = 0xFF050D1A;

		private Keystrokes() {
			super("Keystrokes", Category.HUD, "Shows WASD, the mouse buttons and space.");
		}

		@Override
		public void renderHud(DrawContext context, MinecraftClient client) {
			int block = KEY_SIZE * 3 + GAP * 2;
			int x = 6;
			int y = client.getWindow().getScaledHeight() - (KEY_SIZE * 4 + GAP * 3) - 6;

			drawKey(context, client, x + KEY_SIZE + GAP, y, KEY_SIZE, "W",
					client.options.forwardKey.isPressed());

			int row = y + KEY_SIZE + GAP;
			drawKey(context, client, x, row, KEY_SIZE, "A", client.options.leftKey.isPressed());
			drawKey(context, client, x + KEY_SIZE + GAP, row, KEY_SIZE, "S", client.options.backKey.isPressed());
			drawKey(context, client, x + (KEY_SIZE + GAP) * 2, row, KEY_SIZE, "D",
					client.options.rightKey.isPressed());

			row += KEY_SIZE + GAP;
			int half = (block - GAP) / 2;
			long handle = client.getWindow().getHandle();
			drawBox(context, client, x, row, half, KEY_SIZE, "LMB",
					GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS);
			drawBox(context, client, x + half + GAP, row, block - half - GAP, KEY_SIZE, "RMB",
					GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS);

			row += KEY_SIZE + GAP;
			drawBox(context, client, x, row, block, KEY_SIZE, "____", client.options.jumpKey.isPressed());
		}

		private static void drawKey(
				DrawContext context, MinecraftClient client, int x, int y, int size, String label, boolean pressed) {
			drawBox(context, client, x, y, size, size, label, pressed);
		}

		private static void drawBox(
				DrawContext context,
				MinecraftClient client,
				int x,
				int y,
				int width,
				int height,
				String label,
				boolean pressed) {
			context.fill(x, y, x + width, y + height, pressed ? PRESSED_BG : IDLE_BG);
			context.fill(x, y, x + width, y + 1, BORDER);
			context.fill(x, y + height - 1, x + width, y + height, BORDER);
			context.fill(x, y, x + 1, y + height, BORDER);
			context.fill(x + width - 1, y, x + width, y + height, BORDER);
			context.drawCenteredTextWithShadow(
					client.textRenderer,
					label,
					x + width / 2,
					y + (height - client.textRenderer.fontHeight) / 2 + 1,
					pressed ? PRESSED_TEXT : IDLE_TEXT);
		}
	}

	// ---------------------------------------------------------------- movement

	private static final class AutoSprint extends Module {
		private AutoSprint() {
			super("Auto Sprint", Category.MOVEMENT, "Keeps you sprinting while you walk forward.");
		}

		@Override
		public void tick(MinecraftClient client) {
			if (client.player == null || client.options == null) {
				return;
			}
			if (client.options.forwardKey.isPressed() && !client.player.isSneaking()) {
				client.player.setSprinting(true);
			}
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			if (client.player != null) {
				client.player.setSprinting(false);
			}
		}
	}

	private static final class AutoJump extends Module {
		private Boolean saved;

		private AutoJump() {
			super("Auto Jump", Category.MOVEMENT, "Turns the vanilla auto jump option on.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.options == null) {
				return;
			}
			SimpleOption<Boolean> option = client.options.getAutoJump();
			saved = option.getValue();
			option.setValue(true);
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			if (client.options != null && saved != null) {
				client.options.getAutoJump().setValue(saved);
			}
			saved = null;
		}
	}

	// ----------------------------------------------------------------- utility

	private static final class HideHud extends Module {
		private HideHud() {
			super("Hide HUD", Category.UTILITY, "Hides the whole interface, for screenshots.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.options != null) {
				client.options.hudHidden = true;
			}
		}

		@Override
		protected void onDisable(MinecraftClient client) {
			if (client.options != null) {
				client.options.hudHidden = false;
			}
		}
	}

	private static final class CopyCoords extends Module {
		private CopyCoords() {
			super("Copy Coords", Category.UTILITY, "Copies your position, then switches itself off.");
		}

		@Override
		protected void onEnable(MinecraftClient client) {
			if (client.player != null && client.keyboard != null) {
				client.keyboard.setClipboard(String.format(
						Locale.ROOT,
						"%.0f %.0f %.0f",
						client.player.getX(),
						client.player.getY(),
						client.player.getZ()));
			}
			// An action, not a state: turn back off so the row reads OFF again.
			setEnabled(false);
		}
	}
}
