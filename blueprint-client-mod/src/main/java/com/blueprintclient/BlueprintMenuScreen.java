package com.blueprintclient;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;

public class BlueprintMenuScreen extends Screen {
	private static final int BG_TOP = 0xE0050D1A;
	private static final int BG_BOTTOM = 0xE00D1F2B;
	private static final int TITLE_COLOR = 0xFF8ECBFF;
	private static final int SUBTITLE_COLOR = 0x8F8FB0BF;

	public BlueprintMenuScreen() {
		super(Text.literal("Blueprint Menu"));
	}

	@Override
	protected void init() {
		super.init();

		int centerX = this.width / 2;
		int rowWidth = 280;
		int rowHeight = 26;
		int startY = this.height / 2 - 110;

		addToggle(centerX, startY, rowWidth, "Fullbright",
				() -> BlueprintFeatures.fullbright, BlueprintFeatures::toggleFullbright);
		addToggle(centerX, startY + rowHeight, rowWidth, "Zoom",
				() -> BlueprintFeatures.zoom, BlueprintFeatures::toggleZoom);
		addToggle(centerX, startY + rowHeight * 2, rowWidth, "Coordinates HUD",
				() -> BlueprintFeatures.coordinatesHud, BlueprintFeatures::toggleCoordinatesHud);
		addToggle(centerX, startY + rowHeight * 3, rowWidth, "FPS HUD",
				() -> BlueprintFeatures.fpsHud, BlueprintFeatures::toggleFpsHud);
		addToggle(centerX, startY + rowHeight * 4, rowWidth, "Confetti",
				() -> BlueprintFeatures.confetti, BlueprintFeatures::toggleConfetti);
	}

	private void addToggle(int centerX, int y, int width, String label, BooleanSupplier state, Runnable toggle) {
		ButtonWidget button = ButtonWidget.builder(labelFor(label, state.getAsBoolean()), b -> {
					toggle.run();
					b.setMessage(labelFor(label, state.getAsBoolean()));
				})
				.dimensions(centerX - width / 2, y, width, 20)
				.build();
		this.addDrawableChild(button);
	}

	private static Text labelFor(String label, boolean on) {
		return Text.literal(label + ":  " + (on ? "ON" : "OFF"));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, this.width, this.height, BG_TOP, BG_BOTTOM);

		int centerX = this.width / 2;
		int panelTop = this.height / 2 - 150;
		context.drawCenteredTextWithShadow(this.textRenderer, "BLUEPRINT MENU", centerX, panelTop, TITLE_COLOR);
		context.drawCenteredTextWithShadow(
				this.textRenderer, "Right Shift or Esc to close", centerX, panelTop + 14, SUBTITLE_COLOR);

		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
