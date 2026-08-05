package com.blueprintclient;

import com.blueprintclient.module.Category;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Where each panel sits in the click GUI, and which one is on top.
 *
 * <p>The screen object is thrown away every time the menu closes, so the
 * positions live here instead and are written to the config file.
 */
public final class MenuLayout {
	public static final int PANEL_WIDTH = 150;

	public static final class Panel {
		public int x;
		public int y;
		public boolean collapsed;
		public boolean placed;
	}

	private static final Map<Category, Panel> PANELS = new EnumMap<>(Category.class);
	private static final List<Category> ORDER = new ArrayList<>();

	static {
		for (Category category : Category.values()) {
			PANELS.put(category, new Panel());
			ORDER.add(category);
		}
	}

	private MenuLayout() {
	}

	public static Panel get(Category category) {
		return PANELS.get(category);
	}

	/** Back to front: the last category drawn sits on top. */
	public static List<Category> order() {
		return ORDER;
	}

	public static void bringToFront(Category category) {
		ORDER.remove(category);
		ORDER.add(category);
	}

	/** First run, or a screen too narrow for the saved spot: lay the panels out in a row. */
	public static void ensureDefaults(int screenWidth, int topY) {
		int x = 10;
		for (Category category : Category.values()) {
			Panel panel = PANELS.get(category);
			if (!panel.placed) {
				panel.x = x;
				panel.y = topY;
				panel.placed = true;
			}
			panel.x = Math.max(0, Math.min(panel.x, Math.max(0, screenWidth - PANEL_WIDTH)));
			x += PANEL_WIDTH + 8;
			if (x + PANEL_WIDTH > screenWidth) {
				x = 10;
				topY += 40;
			}
		}
	}
}
