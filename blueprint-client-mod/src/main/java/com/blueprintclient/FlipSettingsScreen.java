package com.blueprintclient;

import com.blueprintclient.flip.FlipSettings;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The auction flipper's numbers, without opening a text editor.
 *
 * <p>Two columns: what it may spend and what counts as a good enough flip on
 * the left, what your server calls things and how fast to click on the right.
 * Click a row to type a new value, right click one to put it back to the
 * default.
 */
public class FlipSettingsScreen extends Screen {
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
	private static final int WARN = 0xFFFF9E7C;

	private static final int ROW_HEIGHT = 13;
	private static final int HEADER_HEIGHT = 16;
	private static final int BUTTON_HEIGHT = 16;
	private static final int COLUMN_GAP = 10;
	private static final int MAX_COLUMN = 230;

	/** How a row's value is written down and read back. */
	private enum Kind {
		TEXT,
		NUMBER,
		PERCENT,
		TOGGLE
	}

	/** Setting a value can fail - "twelve" is not a number of coins. */
	private interface Setter {
		boolean set(String value);
	}

	private record Row(String label, String help, Kind kind, Supplier<String> get, Setter setter) {
	}

	private final Screen parent;
	private final List<Row> money;
	private final List<Row> server;
	private final List<Row> defaults;

	private int editing = -1;
	private String buffer = "";
	private boolean rejected;

	public FlipSettingsScreen(Screen parent) {
		super(Text.literal("Flipper Setup"));
		this.parent = parent;
		FlipSettings live = FlipSettings.get();
		this.money = moneyRows(live);
		this.server = serverRows(live);
		// A second, untouched set of settings, purely so a row knows what it
		// looked like before anyone changed it.
		FlipSettings fresh = FlipSettings.defaults();
		this.defaults = new ArrayList<>();
		this.defaults.addAll(moneyRows(fresh));
		this.defaults.addAll(serverRows(fresh));
	}

	// ------------------------------------------------------------------ rows

	private static List<Row> moneyRows(FlipSettings settings) {
		List<Row> rows = new ArrayList<>();
		rows.add(optional("Currency", "What the server puts in front of its money.",
				() -> settings.currency, value -> settings.currency = value));
		rows.add(number("Max per listing", "Never click a listing dearer than this. Stacks count as one listing.",
				() -> settings.maxSpendPerItem, value -> settings.maxSpendPerItem = value));
		rows.add(number("Session budget", "Stop once this much has been spent. 0 for no limit.",
				() -> settings.sessionBudget, value -> settings.sessionBudget = value));
		rows.add(number("Stop after flips", "Stop after this many flips. 0 for no limit.",
				() -> (long) settings.stopAfterFlips, value -> settings.stopAfterFlips = (int) value));
		rows.add(number("Min profit", "Coins of profit needed before a flip is worth the clicks.",
				() -> settings.minProfit, value -> settings.minProfit = value));
		rows.add(percent("Min margin", "Profit as a share of what the item cost.",
				() -> settings.minMargin, value -> settings.minMargin = value));
		rows.add(percent("Min confidence", "How sure the price model has to be before it acts.",
				() -> settings.minConfidence, value -> settings.minConfidence = value));
		rows.add(number("Min sightings", "Times an item must be seen before it is priced at all.",
				() -> (long) settings.minSamples, value -> settings.minSamples = (int) value));
		rows.add(percent("Max spread", "Refuse items whose price is all over the place.",
				() -> settings.maxDispersion, value -> settings.maxDispersion = value));
		rows.add(percent("Suspicious margin", "Above this a bargain needs far more evidence.",
				() -> settings.suspiciousMargin, value -> settings.suspiciousMargin = value));
		rows.add(number("Trusted sightings", "Sightings that make a huge margin believable.",
				() -> (long) settings.trustedSamples, value -> settings.trustedSamples = (int) value));
		rows.add(percent("Sale tax", "The cut the auction house takes when something sells.",
				() -> settings.saleTax, value -> settings.saleTax = value));
		rows.add(percent("Undercut by", "How far under the cheapest rival to list.",
				() -> settings.undercut, value -> settings.undercut = value));
		rows.add(toggle("Fixed price only", "Ignore items that are being bid on.",
				() -> settings.binOnly, value -> settings.binOnly = value));
		return rows;
	}

