package com.blueprintclient.flip;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an auction house GUI: what an item is, and what it costs.
 *
 * <p>Auction houses are just chests full of items whose lore says the price, so
 * everything here works off the display name and the lore lines. Nothing is
 * server specific beyond the wording the patterns look for, and that wording
 * lives in {@link FlipSettings} where it can be changed.
 */
public final class AuctionParser {
	/** Above this a "price" is a parsing mistake, not a listing. */
	private static final long MAX_PRICE = 1_000_000_000_000L;

	// "Buy it now: 1,234,567 coins", "Price: $12.5k", "BIN 900".
	private static final Pattern BUY_NOW = Pattern.compile(
			"(?i)\\b(?:buy it now|buy now|buy|bin|price|cost)\\b\\D{0,12}?([0-9][0-9,. ]*[kmb]?)");
	// A running auction is not something we can flip: we would have to win it first.
	private static final Pattern BIDDING = Pattern.compile("(?i)\\b(?:starting bid|highest bid|current bid|bidder)\\b");
	private static final Pattern COUNT_PREFIX = Pattern.compile("^[0-9]+\\s*x\\s+");
	private static final Pattern PUNCTUATION = Pattern.compile("[^a-z0-9 +'-]");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	private static final Pattern RARITY = Pattern.compile("^[A-Z][A-Z ]{2,31}$");

	private AuctionParser() {
	}

	// ------------------------------------------------------------------ text

	/** A {@link Text} with the colour codes taken back out. */
	public static String plain(Text text) {
		if (text == null) {
			return "";
		}
		String stripped = Formatting.strip(text.getString());
		return stripped == null ? "" : stripped.trim();
	}

	public static List<String> lore(ItemStack stack) {
		List<String> lines = new ArrayList<>();
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore != null) {
			for (Text line : lore.lines()) {
				lines.add(plain(line));
			}
		}
		return lines;
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
	public static String itemKey(ItemStack stack) {
		String name = normalize(plain(stack.getName()));
		String rarity = rarity(stack);
		if (name.isEmpty()) {
			return "";
		}
		return rarity.isEmpty() ? name : name + '#' + rarity;
	}

	/** Lower case, no counts, no decoration: "6x ✪ Aspect of the End" becomes "aspect of the end". */
	public static String normalize(String display) {
		String value = display.toLowerCase(Locale.ROOT).trim();
		value = COUNT_PREFIX.matcher(value).replaceFirst("");
		value = PUNCTUATION.matcher(value).replaceAll(" ");
		return SPACES.matcher(value).replaceAll(" ").trim();
	}

	/** The shouted line most auction houses put last, e.g. "LEGENDARY SWORD". */
	private static String rarity(ItemStack stack) {
		List<String> lines = lore(stack);
		for (int i = lines.size() - 1; i >= 0; i--) {
			String line = lines.get(i);
			if (RARITY.matcher(line).matches()) {
				return normalize(line);
			}
		}
		return "";
	}

	// ----------------------------------------------------------------- price

	/**
	 * The buy it now price on a listing, or -1 when there is not one.
	 *
	 * <p>Items that are being bid on are skipped: only a fixed price can be
	 * worked out in advance, and only a fixed price can be bought in one click.
	 */
	public static long buyItNowPrice(ItemStack stack, boolean binOnly) {
		List<String> lines = lore(stack);
		if (lines.isEmpty()) {
			return -1L;
		}

		boolean bidding = false;
		for (String line : lines) {
			if (BIDDING.matcher(line).find()) {
				bidding = true;
				continue;
			}
			long price = priceIn(line);
			if (price > 0) {
				return price;
			}
		}

		if (bidding && binOnly) {
			return -1L;
		}
		// The wording and the number sometimes land on separate lines.
		return priceIn(String.join(" ", lines));
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

		double multiplier = 1.0;
		char last = value.charAt(value.length() - 1);
		if (last == 'k' || last == 'm' || last == 'b') {
			multiplier = last == 'k' ? 1_000.0 : last == 'm' ? 1_000_000.0 : 1_000_000_000.0;
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

	// --------------------------------------------------------------- buttons

	/** Whether an item is one of the buttons named in the settings, e.g. "Confirm". */
	public static boolean isButton(ItemStack stack, List<String> names) {
		String name = plain(stack.getName()).toLowerCase(Locale.ROOT);
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

	/** Coins, short enough for a HUD line: 1234567 becomes "1.23m". */
	public static String coins(long amount) {
		long absolute = Math.abs(amount);
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
}
