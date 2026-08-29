package com.blueprintclient.flip;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reading and writing of prices and item names, as plain text.
 *
 * <p>Kept apart from {@link AuctionParser} on purpose: everything here works on
 * strings and numbers alone, with no Minecraft types anywhere near it, so the
 * awkward half of the flipper - what counts as a price, what counts as the same
 * item - can be exercised on its own. {@code tools/FlipMathCheck.java} does
 * exactly that.
 */
public final class PriceText {
	/** Above this a "price" is a parsing mistake, not a listing. */
	public static final long MAX_PRICE = 1_000_000_000_000_000L;

	// "Buy it now: 1,234,567 coins", "Price: $12.5k", "BIN 900".
	private static final Pattern BUY_NOW = Pattern.compile(
			"(?i)\\b(?:buy it now|buy now|buy|bin|price|cost)\\b\\D{0,12}?([0-9][0-9,. ]*[kmbt]?)");
	// A line that is simply an amount of money, which is how a lot of auction
	// houses write it: "$1,250,000", "$4.2M".
	private static final Pattern BARE_MONEY = Pattern.compile("\\$\\s*([0-9][0-9,. ]*[kmbtKMBT]?)");
	// A running auction is not something we can flip: we would have to win it first.
	private static final Pattern BIDDING = Pattern.compile("(?i)\\b(?:starting bid|highest bid|current bid|bidder)\\b");
	private static final Pattern COUNT_PREFIX = Pattern.compile("^[0-9]+\\s*x\\s+");
	private static final Pattern PUNCTUATION = Pattern.compile("[^a-z0-9 +'-]");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	private static final Pattern RARITY = Pattern.compile("^[A-Z][A-Z ]{2,31}$");
	// "Seller: Notch", "Sold by Notch", "Listed by Notch".
	private static final Pattern SELLER = Pattern.compile(
			"(?i)\\b(?:seller|sold by|listed by|owner)\\b\\s*:?\\s*([A-Za-z0-9_]{3,16})");

	private PriceText() {
	}

	// ------------------------------------------------------------- item name

	/**
	 * The name two listings have to share to be worth comparing.
	 *
	 * <p>The rarity line is folded in because plenty of auction houses sell very
	 * different things under one name - a common and a legendary of the same
	 * item are not the same market, and pricing one off the other is how a
	 * flipper loses money.
	 */
	public static String itemKey(String displayName, List<String> lore, String identity) {
		String name = normalize(displayName);
		if (name.isEmpty()) {
			return "";
		}
		StringBuilder key = new StringBuilder(name);
		String rarity = rarity(lore);
		if (!rarity.isEmpty()) {
			key.append('#').append(rarity);
		}
		if (identity != null && !identity.isEmpty()) {
			key.append('#').append(identity);
		}
		return key.toString();
	}

	/** The same, for an item with nothing to tell apart beyond its name. */
	public static String itemKey(String displayName, List<String> lore) {
		return itemKey(displayName, lore, "");
	}

	/**
	 * A short, stable stamp for whatever makes an item itself.
	 *
	 * <p>Everything that changes what a thing is worth but not what it is
	 * called - the enchantments on a sword, what is inside a shulker box - goes
	 * in here. Pricing a Sharpness V sword off a plain one is how a flipper
	 * hands its money to somebody else.
	 */
	public static String identity(String... parts) {
		StringBuilder joined = new StringBuilder();
		for (String part : parts) {
			if (part != null && !part.isEmpty()) {
				joined.append(part).append('|');
			}
		}
		if (joined.length() == 0) {
			return "";
		}
		return Integer.toHexString(joined.toString().hashCode());
	}

	/** Lower case, no counts, no decoration: "6x * Aspect of the End" becomes "aspect of the end". */
	public static String normalize(String display) {
		String value = display.toLowerCase(Locale.ROOT).trim();
		value = COUNT_PREFIX.matcher(value).replaceFirst("");
		value = PUNCTUATION.matcher(value).replaceAll(" ");
		return SPACES.matcher(value).replaceAll(" ").trim();
	}

	/** The shouted line most auction houses put last, e.g. "LEGENDARY SWORD". */
	public static String rarity(List<String> lore) {
		for (int i = lore.size() - 1; i >= 0; i--) {
			String line = lore.get(i);
			if (RARITY.matcher(line).matches()) {
				return normalize(line);
			}
		}
		return "";
	}