	private static List<Row> serverRows(FlipSettings settings) {
		List<Row> rows = new ArrayList<>();
		rows.add(text("Browse command", "The command that opens the auction house, without the slash.",
				() -> settings.browseCommand, value -> settings.browseCommand = value));
		rows.add(text("Sell command", "How an item in hand is listed. %price% is filled in.",
				() -> settings.sellCommand, value -> settings.sellCommand = value));
		rows.add(optional("Sell menu chain", "Menus to walk when there is no sell command. Empty uses the command.",
				() -> settings.sellFlow, value -> settings.sellFlow = value));
		rows.add(words("Browser titles", "Window titles that mean the auction house browser.",
				() -> settings.browseTitles, value -> settings.browseTitles = value));
		rows.add(words("Buy buttons", "Item names the flipper may click to buy something.",
				() -> settings.buyButtons, value -> settings.buyButtons = value));
		rows.add(words("Sell buttons", "Item names that finish a listing.",
				() -> settings.sellButtons, value -> settings.sellButtons = value));
		rows.add(words("Refresh buttons", "Names that reload the page. Any anvil counts too.",
				() -> settings.refreshButtons, value -> settings.refreshButtons = value));
		rows.add(words("Bought wording", "Chat wording that means the purchase worked.",
				() -> settings.boughtMessages, value -> settings.boughtMessages = value));
		rows.add(words("Failed wording", "Chat wording that means it did not.",
				() -> settings.buyFailedMessages, value -> settings.buyFailedMessages = value));
		rows.add(words("Listed wording", "Chat wording that means the relist worked.",
				() -> settings.listedMessages, value -> settings.listedMessages = value));
		rows.add(number("Click delay", "Milliseconds between clicks; each one is a round trip.",
				() -> (long) settings.actionDelayMs, value -> settings.actionDelayMs = (int) value));
		rows.add(number("Click jitter", "Random milliseconds added to every pause.",
				() -> (long) settings.actionJitterMs, value -> settings.actionJitterMs = (int) value));
		rows.add(number("Reload delay", "Milliseconds between page reloads.",
				() -> (long) settings.refreshDelayMs, value -> settings.refreshDelayMs = (int) value));
		rows.add(number("Reloads a minute", "Hard cap on how often the page is reloaded.",
				() -> (long) settings.maxRefreshesPerMinute, value -> settings.maxRefreshesPerMinute = (int) value));
		return rows;
	}

	// ------------------------------------------------------------ row makers

	private interface LongSetter {
		void set(long value);
	}

	private interface DecimalSetter {
		void set(double value);
	}

	private interface FlagSetter {
		void set(boolean value);
	}

	private interface WordsSetter {
		void set(List<String> value);
	}

	private static Row number(String label, String help, Supplier<Long> get, LongSetter set) {
		return new Row(label, help, Kind.NUMBER, () -> Long.toString(get.get()), value -> {
			try {
				long parsed = Long.parseLong(value.replace(",", "").trim());
				if (parsed < 0) {
					return false;
				}
				set.set(parsed);
				return true;
			} catch (NumberFormatException exception) {
				return false;
			}
		});
	}

	private static Row percent(String label, String help, Supplier<Double> get, DecimalSetter set) {
		return new Row(label, help, Kind.PERCENT, () -> asPercent(get.get()), value -> {
			try {
				double parsed = Double.parseDouble(value.replace("%", "").trim());
				if (parsed < 0.0 || parsed > 100_000.0) {
					return false;
				}
				set.set(parsed / 100.0);
				return true;
			} catch (NumberFormatException exception) {
				return false;
			}
		});
	}

	private static Row toggle(String label, String help, Supplier<Boolean> get, FlagSetter set) {
		return new Row(label, help, Kind.TOGGLE, () -> get.get() ? "on" : "off", value -> {
			set.set(Boolean.parseBoolean(value) || "on".equalsIgnoreCase(value));
			return true;
		});
	}

	private static Row text(String label, String help, Supplier<String> get, java.util.function.Consumer<String> set) {
		return new Row(label, help, Kind.TEXT, get, value -> {
			if (value.isBlank()) {
				return false;
			}
			set.accept(value.trim());
			return true;
		});
	}

	/** Like {@link #text}, but an empty value means something - "do it the other way". */
	private static Row optional(
			String label, String help, Supplier<String> get, java.util.function.Consumer<String> set) {
		return new Row(label, help, Kind.TEXT, () -> get.get().isEmpty() ? "(none)" : get.get(), value -> {
			String trimmed = value.trim();
			set.accept("(none)".equals(trimmed) ? "" : trimmed);
			return true;
		});
	}

