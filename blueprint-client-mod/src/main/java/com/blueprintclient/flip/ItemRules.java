package com.blueprintclient.flip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which items the flipper is allowed to touch, and at what price.
 *
 * <p>The maths decides whether a trade looks profitable; this decides whether
 * you want it made at all. Three settings, all lists of item names:
 *
 * <pre>
 *   flip.neverBuy   = dirt, cobblestone, spawn egg
 *   flip.onlyBuy    =                                (empty means everything)
 *   flip.priceRules = diamond block: 100k-5m, elytra: 2m-, netherite ingot: -20m
 * </pre>
 *
 * <p>A name is matched as a whole phrase, so {@code dirt} matches "Dirt" and
 * "64x Dirt" but not "Dirty Sword". A {@code *} stands for any run of
 * characters, so {@code * spawn egg} catches every spawn egg and {@code shulker*}
 * every shulker box.
 *
 * <p>Price rules are written {@code name: low-high}, either end optional, in
 * whatever shorthand the auction house uses - {@code 100k}, {@code 2.5m},
 * {@code 20m}. <b>They are the price of one of the item</b>, not of the stack,
 * so a rule reads the same whether the listing is a single or sixty four of
 * them. Limits on what a whole listing may cost are {@code flip.minListingPrice}
 * and {@code flip.maxSpendPerItem}.
 */
public final class ItemRules {
	/** What one of an item is allowed to cost. Either end may be open. */
	public record Range(long low, long high) {
		public boolean allows(long unitPrice) {
			if (low > 0 && unitPrice < low) {
				return false;
			}
			return high <= 0 || unitPrice <= high;
		}
	}

	private record Rule(String pattern, Range range) {
	}

	private static final ItemRules EMPTY = new ItemRules(List.of(), List.of(), List.of());

	private final List<String> never;
	private final List<String> only;
	private final List<Rule> ranges;

	private ItemRules(List<String> never, List<String> only, List<Rule> ranges) {
		this.never = never;
		this.only = only;
		this.ranges = ranges;
	}

	public static ItemRules none() {
		return EMPTY;
	}

	public static ItemRules parse(String neverBuy, String onlyBuy, String priceRules) {
		return new ItemRules(names(neverBuy), names(onlyBuy), rules(priceRules));
	}

	private static List<String> names(String text) {
		List<String> found = new ArrayList<>();
		if (text == null) {
			return found;
		}
		for (String part : text.split(",")) {
			String name = PriceText.normalize(part);
			if (!name.isEmpty()) {
				found.add(name);
			}
		}
		return found;
	}

	private static List<Rule> rules(String text) {
		List<Rule> found = new ArrayList<>();
		if (text == null) {
			return found;
		}
		for (String part : text.split(",")) {
			String entry = part.trim();
			int colon = entry.lastIndexOf(':');
			if (colon <= 0) {
				continue;
			}
			String pattern = PriceText.normalize(entry.substring(0, colon));
			Range range = range(entry.substring(colon + 1));
			if (!pattern.isEmpty() && range != null) {
				found.add(new Rule(pattern, range));
			}
		}
		return found;
	}

	/** "100k-5m", "2m-", "-20m", "5m" - the last meaning at most five million. */
	private static Range range(String text) {
		String value = text.trim();
		if (value.isEmpty()) {
			return null;
		}

		if (value.startsWith("-")) {
			// "-20m": anything up to twenty million.
			long ceiling = PriceText.parseAmount(value.substring(1));
			return ceiling > 0 ? new Range(0L, ceiling) : null;
		}

		int dash = value.indexOf('-');
		if (dash < 0) {
			// A single number is a ceiling: "bread: 5k" means at most five thousand.
			long only = PriceText.parseAmount(value);
			return only > 0 ? new Range(0L, only) : null;
		}

		long low = PriceText.parseAmount(value.substring(0, dash));
		long high = PriceText.parseAmount(value.substring(dash + 1));
		if (low <= 0 && high <= 0) {
			return null;
		}
		return new Range(Math.max(0L, low), Math.max(0L, high));
	}

	// --------------------------------------------------------------- asking

	public boolean isEmpty() {
		return never.isEmpty() && only.isEmpty() && ranges.isEmpty();
	}

	/** On the never-buy list. */
	public boolean blocked(String displayName) {
		return matchesAny(displayName, never);
	}

	/** Not on the only-buy list, when there is one. */
	public boolean unwanted(String displayName) {
		return !only.isEmpty() && !matchesAny(displayName, only);
	}

	/** The price one of this item is allowed to cost, or null when anything goes. */
	public Range rangeFor(String displayName) {
		String name = PriceText.normalize(displayName);
		for (Rule rule : ranges) {
			if (matches(name, rule.pattern())) {
				return rule.range();
			}
		}
		return null;
	}

	private static boolean matchesAny(String displayName, List<String> patterns) {
		String name = PriceText.normalize(displayName);
		for (String pattern : patterns) {
			if (matches(name, pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whole phrase, or a {@code *} glob.
	 *
	 * <p>Plain names match on word boundaries so that a rule about dirt says
	 * nothing about a Dirty Sword. Anything with a star in it is matched as
	 * written, with the star standing for any run of characters.
	 */
	static boolean matches(String name, String pattern) {
		if (name.isEmpty() || pattern.isEmpty()) {
			return false;
		}
		if (pattern.indexOf('*') >= 0) {
			return glob(name, pattern);
		}
		return name.equals(pattern)
				|| name.startsWith(pattern + " ")
				|| name.endsWith(" " + pattern)
				|| name.contains(" " + pattern + " ");
	}

	private static boolean glob(String name, String pattern) {
		String[] parts = pattern.split("\\*", -1);
		int at = 0;
		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];
			if (part.isEmpty()) {
				continue;
			}
			if (i == 0) {
				// Nothing before the first star means the name has to start here.
				if (!name.startsWith(part)) {
					return false;
				}
				at = part.length();
				continue;
			}
			int found = name.indexOf(part, at);
			if (found < 0) {
				return false;
			}
			at = found + part.length();
			if (i == parts.length - 1 && !pattern.endsWith("*") && at != name.length()) {
				return false;
			}
		}
		return true;
	}

	/** For the settings screen: a word on what these rules do. */
	public String describe() {
		return String.format(
				Locale.ROOT, "%d never, %d only, %d ranges", never.size(), only.size(), ranges.size());
	}
}