	/**
	 * Who has this listed, or empty when the auction house does not say.
	 *
	 * <p>It matters because one person listing the same thing ten times is one
	 * opinion about the price, and ten people listing it once each is a market.
	 */
	public static String seller(List<String> lore) {
		for (String line : lore) {
			Matcher matcher = SELLER.matcher(line);
			if (matcher.find()) {
				return matcher.group(1).toLowerCase(Locale.ROOT);
			}
		}
		return "";
	}

	// ----------------------------------------------------------------- price

	/**
	 * The buy it now price in a block of lore, or -1 when there is not one.
	 *
	 * <p>Items being bid on are skipped when {@code binOnly}: only a fixed price
	 * can be worked out in advance, and only a fixed price can be bought in one
	 * click.
	 */
	public static long buyItNowPrice(List<String> lore, boolean binOnly) {
		if (lore.isEmpty()) {
			return -1L;
		}

		boolean bidding = false;
		for (String line : lore) {
			if (BIDDING.matcher(line).find()) {
				bidding = true;
				continue;
			}
			long price = priceIn(line);
			if (price > 0) {
				return price;
			}
		}

		// Lines that are nothing but money, with no wording at all.
		for (String line : lore) {
			if (binOnly && BIDDING.matcher(line).find()) {
				continue;
			}
			Matcher matcher = BARE_MONEY.matcher(line);
			while (matcher.find()) {
				long amount = parseAmount(matcher.group(1));
				if (amount > 0) {
					return amount;
				}
			}
		}

		if (bidding && binOnly) {
			return -1L;
		}
		// The wording and the number sometimes land on separate lines.
		return priceIn(String.join(" ", lore));
	}

	private static long priceIn(String line) {
		Matcher matcher = BUY_NOW.matcher(line);
		while (matcher.find()) {
			long amount = parseAmount(matcher.group(1));
			if (amount > 0) {
				return amount;
			}
		}
		return -1L;
	}

	/**
	 * "1,234,567", "12.5k" and "$3.2M" all become a plain number of coins.
	 *
	 * @return the amount, or -1 when the text is not a number we trust
	 */
	public static long parseAmount(String text) {
		String value = text.toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").replace(" ", "").trim();
		if (value.isEmpty()) {
			return -1L;
		}

		double multiplier = switch (value.charAt(value.length() - 1)) {
			case 'k' -> 1_000.0;
			case 'm' -> 1_000_000.0;
			case 'b' -> 1_000_000_000.0;
			case 't' -> 1_000_000_000_000.0;
			default -> 1.0;
		};
		if (multiplier > 1.0) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.endsWith(".")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.isEmpty()) {
			return -1L;
		}

		try {
			double amount = Double.parseDouble(value) * multiplier;
			if (amount < 1.0 || amount > MAX_PRICE) {
				return -1L;
			}
			return Math.round(amount);
		} catch (NumberFormatException exception) {
			return -1L;
		}
	}

	/** Whether a name is one of the buttons named in the settings, e.g. "Confirm". */
	public static boolean isButton(String displayName, List<String> names) {
		String name = displayName.toLowerCase(Locale.ROOT).trim();
		if (name.isEmpty()) {
			return false;
		}
		for (String needle : names) {
			if (!needle.isEmpty() && name.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	/** Money, short enough for a HUD line: 1234567 becomes "1.23m". */
	public static String coins(long amount) {
		long absolute = Math.abs(amount);
		if (absolute >= 1_000_000_000_000L) {
			return String.format(Locale.ROOT, "%.2ft", amount / 1_000_000_000_000.0);
		}
		if (absolute >= 1_000_000_000L) {
			return String.format(Locale.ROOT, "%.2fb", amount / 1_000_000_000.0);
		}
		if (absolute >= 1_000_000L) {
			return String.format(Locale.ROOT, "%.2fm", amount / 1_000_000.0);
		}
		if (absolute >= 1_000L) {
			return String.format(Locale.ROOT, "%.1fk", amount / 1_000.0);
		}
		return Long.toString(amount);
	}

	/** The same with the server's currency in front: "$1.23m". */
	public static String money(long amount, String symbol) {
		return amount < 0 ? "-" + symbol + coins(-amount) : symbol + coins(amount);
	}
}