	private static Row words(String label, String help, Supplier<List<String>> get, WordsSetter set) {
		return new Row(label, help, Kind.TEXT, () -> String.join(", ", get.get()), value -> {
			List<String> parsed = new ArrayList<>();
			for (String part : value.split(",")) {
				String word = part.trim().toLowerCase(Locale.ROOT);
				if (!word.isEmpty()) {
					parsed.add(word);
				}
			}
			if (parsed.isEmpty()) {
				return false;
			}
			set.set(parsed);
			return true;
		});
	}

	private static String asPercent(double value) {
		double shown = value * 100.0;
		return shown == Math.rint(shown)
				? String.format(Locale.ROOT, "%.0f%%", shown)
				: String.format(Locale.ROOT, "%.1f%%", shown);
	}

	// ------------------------------------------------------------- rendering

	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fillGradient(0, 0, this.width, this.height, BACKDROP_TOP, BACKDROP_BOTTOM);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);

		context.drawTextWithShadow(this.textRenderer, "FLIPPER", 10, 10, ACCENT);
		int wordmark = this.textRenderer.getWidth("FLIPPER ");
		context.drawTextWithShadow(this.textRenderer, "SETUP", 10 + wordmark, 10, ACCENT_LIGHT);

		button(context, doneX(), 8, doneWidth(), "DONE", mouseX, mouseY);
		button(context, resetX(), 8, resetWidth(), "RESET ALL", mouseX, mouseY);

		Row described = drawColumn(context, money, 0, "MONEY AND THRESHOLDS", mouseX, mouseY);
		Row other = drawColumn(context, server, 1, "SERVER WORDING AND PACING", mouseX, mouseY);
		if (other != null) {
			described = other;
		}
		if (editing >= 0) {
			described = row(editing);
		}

		String footer = described != null
				? described.label() + " - " + described.help()
				: "Click a row to change it, right click to put it back to the default.";
		context.drawTextWithShadow(this.textRenderer, footer, 10, this.height - 24, described != null ? TEXT : MUTED);
		context.drawTextWithShadow(
				this.textRenderer,
				editing >= 0 ? "Enter to accept, Escape to leave it alone." : "Anything changed here is saved on the way out.",
				10,
				this.height - 13,
				rejected ? WARN : MUTED);
	}

	/** Draws one column and returns the row under the pointer, if any. */
	private Row drawColumn(DrawContext context, List<Row> rows, int column, String title, int mouseX, int mouseY) {
		int x = columnX(column);
		int width = columnWidth();
		int y = topY();

		context.fill(x, y, x + width, y + HEADER_HEIGHT, HEADER_BG);
		context.fill(x, y, x + 3, y + HEADER_HEIGHT, ACCENT);
		context.drawTextWithShadow(this.textRenderer, title, x + 10, y + 4, ACCENT_LIGHT);
		context.fill(x, y + HEADER_HEIGHT, x + width, y + HEADER_HEIGHT + rows.size() * ROW_HEIGHT + 4, PANEL_BG);

		Row hovered = null;
		int rowY = y + HEADER_HEIGHT + 2;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int index = column == 0 ? i : money.size() + i;
			boolean over = inside(mouseX, mouseY, x, rowY, width, ROW_HEIGHT);
			if (over) {
				context.fill(x, rowY, x + width, rowY + ROW_HEIGHT, ROW_HOVER);
				hovered = row;
			}

			boolean active = editing == index;
			context.drawTextWithShadow(this.textRenderer, row.label(), x + 8, rowY + 3, active ? ACCENT_LIGHT : MUTED);

			String value = active ? buffer + "_" : row.get().get();
			int room = width - 16 - this.textRenderer.getWidth(row.label());
			value = fit(value, room);
			context.drawTextWithShadow(
					this.textRenderer,
					value,
					x + width - 8 - this.textRenderer.getWidth(value),
					rowY + 3,
					active ? (rejected ? WARN : TEXT) : ACCENT_LIGHT);

			rowY += ROW_HEIGHT;
		}

		border(context, x, y, width, HEADER_HEIGHT + rows.size() * ROW_HEIGHT + 4, BORDER);
		return hovered;
	}

	/** Trims a value to the room it has, keeping the end - the part being typed. */
	private String fit(String value, int room) {
		if (room <= 0 || this.textRenderer.getWidth(value) <= room) {
			return value;
		}
		String trimmed = value;
		while (!trimmed.isEmpty() && this.textRenderer.getWidth("…" + trimmed) > room) {
			trimmed = trimmed.substring(1);
		}
		return "…" + trimmed;
	}

	private void button(DrawContext context, int x, int y, int width, String label, int mouseX, int mouseY) {
		boolean hovered = inside(mouseX, mouseY, x, y, width, BUTTON_HEIGHT);
		context.fill(x, y, x + width, y + BUTTON_HEIGHT, hovered ? ACCENT : HEADER_BG);
		border(context, x, y, width, BUTTON_HEIGHT, hovered ? ACCENT_LIGHT : BORDER);
		context.drawTextWithShadow(this.textRenderer, label, x + 8, y + 4, hovered ? 0xFF050D1A : TEXT);
	}

	private void border(DrawContext context, int x, int y, int width, int height, int color) {
		context.fill(x, y, x + width, y + 1, color);
		context.fill(x, y + height - 1, x + width, y + height, color);
		context.fill(x, y, x + 1, y + height, color);
		context.fill(x + width - 1, y, x + width, y + height, color);
	}

	// ---------------------------------------------------------------- layout

	private int columnWidth() {
		return Math.min(MAX_COLUMN, (this.width - 20 - COLUMN_GAP) / 2);
	}

	private int columnX(int column) {
		int total = columnWidth() * 2 + COLUMN_GAP;
		return (this.width - total) / 2 + column * (columnWidth() + COLUMN_GAP);
	}

	private int topY() {
		return 30;
	}

	private int doneWidth() {
		return this.textRenderer.getWidth("DONE") + 16;
	}

	private int doneX() {
		return this.width - 10 - doneWidth();
	}

	private int resetWidth() {
		return this.textRenderer.getWidth("RESET ALL") + 16;
	}

	private int resetX() {
		return doneX() - resetWidth() - 8;
	}

	private Row row(int index) {
		return index < money.size() ? money.get(index) : server.get(index - money.size());
	}

	private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	// ----------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mouseX = click.x();
		double mouseY = click.y();

		if (inside(mouseX, mouseY, doneX(), 8, doneWidth(), BUTTON_HEIGHT)) {
			close();
			return true;
		}
		if (inside(mouseX, mouseY, resetX(), 8, resetWidth(), BUTTON_HEIGHT)) {
			for (int index = 0; index < money.size() + server.size(); index++) {
				row(index).setter().set(defaults.get(index).get().get());
			}
			stopEditing();
			return true;
		}

		for (int column = 0; column < 2; column++) {
			List<Row> rows = column == 0 ? money : server;
			int x = columnX(column);
			int rowY = topY() + HEADER_HEIGHT + 2;
			for (int i = 0; i < rows.size(); i++) {
				if (inside(mouseX, mouseY, x, rowY, columnWidth(), ROW_HEIGHT)) {
					int index = column == 0 ? i : money.size() + i;
					if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
						row(index).setter().set(defaults.get(index).get().get());
						stopEditing();
					} else if (rows.get(i).kind() == Kind.TOGGLE) {
						rows.get(i).setter().set("on".equals(rows.get(i).get().get()) ? "off" : "on");
						stopEditing();
					} else {
						editing = index;
						buffer = rows.get(i).get().get();
						rejected = false;
					}
					return true;
				}
				rowY += ROW_HEIGHT;
			}
		}

		stopEditing();
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		int key = input.key();

		if (editing >= 0) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				stopEditing();
				return true;
			}
			if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
				if (row(editing).setter().set(buffer)) {
					stopEditing();
				} else {
					// Left in the box rather than thrown away, so a typo can be
					// fixed rather than retyped.
					rejected = true;
				}
				return true;
			}
			if (key == GLFW.GLFW_KEY_BACKSPACE) {
				if (!buffer.isEmpty()) {
					buffer = buffer.substring(0, buffer.length() - 1);
					rejected = false;
				}
				return true;
			}
			return true;
		}

		if (key == GLFW.GLFW_KEY_ESCAPE) {
			close();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean charTyped(CharInput input) {
		if (editing >= 0 && buffer.length() < 200 && input.isValidChar()) {
			buffer = buffer + input.asString();
			rejected = false;
			return true;
		}
		return super.charTyped(input);
	}

	private void stopEditing() {
		editing = -1;
		buffer = "";
		rejected = false;
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
}
