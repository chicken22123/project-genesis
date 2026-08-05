package com.blueprintclient.module;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * One toggle in the Blueprint menu.
 *
 * <p>A module can do three things: react to being switched on or off, run
 * something every client tick, and put a line (or its own drawing) on the HUD.
 * Everything a module needs is passed in, so modules never reach for globals.
 */
public abstract class Module {
	/** The blue every HUD element is drawn in. */
	public static final int HUD_COLOR = 0xFF8ECBFF;

	private final String name;
	private final Category category;
	private final String description;
	private boolean enabled;

	// Where this element sits on the HUD, as a fraction of the screen, so a
	// position survives a resolution or GUI scale change.
	private float hudX;
	private float hudY;
	private float defaultHudX;
	private float defaultHudY;

	protected Module(String name, Category category, String description) {
		this.name = name;
		this.category = category;
		this.description = description;
	}

	public String getName() {
		return name;
	}

	public Category getCategory() {
		return category;
	}

	public String getDescription() {
		return description;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void toggle() {
		setEnabled(!enabled);
	}

	public void setEnabled(boolean value) {
		if (enabled == value) {
			return;
		}
		enabled = value;
		MinecraftClient client = MinecraftClient.getInstance();
		if (value) {
			onEnable(client);
		} else {
			onDisable(client);
		}
	}

	/**
	 * Restore a saved state without side effects.
	 *
	 * <p>The config is read while the client is still starting, when the game
	 * options a module might touch do not exist yet. {@link Modules#applyStartup}
	 * runs the enable hooks later, on the first tick.
	 */
	public void setEnabledSilently(boolean value) {
		enabled = value;
	}

	protected void onEnable(MinecraftClient client) {
	}

	protected void onDisable(MinecraftClient client) {
	}

	public void tick(MinecraftClient client) {
	}

	/** A single line of text for the HUD, or null to draw nothing. */
	public String hudLine(MinecraftClient client) {
		return null;
	}

	// ------------------------------------------------------------ hud layout

	/** HUD modules are the ones that can be moved around in the HUD editor. */
	public boolean isHudElement() {
		return category == Category.HUD;
	}

	public float getHudX() {
		return hudX;
	}

	public float getHudY() {
		return hudY;
	}

	public void setHudPosition(float x, float y) {
		hudX = Math.max(0.0f, Math.min(1.0f, x));
		hudY = Math.max(0.0f, Math.min(1.0f, y));
	}

	/** Sets the position and remembers it, so Reset in the editor can restore it. */
	public void setDefaultHudPosition(float x, float y) {
		defaultHudX = x;
		defaultHudY = y;
		setHudPosition(x, y);
	}

	public void resetHudPosition() {
		setHudPosition(defaultHudX, defaultHudY);
	}

	/**
	 * Draw this element with its top left corner at the given point.
	 *
	 * <p>Text modules get this for free from {@link #hudLine}; anything that
	 * draws more than a line overrides it along with the two size methods.
	 */
	public void renderHudAt(DrawContext context, MinecraftClient client, int x, int y) {
		String line = hudLine(client);
		if (line != null) {
			context.drawTextWithShadow(client.textRenderer, line, x, y, HUD_COLOR);
		}
	}

	public int hudWidth(MinecraftClient client) {
		String line = hudLine(client);
		return line == null ? 0 : client.textRenderer.getWidth(line);
	}

	public int hudHeight(MinecraftClient client) {
		return client.textRenderer.fontHeight;
	}
}
