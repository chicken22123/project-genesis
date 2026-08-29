package com.blueprintclient;

import com.blueprintclient.module.Category;
import com.blueprintclient.module.Module;
import com.blueprintclient.module.Modules;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;

/**
 * The click GUI: one draggable panel per category, each one collapsible, with a
 * search box and the menu key binding along the top.
 *
 * <p>Panels are drawn by hand rather than built from widgets, because a widget
 * cannot be picked up and moved around the screen.
 */
public class BlueprintMenuScreen extends Screen {
	private static final int BACKDROP_TOP = 0xC0050D1A;
	private static final int BACKDROP_BOTTOM = 0xC00D1F2B;
	private static final int PANEL_BG = 0xF00A1929;
	private static final int HEADER_BG = 0xFF0D1F2B;
	private static final int BORDER = 0xFF1B3A63;
	private static final int ROW_HOVER = 0x403D8BFF;
	private static final int ACCENT = 0xFF3D8BFF;
	private static final int ACCENT_LIGHT = 0xFF8ECBFF;
	private static final int TEXT = 0xFFE8F4FF;
	private static final int MUTED = 0xFF6F90AD;
	private static final int ON_GREEN = 0xFF7CE0A8;
	private static final int FIELD_BG = 0xFF12263D;

	private static final int HEADER_HEIGHT = 18;
	private static final int ROW_HEIGHT = 15;
	private static final int BAR_Y = 8;
	private static final int FIELD_HEIGHT = 16;
	private static final int SEARCH_WIDTH = 180;
	private static final int COLLAPSE_ZONE = 16;
	private static final String EDIT_LABEL = "EDIT HUD";
	private static final String SETUP_LABEL = "FLIPPER";

	private String search = "";
	private boolean binding;
	private Category dragging;
	private int dragOffsetX;
	private int dragOffsetY;

	public BlueprintMenuScreen() {
		super(Text.literal("Blueprint Menu"));
	}

	@Override
	protected void init() {
		super.init();
		MenuLayout.ensureDefaults(this.width, BAR_Y + FIELD_HEIGHT + 12);
	}

	// -------------------------------------------------------------- rendering

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		// Replaces the vanilla blur/darken so the blueprint gradient is the
		// only backdrop.
		context.fillGradient(0, 0, this.width, this.height, BACKDROP_TOP, BACKDROP_BOTTOM);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		drawTopBar(context, mouseX, mouseY);

		Module hovered = null;
		for (Category category : MenuLayout.order()) {
			Module found = drawPanel(context, category, mouseX, mouseY);
			if (found != null) {
				hovered = found;
			}
		}

