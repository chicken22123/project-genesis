package com.blueprintclient;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class BlueprintWelcomeScreen extends Screen {
	private static final int BACKDROP = 0xC0050D1A;
	private static final int PANEL_BG = 0xF00D1F2B;
	private static final int PANEL_BORDER = 0xFF3D8BFF;
	private static final int TITLE_COLOR = 0xFF8ECBFF;
	private static final int SUBTITLE_COLOR = 0x8F8FB0BF;

	private final Screen parent;

	public BlueprintWelcomeScreen(Screen parent) {
		super(Text.literal("Blueprint Client"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();

		int panelWidth = 300;
		int panelHeight = 150;
		int centerX = this.width / 2;
		int panelTop = (this.height - panelHeight) / 2;

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Continue"), button -> this.close())
				.dimensions(centerX - 70, panelTop + panelHeight - 34, 140, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, BACKDROP);

		int panelWidth = 300;
		int panelHeight = 150;
		int panelLeft = (this.width - panelWidth) / 2;
		int panelTop = (this.height - panelHeight) / 2;
		int panelRight = panelLeft + panelWidth;
		int panelBottom = panelTop + panelHeight;

		context.fill(panelLeft, panelTop, panelRight, panelBottom, PANEL_BG);
		context.fill(panelLeft, panelTop, panelRight, panelTop + 1, PANEL_BORDER);
		context.fill(panelLeft, panelBottom - 1, panelRight, panelBottom, PANEL_BORDER);
		context.fill(panelLeft, panelTop, panelLeft + 1, panelBottom, PANEL_BORDER);
		context.fill(panelRight - 1, panelTop, panelRight, panelBottom, PANEL_BORDER);

		int centerX = this.width / 2;
		context.drawCenteredTextWithShadow(this.textRenderer, "BLUEPRINT CLIENT", centerX, panelTop + 24, TITLE_COLOR);
		context.drawCenteredTextWithShadow(this.textRenderer, "Welcome back.", centerX, panelTop + 44, SUBTITLE_COLOR);

		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		this.client.setScreen(this.parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
