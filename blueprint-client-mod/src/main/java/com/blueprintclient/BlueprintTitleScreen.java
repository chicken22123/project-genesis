package com.blueprintclient;

import com.blueprintclient.module.Module;
import com.blueprintclient.module.Modules;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The main menu the mod puts in front of the vanilla one.
 *
 * <p>Everything is drawn by hand - background, wordmark and buttons - so the
 * screen matches the launcher rather than looking like vanilla with a different
 * colour behind it.
 */
public class BlueprintTitleScreen extends Screen {
	private static final int BG_TOP = 0xFF050D1A;
	private static final int BG_BOTTOM = 0xFF0D1F2B;
	private static final int PANEL_BG = 0xF00D1F2B;
	private static final int PANEL_ALT = 0xF00A1929;
	private static final int BORDER = 0xFF1B3A63;
	private static final int ACCENT = 0xFF3D8BFF;
	private static final int ACCENT_LIGHT = 0xFF8ECBFF;
	private static final int TEXT = 0xFFE8F4FF;
	private static final int MUTED = 0xFF6F90AD;
	private static final int OK_GREEN = 0xFF7CE0A8;

	private static final int BUTTON_WIDTH = 240;
	private static final int BUTTON_HEIGHT = 26;
	private static final int BUTTON_GAP = 6;

	private final List<MenuItem> items = new ArrayList<>();
	private int ticks;

	private record MenuItem(String label, String hint, Runnable action, boolean primary) {
	}

	public BlueprintTitleScreen() {
		super(Text.literal("Blueprint Client"));
	}

	@Override
	protected void init() {
		super.init();
		items.clear();
		items.add(new MenuItem("SINGLEPLAYER", "Your worlds", () ->
				this.client.setScreen(new SelectWorldScreen(this)), true));
		items.add(new MenuItem("MULTIPLAYER", "Server list", () ->
				this.client.setScreen(new MultiplayerScreen(this)), false));
		items.add(new MenuItem("OPTIONS", "Video, controls, sound", () ->
				this.client.setScreen(new OptionsScreen(this, this.client.options)), false));
		items.add(new MenuItem("QUIT", "Close the game", () -> this.client.scheduleStop(), false));
	}

	@Override
	public void tick() {
		super.tick();
		ticks++;
	}

	// -------------------------------------------------------------- geometry

	private int menuTop() {
		return this.height / 2 - 24;
	}

	private int buttonX() {
		return this.width / 2 - BUTTON_WIDTH / 2;
	}

	private int buttonY(int index) {
		return menuTop() + index * (BUTTON_HEIGHT + BUTTON_GAP);
	}

