package com.blueprintclient;

import com.blueprintclient.module.Module;
import com.blueprintclient.module.Modules;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Drag the HUD elements wherever you want them.
 *
 * <p>Every enabled HUD module is drawn where it really sits, inside a handle
 * box that can be picked up. Positions are kept as a fraction of the screen, so
 * moving something to the bottom right keeps it there at another resolution.
 */
public class HudEditorScreen extends Screen {
	private static final int BACKDROP_TOP = 0xC0050D1A;
	private static final int BACKDROP_BOTTOM = 0xC00D1F2B;
	private static final int GRID = 0x14294B73;
	private static final int BOX_BG = 0x40123A63;
	private static final int BOX_BG_HOVER = 0x603D8BFF;
	private static final int BORDER = 0xFF1B3A63;
	private static final int ACCENT = 0xFF3D8BFF;
	private static final int ACCENT_LIGHT = 0xFF8ECBFF;
	private static final int TEXT = 0xFFE8F4FF;
	private static final int MUTED = 0xFF6F90AD;
	private static final int FIELD_BG = 0xFF12263D;
	private static final int GUIDE = 0xFF7CE0A8;

	private static final int PADDING = 3;
	private static final int SNAP = 6;
	private static final int BUTTON_HEIGHT = 16;

	private final Screen parent;

	private Module dragging;
	private int grabX;
	private int grabY;
	private boolean snappedX;
	private boolean snappedY;

	public HudEditorScreen(Screen parent) {
		super(Text.literal("HUD Editor"));
		this.parent = parent;
	}

	// -------------------------------------------------------------- geometry

	private List<Module> elements() {
		List<Module> shown = new java.util.ArrayList<>();
		for (Module module : Modules.hudElements()) {
			if (module.isEnabled()) {
				shown.add(module);
			}
		}
		return shown;
	}

	private int elementX(Module module) {
		return Math.round(module.getHudX() * this.width);
	}

	private int elementY(Module module) {
		return Math.round(module.getHudY() * this.height);
	}

	private int elementWidth(Module module) {
		return Math.max(module.hudWidth(this.client), this.textRenderer.getWidth(module.getName()));
	}

	private int elementHeight(Module module) {
		return Math.max(module.hudHeight(this.client), this.textRenderer.fontHeight);
	}

	private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	private int resetWidth() {
		return this.textRenderer.getWidth("RESET LAYOUT") + 16;
	}

	private int doneWidth() {
		return this.textRenderer.getWidth("DONE") + 16;
	}

	private int barY() {
		return this.height - BUTTON_HEIGHT - 8;
	}

	private int resetX() {
		return this.width / 2 - (resetWidth() + doneWidth() + 8) / 2;
	}

	private int doneX() {
		return resetX() + resetWidth() + 8;
	}

