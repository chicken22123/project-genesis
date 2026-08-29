package com.blueprintclient.flip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Every number and every bit of wording the flipper depends on.
 *
 * <p>Auction houses differ: the command that opens one, what the buy button is
 * called, what the server says in chat when a sale goes through. All of that is
 * here rather than buried in the state machine, so a different server means
 * editing {@code blueprintclient.properties}, not the mod.
 *
 * <p>The defaults are written for the common chest based auction house: a
 * {@code /ah} browser with an anvil that reloads the page.
 */
public final class FlipSettings {
	private static final FlipSettings INSTANCE = new FlipSettings();

	/** How the auction house is opened, without the leading slash. */
	public String browseCommand = "ah";
	/** How an item in hand is listed. {@code %price%} is replaced with the asking price. */
	public String sellCommand = "ah sell %price%";
	/**
	 * The menu chain for auction houses with no sell command, e.g.
	 * {@code button:manage auctions, button:create auction, item, price,
	 * button:confirm}. Empty means use {@link #sellCommand}. See {@link SellFlow}.
	 */
	public String sellFlow = "";

	/** What the server puts in front of its money. */
	public String currency = "$";

	/** Screen titles that mean "this is the browser", lower case. */
	public List<String> browseTitles = list("auction", "ah browser", "market");
	/** Item names that buy or confirm a purchase, in the order they are usually met. */
	public List<String> buyButtons = list("buy it now", "buy item right now", "buy", "confirm", "purchase", "accept");
	/** Item names that finish a listing. */
	public List<String> sellButtons = list("confirm", "create auction", "list item", "accept", "yes");
	/** Item names, other than an anvil, that reload the page. */
	public List<String> refreshButtons = list("refresh", "reload", "update");

	/** Chat wording that means the purchase went through, or did not. */
	public List<String> boughtMessages =
			list("you purchased", "you bought", "you have bought", "purchased for", "you claimed");
	public List<String> buyFailedMessages = list(
			"not enough coins",
			"not enough money",
			"cannot afford",
			"can't afford",
			"already been sold",
			"no longer available",
			"has been sold");
	/** Wording that means the sell command was refused: a limit, a bad price, an empty hand. */
	public List<String> listFailedMessages = list(
			"too many",
			"maximum amount",
			"auction limit",
			"invalid price",
			"cannot sell",
			"can't sell",
			"must hold",
			"hold an item",
			"nothing in your hand",
			"not allowed to sell",
			"blacklisted");

	public List<String> listedMessages =
			list("auction started", "you listed", "you have listed", "put up for auction", "listed for", "now selling");

	/** Items never to buy, whatever the maths says. A whole phrase, or a {@code *} glob. */
	public String neverBuy = "dirt, cobblestone";
	/** When set, the only items to buy. Empty means everything else is fair game. */
	public String onlyBuy = "";
	/** Per item price ranges: {@code diamond block: 100k-5m, elytra: 2m-}. The price of one. */
	public String priceRules = "";

	/** Money limits. Nothing outside these is ever clicked. */
	public long minListingPrice = 0L;
	/**
	 * The most one listing may cost. Elytras and netherite are not cheap, so
	 * this starts high enough to buy them; 0 removes the ceiling entirely and
	 * leaves {@link #sessionBudget} as the only limit.
	 */
	public long maxSpendPerItem = 250_000_000L;
	public long sessionBudget = 1_000_000_000L;
	public int stopAfterFlips = 0;

	/** What counts as worth buying. */
	public long minProfit = 100_000L;
	public double minMargin = 0.12;
	public double minConfidence = 0.30;
	public int minSamples = 3;
	/** Different people who must have listed it. One person can list a lie ten times. */
	public int minSellers = 2;
	/** Copies that must be on the page to resell against. */
	public int minDepth = 2;
	/** Refuse items whose listings never come and go: nobody is paying that price. */
	public boolean requireChurn = true;
	/** How hard a market that never moves is disbelieved. */
	public double churnHaircut = 0.25;
	/** Ignore anything worth less than this each; 0 to consider everything. */
	public long minUnitValue = 0L;
	public double maxDispersion = 0.35;
	/** A margin past this is a trap, not a bargain: same name, different item. */
	public double suspiciousMargin = 3.0;
	public int trustedSamples = 12;

	/** The cut the auction house takes, and how far under the market we list. */
	public double saleTax = 0.05;
	public double undercut = 0.03;