	private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	// ------------------------------------------------------------- rendering

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, this.width, this.height, BG_TOP, BG_BOTTOM);
		drawGrid(context);
		drawHorizonGlow(context);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		drawWordmark(context);

		for (int index = 0; index < items.size(); index++) {
			drawButton(context, items.get(index), buttonX(), buttonY(index), mouseX, mouseY);
		}

		drawStatusBar(context);
		drawModuleCard(context);
	}

	private void drawGrid(DrawContext context) {
		int step = 28;
		float pulse = (float) (0.5 + 0.5 * Math.sin(ticks * 0.02));
		int alpha = (int) (10 + 8 * pulse);
		int color = (alpha << 24) | 0x1E3A63;
		for (int x = 0; x < this.width; x += step) {
			context.fill(x, 0, x + 1, this.height, color);
		}
		for (int y = 0; y < this.height; y += step) {
			context.fill(0, y, this.width, y + 1, color);
		}
	}

	/** A soft band behind the wordmark, brightest in the middle. */
	private void drawHorizonGlow(DrawContext context) {
		int centerY = this.height / 3;
		int band = 46;
		for (int offset = band; offset > 0; offset -= 2) {
			int alpha = (int) (26.0 * (1.0 - (double) offset / band));
			if (alpha <= 0) {
				continue;
			}
			int color = (alpha << 24) | 0x3D8BFF;
			context.fill(0, centerY - offset, this.width, centerY + offset, color);
		}
	}

	private void drawWordmark(DrawContext context) {
		int centerX = this.width / 2;
		int titleY = this.height / 3 - 18;

		// Scaled up so the wordmark reads as a logo rather than a line of text.
		context.getMatrices().pushMatrix();
		context.getMatrices().scale(2.0f, 2.0f);

		String blueprint = "BLUEPRINT";
		String client = " CLIENT";
		int total = this.textRenderer.getWidth(blueprint) + this.textRenderer.getWidth(client);
		int startX = (centerX - total) / 2;
		int scaledY = titleY / 2;

		context.drawTextWithShadow(this.textRenderer, blueprint, startX, scaledY, ACCENT);
		context.drawTextWithShadow(
				this.textRenderer, client, startX + this.textRenderer.getWidth(blueprint), scaledY, ACCENT_LIGHT);

		context.getMatrices().popMatrix();

		drawShimmerRule(context, centerX, titleY + 24);

		String version = "MINECRAFT 1.21.11";
		int versionWidth = this.textRenderer.getWidth(version);
		int pillX = centerX - versionWidth / 2 - 8;
		int pillY = titleY + 32;
		context.fill(pillX, pillY, pillX + versionWidth + 16, pillY + 14, PANEL_BG);
		border(context, pillX, pillY, versionWidth + 16, 14, BORDER);
		context.drawTextWithShadow(this.textRenderer, version, pillX + 8, pillY + 3, MUTED);
	}

	private void drawShimmerRule(DrawContext context, int centerX, int y) {
		int ruleHalf = 110;
		context.fill(centerX - ruleHalf, y, centerX + ruleHalf, y + 1, BORDER);

		float phase = (ticks % 100) / 100f;
		int streakWidth = 50;
		int streakX = (int) (centerX - ruleHalf + (ruleHalf * 2 + streakWidth) * phase - streakWidth);
		int x0 = Math.max(centerX - ruleHalf, streakX);
		int x1 = Math.min(centerX + ruleHalf, streakX + streakWidth);
		if (x1 > x0) {
			context.fill(x0, y, x1, y + 1, ACCENT_LIGHT);
		}
	}

	private void drawButton(DrawContext context, MenuItem item, int x, int y, int mouseX, int mouseY) {
		boolean hovered = inside(mouseX, mouseY, x, y, BUTTON_WIDTH, BUTTON_HEIGHT);

		int fill;
		int labelColor;
		if (item.primary()) {
			fill = hovered ? 0xFF5A9DFF : ACCENT;
			labelColor = BG_TOP;
		} else {
			fill = hovered ? PANEL_BG : PANEL_ALT;
			labelColor = hovered ? TEXT : MUTED;
		}

		context.fill(x, y, x + BUTTON_WIDTH, y + BUTTON_HEIGHT, fill);
		border(context, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, hovered ? ACCENT_LIGHT : BORDER);

		// The accent bar slides in from the left on hover.
		if (!item.primary()) {
			int bar = hovered ? 3 : 1;
			context.fill(x, y, x + bar, y + BUTTON_HEIGHT, hovered ? ACCENT : BORDER);
		}

		int textY = y + (BUTTON_HEIGHT - this.textRenderer.fontHeight) / 2;
		context.drawTextWithShadow(this.textRenderer, item.label(), x + 14, textY, labelColor);

		int hintWidth = this.textRenderer.getWidth(item.hint());
		context.drawTextWithShadow(
				this.textRenderer,
				item.hint(),
				x + BUTTON_WIDTH - 12 - hintWidth,
				textY,
				item.primary() ? 0x66050D1A : MUTED);
	}

	private void drawStatusBar(DrawContext context) {
		int barY = this.height - 18;
		context.fill(0, barY, this.width, this.height, PANEL_ALT);
		context.fill(0, barY, this.width, barY + 1, BORDER);

		float pulse = (float) (0.5 + 0.5 * Math.sin(ticks * 0.15));
		int alpha = (int) (140 + 100 * pulse);
		context.fill(8, barY + 7, 12, barY + 11, (alpha << 24) | 0x7CE0A8);
		context.drawTextWithShadow(this.textRenderer, "Ready", 18, barY + 5, OK_GREEN);

		String fps = this.client.getCurrentFps() + " fps";
		String hint = "Right Shift opens the Blueprint menu in game";
		context.drawTextWithShadow(this.textRenderer, hint, 60, barY + 5, MUTED);
		context.drawTextWithShadow(
				this.textRenderer, fps, this.width - 8 - this.textRenderer.getWidth(fps), barY + 5, ACCENT_LIGHT);
	}

	/** A small card showing what the client has loaded, top right. */
	private void drawModuleCard(DrawContext context) {
		int enabled = 0;
		for (Module module : Modules.all()) {
			if (module.isEnabled()) {
				enabled++;
			}
		}

		String heading = "MODULES";
		String value = enabled + " / " + Modules.all().size() + " active";
		int width = Math.max(this.textRenderer.getWidth(value), this.textRenderer.getWidth(heading)) + 20;
		int x = this.width - width - 10;
		int y = 10;
		int height = 34;

		context.fill(x, y, x + width, y + height, PANEL_BG);
		border(context, x, y, width, height, BORDER);
		context.fill(x, y, x + 2, y + height, ACCENT);
		context.drawTextWithShadow(this.textRenderer, heading, x + 10, y + 6, MUTED);
		context.drawTextWithShadow(this.textRenderer, value, x + 10, y + 19, TEXT);
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
		for (int index = 0; index < items.size(); index++) {
			if (inside(click.x(), click.y(), buttonX(), buttonY(index), BUTTON_WIDTH, BUTTON_HEIGHT)) {
				this.client.getSoundManager().play(
						PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				items.get(index).action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
