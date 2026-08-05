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
	private final String name;
	private final Category category;
	private final String description;
	private boolean enabled;

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

	/** A single line for the HUD stack, or null to draw nothing. */
	public String hudLine(MinecraftClient client) {
		return null;
	}

	/** Free drawing, for modules that need more than one line of text. */
	public void renderHud(DrawContext context, MinecraftClient client) {
	}
}