	/** Only fixed price listings; a running auction cannot be bought outright. */
	public boolean binOnly = true;

	/** Pacing, in milliseconds. Every click needs a round trip to the server. */
	public int actionDelayMs = 400;
	public int actionJitterMs = 250;
	public int refreshDelayMs = 900;
	public int maxRefreshesPerMinute = 40;

	private ItemRules parsedRules;
	private String parsedFrom;

	private FlipSettings() {
	}

	public static FlipSettings get() {
		return INSTANCE;
	}

	/** A fresh set of factory settings, so a screen can offer "put it back". */
	public static FlipSettings defaults() {
		return new FlipSettings();
	}

	// ------------------------------------------------------------------ file

	public void load(Properties properties) {
		browseCommand = string(properties, "flip.browseCommand", browseCommand);
		currency = properties.getProperty("flip.currency", currency);
		sellCommand = string(properties, "flip.sellCommand", sellCommand);
		sellFlow = properties.getProperty("flip.sellFlow", sellFlow).trim();

		browseTitles = words(properties, "flip.browseTitles", browseTitles);
		buyButtons = words(properties, "flip.buyButtons", buyButtons);
		sellButtons = words(properties, "flip.sellButtons", sellButtons);
		refreshButtons = words(properties, "flip.refreshButtons", refreshButtons);
		boughtMessages = words(properties, "flip.boughtMessages", boughtMessages);
		buyFailedMessages = words(properties, "flip.buyFailedMessages", buyFailedMessages);
		listedMessages = words(properties, "flip.listedMessages", listedMessages);
		listFailedMessages = words(properties, "flip.listFailedMessages", listFailedMessages);

		neverBuy = properties.getProperty("flip.neverBuy", neverBuy).trim();
		onlyBuy = properties.getProperty("flip.onlyBuy", onlyBuy).trim();
		priceRules = properties.getProperty("flip.priceRules", priceRules).trim();
		minListingPrice = number(properties, "flip.minListingPrice", minListingPrice);
		maxSpendPerItem = number(properties, "flip.maxSpendPerItem", maxSpendPerItem);
		sessionBudget = number(properties, "flip.sessionBudget", sessionBudget);
		stopAfterFlips = (int) number(properties, "flip.stopAfterFlips", stopAfterFlips);

		minProfit = number(properties, "flip.minProfit", minProfit);
		minMargin = decimal(properties, "flip.minMargin", minMargin);
		minConfidence = decimal(properties, "flip.minConfidence", minConfidence);
		minSamples = (int) number(properties, "flip.minSamples", minSamples);
		minSellers = (int) number(properties, "flip.minSellers", minSellers);
		minDepth = (int) number(properties, "flip.minDepth", minDepth);
		requireChurn = Boolean.parseBoolean(string(properties, "flip.requireChurn", Boolean.toString(requireChurn)));
		churnHaircut = decimal(properties, "flip.churnHaircut", churnHaircut);
		minUnitValue = number(properties, "flip.minUnitValue", minUnitValue);
		maxDispersion = decimal(properties, "flip.maxDispersion", maxDispersion);
		suspiciousMargin = decimal(properties, "flip.suspiciousMargin", suspiciousMargin);
		trustedSamples = (int) number(properties, "flip.trustedSamples", trustedSamples);

		saleTax = decimal(properties, "flip.saleTax", saleTax);
		undercut = decimal(properties, "flip.undercut", undercut);
		binOnly = Boolean.parseBoolean(string(properties, "flip.binOnly", Boolean.toString(binOnly)));

		actionDelayMs = (int) number(properties, "flip.actionDelayMs", actionDelayMs);
		actionJitterMs = (int) number(properties, "flip.actionJitterMs", actionJitterMs);
		refreshDelayMs = (int) number(properties, "flip.refreshDelayMs", refreshDelayMs);
		maxRefreshesPerMinute = (int) number(properties, "flip.maxRefreshesPerMinute", maxRefreshesPerMinute);
	}

