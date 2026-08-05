package com.blueprintclient;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class BlueprintTitleScreen extends Screen {
	private static final int BG_TOP = 0xFF050D1A;
	private static final int BG_BOTTOM = 0xFF0D1F2B;
	private static final int PANEL_BORDER = 0xFF3D8BFF;
	private static final int TITLE_COLOR = 0xFF8ECBFF;
	private static final int SUBTITLE_COLOR = 0x8F8FB0BF;

	private final List<ClickableWidget> navButtons = new ArrayList<>();
	private int ticks;

	public BlueprintTitleScreen() {
		super(Text.literal("Blueprint Client"));
	}

	@Override
	protected void init() {
		super.init();
		navButtons.clear();

		int centerX = this.width / 2;
		int buttonWidth = 220;
		int buttonY = this.height / 2 + 10;

		navButtons.add(this.addDrawableChild(ButtonWidget.builder(Text.literal("Singleplayer"), button ->
						this.client.setScreen(new net.minecraft.client.gui.screen.world.SelectWorldScreen(this)))
				.dimensions(centerX - buttonWidth / 2, buttonY, buttonWidth, 20)
				.build()));

		navButtons.add(this.addDrawableChild(ButtonWidget.builder(Text.literal("Multiplayer"), button ->
						this.client.setScreen(new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(this)))
				.dimensions(centerX - buttonWidth / 2, buttonY + 24, buttonWidth, 20)
				.build()));

		navButtons.add(this.addDrawableChild(ButtonWidget.builder(Text.literal("Options"), button -> {
					GameOptions options = this.client.options;
					this.client.setScreen(new OptionsScreen(this, options));
				})
				.dimensions(centerX - buttonWidth / 2, buttonY + 48, buttonWidth / 2 - 4, 20)
				.build()));

		navButtons.add(this.addDrawableChild(ButtonWidget.builder(Text.literal("Quit"), button -> this.client.scheduleStop())
				.dimensions(centerX + 4, buttonY + 48, buttonWidth / 2 - 4, 20)
				.build()));
	}

	@Override
	public void tick() {
		super.tick();
		ticks++;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, this.width, this.height, BG_TOP, BG_BOTTOM);
		drawGrid(context);

		int centerX = this.width / 2;
		int titleY = this.height / 4;

		context.drawCenteredTextWithShadow(this.textRenderer, "BLUEPRINT CLIENT", centerX, titleY, TITLE_COLOR);
		context.drawCenteredTextWithShadow(this.textRenderer, "MINECRAFT 1.21.11", centerX, titleY + 16, SUBTITLE_COLOR);
		drawShimmerRule(context, centerX, titleY + 30);

		for (ClickableWidget widget : navButtons) {
			if (widget.isHovered()) {
				drawHoverGlow(context, widget);
			}
		}

		super.render(context, mouseX, mouseY, delta);

		drawStatusFooter(context);
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

	private void drawShimmerRule(DrawContext context, int centerX, int y) {
		int ruleHalf = 90;
		context.fill(centerX - ruleHalf, y, centerX + ruleHalf, y + 1, PANEL_BORDER);

		float phase = (ticks % 100) / 100f;
		int streakWidth = 40;
		int streakX = (int) (centerX - ruleHalf + (ruleHalf * 2 + streakWidth) * phase - streakWidth);
		int x0 = Math.max(centerX - ruleHalf, streakX);
		int x1 = Math.min(centerX + ruleHalf, streakX + streakWidth);
		if (x1 > x0) {
			context.fill(x0, y, x1, y + 1, TITLE_COLOR);
		}
	}

	private void drawHoverGlow(DrawContext context, ClickableWidget widget) {
		int x0 = widget.getX() - 2;
		int x1 = widget.getX() + widget.getWidth() + 2;
		int y = widget.getY() + widget.getHeight() + 2;
		float pulse = (float) (0.6 + 0.4 * Math.sin(ticks * 0.3));
		int alpha = (int) (180 * pulse);
		int color = (alpha << 24) | 0x3D8BFF;
		context.fill(x0, y, x1, y + 2, color);
	}

	private void drawStatusFooter(DrawContext context) {
		float pulse = (float) (0.5 + 0.5 * Math.sin(ticks * 0.15));
		int alpha = (int) (140 + 100 * pulse);
		int dotColor = (alpha << 24) | 0x3D8BFF;
		context.fill(6, this.height - 13, 10, this.height - 9, dotColor);
		context.drawText(this.textRenderer, "Blueprint Client — Ready", 16, this.height - 14, SUBTITLE_COLOR, false);
	}
}