	// ------------------------------------------------------------- rendering

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, this.width, this.height, BACKDROP_TOP, BACKDROP_BOTTOM);
		for (int x = 0; x < this.width; x += 24) {
			context.fill(x, 0, x + 1, this.height, GRID);
		}
		for (int y = 0; y < this.height; y += 24) {
			context.fill(0, y, this.width, y + 1, GRID);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		List<Module> shown = elements();
		for (Module module : shown) {
			drawElement(context, module, mouseX, mouseY);
		}

		if (dragging != null) {
			drawGuides(context);
		}

		drawHeader(context, shown.size());
		drawButtons(context, mouseX, mouseY);
	}

	private void drawElement(DrawContext context, Module module, int mouseX, int mouseY) {
		int x = elementX(module);
		int y = elementY(module);
		int width = elementWidth(module);
		int height = elementHeight(module);

		boolean hovered = dragging == module
				|| inside(mouseX, mouseY, x - PADDING, y - PADDING, width + PADDING * 2, height + PADDING * 2);

		context.fill(
				x - PADDING, y - PADDING, x + width + PADDING, y + height + PADDING,
				hovered ? BOX_BG_HOVER : BOX_BG);
		border(context, x - PADDING, y - PADDING, width + PADDING * 2, height + PADDING * 2,
				hovered ? ACCENT_LIGHT : BORDER);

		module.renderHudAt(context, this.client, x, y);

		// Modules with nothing to show yet - Ping in single player, say - would
		// otherwise be invisible and impossible to grab.
		if (module.hudWidth(this.client) == 0) {
			context.drawTextWithShadow(this.textRenderer, module.getName(), x, y, MUTED);
		}
	}

	/** Green rules showing what the dragged element has snapped to. */
	private void drawGuides(DrawContext context) {
		int x = elementX(dragging);
		int y = elementY(dragging);
		if (snappedX) {
			context.fill(x - 1, 0, x, this.height, GUIDE);
		}
		if (snappedY) {
			context.fill(0, y - 1, this.width, y, GUIDE);
		}
	}

	private void drawHeader(DrawContext context, int count) {
		context.fill(0, 0, this.width, 30, 0xD00A1929);
		context.fill(0, 30, this.width, 31, BORDER);
		context.fill(10, 8, 13, 22, ACCENT);
		context.drawTextWithShadow(this.textRenderer, "HUD EDITOR", 20, 6, ACCENT_LIGHT);
		context.drawTextWithShadow(
				this.textRenderer,
				count == 0
						? "No HUD modules are on - turn some on in the menu first."
						: "Drag any box to move it. It snaps to the edges and the middle.",
				20, 18, MUTED);
	}

	private void drawButtons(DrawContext context, int mouseX, int mouseY) {
		drawButton(context, resetX(), barY(), resetWidth(), "RESET LAYOUT", mouseX, mouseY, false);
		drawButton(context, doneX(), barY(), doneWidth(), "DONE", mouseX, mouseY, true);
	}

	private void drawButton(
			DrawContext context, int x, int y, int width, String label, int mouseX, int mouseY, boolean primary) {
		boolean hovered = inside(mouseX, mouseY, x, y, width, BUTTON_HEIGHT);
		int fill = primary ? (hovered ? 0xFF5A9DFF : ACCENT) : (hovered ? BORDER : FIELD_BG);
		context.fill(x, y, x + width, y + BUTTON_HEIGHT, fill);
		border(context, x, y, width, BUTTON_HEIGHT, hovered ? ACCENT_LIGHT : BORDER);
		context.drawTextWithShadow(
				this.textRenderer, label, x + 8, y + 4, primary ? 0xFF050D1A : TEXT);
	}

	private void border(DrawContext context, int x, int y, int width, int height, int color) {
		context.fill(x, y, x + width, y + 1, color);
		context.fill(x, y + height - 1, x + width, y + height, color);
		context.fill(x, y, x + 1, y + height, color);
		context.fill(x + width - 1, y, x + width, y + height, color);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();

		if (inside(mouseX, mouseY, resetX(), barY(), resetWidth(), BUTTON_HEIGHT)) {
			for (Module module : Modules.hudElements()) {
				module.resetHudPosition();
			}
			return true;
		}
		if (inside(mouseX, mouseY, doneX(), barY(), doneWidth(), BUTTON_HEIGHT)) {
			close();
			return true;
		}

		List<Module> shown = elements();
		// Last drawn is on top, so search backwards.
		for (int index = shown.size() - 1; index >= 0; index--) {
			Module module = shown.get(index);
			int x = elementX(module);
			int y = elementY(module);
			int width = elementWidth(module) + PADDING * 2;
			int height = elementHeight(module) + PADDING * 2;
			if (inside(mouseX, mouseY, x - PADDING, y - PADDING, width, height)) {
				dragging = module;
				grabX = (int) (mouseX - x);
				grabY = (int) (mouseY - y);
				return true;
			}
		}

		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (dragging == null) {
			return super.mouseDragged(click, deltaX, deltaY);
		}

		int width = elementWidth(dragging);
		int height = elementHeight(dragging);
		int x = clamp((int) (click.x() - grabX), 0, this.width - width);
		int y = clamp((int) (click.y() - grabY), 0, this.height - height);

		int snappedHorizontally = snap(x, width, this.width);
		int snappedVertically = snap(y, height, this.height);
		snappedX = snappedHorizontally != x;
		snappedY = snappedVertically != y;

		dragging.setHudPosition(
				snappedHorizontally / (float) this.width, snappedVertically / (float) this.height);
		return true;
	}

	/** Pull an edge to the screen border or the centre line when it is close. */
	private static int snap(int position, int size, int screen) {
		int margin = 4;
		if (Math.abs(position - margin) <= SNAP) {
			return margin;
		}
		if (Math.abs(position + size - (screen - margin)) <= SNAP) {
			return screen - margin - size;
		}
		int centered = (screen - size) / 2;
		if (Math.abs(position - centered) <= SNAP) {
			return centered;
		}
		return position;
	}

	@Override
	public boolean mouseReleased(Click click) {
		dragging = null;
		snappedX = false;
		snappedY = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void close() {
		BlueprintConfig.save();
		this.client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(Math.max(min, max), value));
	}
}