		drawFooter(context, hovered);
	}

	private void drawTopBar(DrawContext context, int mouseX, int mouseY) {
		context.drawTextWithShadow(this.textRenderer, "BLUEPRINT", 10, BAR_Y + 4, ACCENT);
		int wordmark = this.textRenderer.getWidth("BLUEPRINT ");
		context.drawTextWithShadow(this.textRenderer, "CLIENT", 10 + wordmark, BAR_Y + 4, ACCENT_LIGHT);

		int searchX = searchX();
		boolean searching = !this.search.isEmpty();
		field(context, searchX, BAR_Y, SEARCH_WIDTH, searching || inside(mouseX, mouseY, searchX, BAR_Y, SEARCH_WIDTH, FIELD_HEIGHT));
		String shown = searching ? this.search + "_" : "Search modules...";
		context.drawTextWithShadow(
				this.textRenderer, shown, searchX + 6, BAR_Y + 4, searching ? TEXT : MUTED);

		int bindWidth = bindWidth();
		int bindX = bindX(bindWidth);
		field(context, bindX, BAR_Y, bindWidth, this.binding || inside(mouseX, mouseY, bindX, BAR_Y, bindWidth, FIELD_HEIGHT));
		context.drawTextWithShadow(
				this.textRenderer, bindLabel(), bindX + 8, BAR_Y + 4, this.binding ? ACCENT_LIGHT : TEXT);

		int editWidth = editWidth();
		int editX = editX();
		boolean editHovered = inside(mouseX, mouseY, editX, BAR_Y, editWidth, FIELD_HEIGHT);
		field(context, editX, BAR_Y, editWidth, editHovered);
		context.drawTextWithShadow(
				this.textRenderer, EDIT_LABEL, editX + 8, BAR_Y + 4, editHovered ? ACCENT_LIGHT : TEXT);

		int setupWidth = setupWidth();
		int setupX = setupX();
		boolean setupHovered = inside(mouseX, mouseY, setupX, BAR_Y, setupWidth, FIELD_HEIGHT);
		field(context, setupX, BAR_Y, setupWidth, setupHovered);
		context.drawTextWithShadow(
				this.textRenderer, SETUP_LABEL, setupX + 8, BAR_Y + 4, setupHovered ? ACCENT_LIGHT : TEXT);
	}

	/** Draws one panel and returns the module under the pointer, if any. */
	private Module drawPanel(DrawContext context, Category category, int mouseX, int mouseY) {
		List<Module> modules = Modules.search(category, this.search);
		if (modules.isEmpty() && !this.search.isEmpty()) {
			return null;
		}

		MenuLayout.Panel panel = MenuLayout.get(category);
		int x = panel.x;
		int y = panel.y;
		int width = MenuLayout.PANEL_WIDTH;
		int bodyHeight = panel.collapsed ? 0 : modules.size() * ROW_HEIGHT + 4;

		context.fill(x, y, x + width, y + HEADER_HEIGHT, HEADER_BG);
		context.fill(x, y, x + 3, y + HEADER_HEIGHT, ACCENT);
		context.drawTextWithShadow(
				this.textRenderer,
				category.getDisplayName().toUpperCase(Locale.ROOT),
				x + 10,
				y + 5,
				ACCENT_LIGHT);

		String count = Integer.toString(modules.size());
		int countWidth = this.textRenderer.getWidth(count);
		context.drawTextWithShadow(this.textRenderer, count, x + width - COLLAPSE_ZONE - countWidth - 4, y + 5, MUTED);
		context.drawTextWithShadow(this.textRenderer, panel.collapsed ? "+" : "-", x + width - 11, y + 5, ACCENT_LIGHT);

		Module hovered = null;
		if (!panel.collapsed) {
			context.fill(x, y + HEADER_HEIGHT, x + width, y + HEADER_HEIGHT + bodyHeight, PANEL_BG);

			int rowY = y + HEADER_HEIGHT + 2;
			for (Module module : modules) {
				boolean over = inside(mouseX, mouseY, x, rowY, width, ROW_HEIGHT);
				if (over) {
					context.fill(x, rowY, x + width, rowY + ROW_HEIGHT, ROW_HOVER);
					hovered = module;
				}
				if (module.isEnabled()) {
					context.fill(x, rowY, x + 2, rowY + ROW_HEIGHT, ON_GREEN);
				}

				context.drawTextWithShadow(
						this.textRenderer, module.getName(), x + 8, rowY + 4, module.isEnabled() ? TEXT : MUTED);

				String state = module.isEnabled() ? "ON" : "OFF";
				int stateWidth = this.textRenderer.getWidth(state);
				context.drawTextWithShadow(
						this.textRenderer, state, x + width - 8 - stateWidth, rowY + 4,
						module.isEnabled() ? ON_GREEN : MUTED);

				rowY += ROW_HEIGHT;
			}
		}

		border(context, x, y, width, HEADER_HEIGHT + bodyHeight);
		return hovered;
	}

	private void drawFooter(DrawContext context, Module hovered) {
		String message = hovered != null
				? hovered.getName() + " - " + hovered.getDescription()
				: "Drag a header to move it, click - to collapse, type to search.";
		context.drawTextWithShadow(
				this.textRenderer, message, 10, this.height - 14, hovered != null ? TEXT : MUTED);
	}

	private void field(DrawContext context, int x, int y, int width, boolean active) {
		context.fill(x, y, x + width, y + FIELD_HEIGHT, FIELD_BG);
		border(context, x, y, width, FIELD_HEIGHT, active ? ACCENT : BORDER);
	}

	private void border(DrawContext context, int x, int y, int width, int height) {
		border(context, x, y, width, height, BORDER);
	}

	private void border(DrawContext context, int x, int y, int width, int height, int color) {
		context.fill(x, y, x + width, y + 1, color);
		context.fill(x, y + height - 1, x + width, y + height, color);
		context.fill(x, y, x + 1, y + height, color);
		context.fill(x + width - 1, y, x + width, y + height, color);
	}

	// ----------------------------------------------------------------- layout

	private int searchX() {
		return this.width / 2 - SEARCH_WIDTH / 2;
	}

	private String bindLabel() {
		return this.binding ? "PRESS A KEY..." : "MENU KEY: " + BlueprintClientMod.menuKeyName().toUpperCase(Locale.ROOT);
	}

	private int bindWidth() {
		return this.textRenderer.getWidth(bindLabel()) + 16;
	}

	private int bindX(int bindWidth) {
		return this.width - 10 - bindWidth;
	}

	private int editWidth() {
		return this.textRenderer.getWidth(EDIT_LABEL) + 16;
	}

	private int editX() {
		return bindX(bindWidth()) - editWidth() - 8;
	}

	private int setupWidth() {
		return this.textRenderer.getWidth(SETUP_LABEL) + 16;
	}

	private int setupX() {
		return editX() - setupWidth() - 8;
	}

	private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	private int panelHeight(Category category, MenuLayout.Panel panel) {
		if (panel.collapsed) {
			return HEADER_HEIGHT;
		}
		return HEADER_HEIGHT + Modules.search(category, this.search).size() * ROW_HEIGHT + 4;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();

		if (this.binding) {
			this.binding = false;
			return true;
		}

		int bindWidth = bindWidth();
		if (inside(mouseX, mouseY, bindX(bindWidth), BAR_Y, bindWidth, FIELD_HEIGHT)) {
			this.binding = true;
			return true;
		}

		if (inside(mouseX, mouseY, editX(), BAR_Y, editWidth(), FIELD_HEIGHT)) {
			BlueprintConfig.save();
			this.client.setScreen(new HudEditorScreen(this));
			return true;
		}

		if (inside(mouseX, mouseY, setupX(), BAR_Y, setupWidth(), FIELD_HEIGHT)) {
			BlueprintConfig.save();
			this.client.setScreen(new FlipSettingsScreen(this));
			return true;
		}

		if (inside(mouseX, mouseY, searchX(), BAR_Y, SEARCH_WIDTH, FIELD_HEIGHT)) {
			if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
				this.search = "";
			}
			return true;
		}

		List<Category> order = MenuLayout.order();
		// Front to back: the panel drawn last is the one the pointer hits.
		for (int index = order.size() - 1; index >= 0; index--) {
			Category category = order.get(index);
			List<Module> modules = Modules.search(category, this.search);
			if (modules.isEmpty() && !this.search.isEmpty()) {
				continue;
			}

			MenuLayout.Panel panel = MenuLayout.get(category);
			int x = panel.x;
			int y = panel.y;
			int width = MenuLayout.PANEL_WIDTH;

			if (inside(mouseX, mouseY, x, y, width, HEADER_HEIGHT)) {
				MenuLayout.bringToFront(category);
				boolean onCollapseBox = mouseX >= x + width - COLLAPSE_ZONE;
				if (onCollapseBox || click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
					panel.collapsed = !panel.collapsed;
				} else {
					this.dragging = category;
					this.dragOffsetX = (int) (mouseX - x);
					this.dragOffsetY = (int) (mouseY - y);
				}
				return true;
			}

			if (!panel.collapsed) {
				int rowY = y + HEADER_HEIGHT + 2;
				for (Module module : modules) {
					if (inside(mouseX, mouseY, x, rowY, width, ROW_HEIGHT)) {
						module.toggle();
						MenuLayout.bringToFront(category);
						return true;
					}
					rowY += ROW_HEIGHT;
				}
			}
		}

		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (this.dragging != null) {
			MenuLayout.Panel panel = MenuLayout.get(this.dragging);
			int height = panelHeight(this.dragging, panel);
			panel.x = clamp((int) (click.x() - this.dragOffsetX), 0, this.width - MenuLayout.PANEL_WIDTH);
			panel.y = clamp((int) (click.y() - this.dragOffsetY), 0, Math.max(0, this.height - height));
			return true;
		}
		return super.mouseDragged(click, deltaX, deltaY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		this.dragging = null;
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int key = input.key();

		if (this.binding) {
			if (key != GLFW.GLFW_KEY_ESCAPE) {
				BlueprintClientMod.setMenuKey(key);
			}
			this.binding = false;
			return true;
		}

		if (key == GLFW.GLFW_KEY_BACKSPACE) {
			if (!this.search.isEmpty()) {
				this.search = this.search.substring(0, this.search.length() - 1);
			}
			return true;
		}

		if (key == GLFW.GLFW_KEY_ESCAPE) {
			if (!this.search.isEmpty()) {
				this.search = "";
			} else {
				close();
			}
			return true;
		}

		// Key bindings do not fire while a screen is open, so closing on the
		// menu key has to be handled here.
		if (key == BlueprintClientMod.menuKeyCode()) {
			close();
			return true;
		}

		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (this.search.length() < 24 && input.isValidChar()) {
			this.search = this.search + input.asString();
			return true;
		}
		return super.charTyped(input);
	}

	@Override
	public void close() {
		BlueprintConfig.save();
		super.close();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