	public void save(Properties properties) {
		properties.setProperty("flip.browseCommand", browseCommand);
		properties.setProperty("flip.currency", currency);
		properties.setProperty("flip.sellCommand", sellCommand);
		properties.setProperty("flip.sellFlow", sellFlow);

		properties.setProperty("flip.browseTitles", String.join(",", browseTitles));
		properties.setProperty("flip.buyButtons", String.join(",", buyButtons));
		properties.setProperty("flip.sellButtons", String.join(",", sellButtons));
		properties.setProperty("flip.refreshButtons", String.join(",", refreshButtons));
		properties.setProperty("flip.boughtMessages", String.join(",", boughtMessages));
		properties.setProperty("flip.buyFailedMessages", String.join(",", buyFailedMessages));
		properties.setProperty("flip.listedMessages", String.join(",", listedMessages));
		properties.setProperty("flip.listFailedMessages", String.join(",", listFailedMessages));

		properties.setProperty("flip.neverBuy", neverBuy);
		properties.setProperty("flip.onlyBuy", onlyBuy);
		properties.setProperty("flip.priceRules", priceRules);
		properties.setProperty("flip.minListingPrice", Long.toString(minListingPrice));
		properties.setProperty("flip.maxSpendPerItem", Long.toString(maxSpendPerItem));
		properties.setProperty("flip.sessionBudget", Long.toString(sessionBudget));
		properties.setProperty("flip.stopAfterFlips", Integer.toString(stopAfterFlips));

		properties.setProperty("flip.minProfit", Long.toString(minProfit));
		properties.setProperty("flip.minMargin", decimal(minMargin));
		properties.setProperty("flip.minConfidence", decimal(minConfidence));
		properties.setProperty("flip.minSamples", Integer.toString(minSamples));
		properties.setProperty("flip.minSellers", Integer.toString(minSellers));
		properties.setProperty("flip.minDepth", Integer.toString(minDepth));
		properties.setProperty("flip.requireChurn", Boolean.toString(requireChurn));
		properties.setProperty("flip.churnHaircut", decimal(churnHaircut));
		properties.setProperty("flip.minUnitValue", Long.toString(minUnitValue));
		properties.setProperty("flip.maxDispersion", decimal(maxDispersion));
		properties.setProperty("flip.suspiciousMargin", decimal(suspiciousMargin));
		properties.setProperty("flip.trustedSamples", Integer.toString(trustedSamples));

		properties.setProperty("flip.saleTax", decimal(saleTax));
		properties.setProperty("flip.undercut", decimal(undercut));
		properties.setProperty("flip.binOnly", Boolean.toString(binOnly));

		properties.setProperty("flip.actionDelayMs", Integer.toString(actionDelayMs));
		properties.setProperty("flip.actionJitterMs", Integer.toString(actionJitterMs));
		properties.setProperty("flip.refreshDelayMs", Integer.toString(refreshDelayMs));
		properties.setProperty("flip.maxRefreshesPerMinute", Integer.toString(maxRefreshesPerMinute));
	}

	/**
	 * The parsed shopping rules, rebuilt only when one of the three settings
	 * changes - they are read once per listing per page, which is often.
	 */
	public ItemRules rules() {
		String signature = neverBuy + '\u0000' + onlyBuy + '\u0000' + priceRules;
		if (parsedRules == null || !signature.equals(parsedFrom)) {
			parsedRules = ItemRules.parse(neverBuy, onlyBuy, priceRules);
			parsedFrom = signature;
		}
		return parsedRules;
	}

	/** The sell command with the asking price filled in. */
	public String sellCommandFor(long price) {
		return sellCommand.replace("%price%", Long.toString(price));
	}

	// ----------------------------------------------------------------- parts

	private static List<String> list(String... values) {
		return new ArrayList<>(Arrays.asList(values));
	}

	private static String string(Properties properties, String name, String fallback) {
		String value = properties.getProperty(name);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static List<String> words(Properties properties, String name, List<String> fallback) {
		String value = properties.getProperty(name);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		List<String> found = new ArrayList<>();
		for (String part : value.split(",")) {
			String word = part.trim().toLowerCase(Locale.ROOT);
			if (!word.isEmpty()) {
				found.add(word);
			}
		}
		return found.isEmpty() ? fallback : found;
	}

	private static long number(Properties properties, String name, long fallback) {
		try {
			return Long.parseLong(string(properties, name, Long.toString(fallback)));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static double decimal(Properties properties, String name, double fallback) {
		try {
			return Double.parseDouble(string(properties, name, Double.toString(fallback)));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static String decimal(double value) {
		return String.format(Locale.ROOT, "%.4f", value);
	}
}
