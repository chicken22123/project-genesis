package com.blueprintclient;

import com.blueprintclient.flip.FlipSettings;
import com.blueprintclient.module.Category;
import com.blueprintclient.module.Module;
import com.blueprintclient.module.Modules;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Remembers which modules are on and where the panels were left.
 *
 * <p>A plain properties file, so it can be read and fixed by hand. The menu
 * key is not stored here: it is a real key binding, so Minecraft already keeps
 * it in options.txt and the vanilla Controls screen stays in sync.
 */
public final class BlueprintConfig {
	private static final String FILE_NAME = "blueprintclient.properties";

	private BlueprintConfig() {
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static void load() {
		Path file = path();
		if (!Files.isRegularFile(file)) {
			return;
		}

		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(file)) {
			properties.load(input);
		} catch (IOException exception) {
			return;
		}

		FlipSettings.get().load(properties);

		for (Module module : Modules.all()) {
			String value = properties.getProperty("module." + key(module.getName()));
			if (value != null) {
				// Silently: the game options these modules touch do not exist
				// yet this early in start up.
				module.setEnabledSilently(Boolean.parseBoolean(value));
			}

			if (module.isHudElement()) {
				Float x = readFloat(properties, "hud." + key(module.getName()) + ".x");
				Float y = readFloat(properties, "hud." + key(module.getName()) + ".y");
				if (x != null && y != null) {
					module.setHudPosition(x, y);
				}
			}
		}

		for (Category category : Category.values()) {
			MenuLayout.Panel panel = MenuLayout.get(category);
			String prefix = "panel." + category.name().toLowerCase(java.util.Locale.ROOT) + '.';
			Integer x = readInt(properties, prefix + 'x');
			Integer y = readInt(properties, prefix + 'y');
			if (x != null && y != null) {
				panel.x = x;
				panel.y = y;
				panel.placed = true;
			}
			panel.collapsed = Boolean.parseBoolean(properties.getProperty(prefix + "collapsed", "false"));
		}
	}

	public static void save() {
		Properties properties = new Properties();
		FlipSettings.get().save(properties);

		for (Module module : Modules.all()) {
			properties.setProperty("module." + key(module.getName()), Boolean.toString(module.isEnabled()));
			if (module.isHudElement()) {
				properties.setProperty(
						"hud." + key(module.getName()) + ".x", String.format(java.util.Locale.ROOT, "%.4f", module.getHudX()));
				properties.setProperty(
						"hud." + key(module.getName()) + ".y", String.format(java.util.Locale.ROOT, "%.4f", module.getHudY()));
			}
		}

		for (Category category : Category.values()) {
			MenuLayout.Panel panel = MenuLayout.get(category);
			String prefix = "panel." + category.name().toLowerCase(java.util.Locale.ROOT) + '.';
			properties.setProperty(prefix + 'x', Integer.toString(panel.x));
			properties.setProperty(prefix + 'y', Integer.toString(panel.y));
			properties.setProperty(prefix + "collapsed", Boolean.toString(panel.collapsed));
		}

		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream output = Files.newOutputStream(file)) {
				properties.store(output, "Blueprint Client");
			}
		} catch (IOException exception) {
			// A menu toggle is not worth crashing the game over.
		}
	}

	private static String key(String moduleName) {
		return moduleName.replace(' ', '_').toLowerCase(java.util.Locale.ROOT);
	}

	private static Float readFloat(Properties properties, String name) {
		String value = properties.getProperty(name);
		if (value == null) {
			return null;
		}
		try {
			return Float.valueOf(value.trim());
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static Integer readInt(Properties properties, String name) {
		String value = properties.getProperty(name);
		if (value == null) {
			return null;
		}
		try {
			return Integer.valueOf(value.trim());
		} catch (NumberFormatException exception) {
			return null;
		}
	}
}
