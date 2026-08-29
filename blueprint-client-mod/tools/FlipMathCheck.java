import com.blueprintclient.flip.FlipMath;
import com.blueprintclient.flip.FlipSettings;
import com.blueprintclient.flip.MarketModel;
import com.blueprintclient.flip.PriceText;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the half of the auction flipper that decides how coins get spent.
 *
 * <p>Nothing here needs Minecraft: price parsing, the price model and the
 * scoring are plain Java, so they can be run and checked on any machine.
 * {@code tools/check-flip-math.sh} compiles and runs it.
 */
public final class FlipMathCheck {
	private static int checks;
	private static int failures;

	public static void main(String[] args) throws Exception {
		priceParsing();
		itemNames();
		formatting();
		priceModel();
		modelPersistence();
		scoring();
		asking();
		warmUp();

		System.out.printf("%n%d checks, %d failed%n", checks, failures);
		if (failures > 0) {
			System.exit(1);
		}
	}

	// ------------------------------------------------------------ the checks

	private static void priceParsing() {
		section("reading prices");

		equal("plain number", 1_234_567L, PriceText.parseAmount("1,234,567"));
		equal("thousands suffix", 12_500L, PriceText.parseAmount("12.5k"));
		equal("millions with a currency sign", 3_200_000L, PriceText.parseAmount("$3.2M"));
		equal("billions", 2_000_000_000L, PriceText.parseAmount("2b"));
		equal("spaces as separators", 1_500L, PriceText.parseAmount("1 500"));
		equal("nonsense is refused", -1L, PriceText.parseAmount("lots"));
		equal("zero is refused", -1L, PriceText.parseAmount("0"));
		equal("absurd numbers are refused", -1L, PriceText.parseAmount("9999999999999999"));

		equal(
				"buy it now line",
				1_234_567L,
				PriceText.buyItNowPrice(List.of("Rare drop", "Buy it now: 1,234,567 coins"), true));
		equal("price line", 45_000L, PriceText.buyItNowPrice(List.of("Price: $45,000"), true));
		equal(
				"a fixed price wins over the opening bid",
				20_000L,
				PriceText.buyItNowPrice(List.of("Starting bid: 5,000 coins", "Buy it now: 20,000 coins"), true));
		equal(
				"an auction with no fixed price is not a flip",
				-1L,
				PriceText.buyItNowPrice(List.of("Starting bid: 5,000 coins", "Ends in: 4h"), true));
		equal(
				"bid wording is ignored when only fixed prices count",
				-1L,
				PriceText.buyItNowPrice(List.of("Current bid price: 500 coins"), true));
		equal(
				"and read when they do not",
				500L,
				PriceText.buyItNowPrice(List.of("Current bid price: 500 coins"), false));
		equal("lore with no price at all", -1L, PriceText.buyItNowPrice(List.of("A very nice hat"), true));
		equal("no lore at all", -1L, PriceText.buyItNowPrice(List.of(), true));
		equal(
				"a wrapped line still reads",
				7_500L,
				PriceText.buyItNowPrice(List.of("Buy it now", "7,500 coins"), true));
	}

	private static void itemNames() {
		section("naming items");

		equal(
				"count prefixes and rarity",
				"enchanted bread#common",
				PriceText.itemKey("6x Enchanted Bread", List.of("Buy it now: 500", "COMMON")));
		equal(
				"rarity separates two different markets",
				false,
				PriceText.itemKey("Hyperion", List.of("LEGENDARY SWORD"))
						.equals(PriceText.itemKey("Hyperion", List.of("MYTHIC SWORD"))));
		equal(
				"decoration is not part of the name",
				PriceText.itemKey("Aspect of the End", List.of("RARE SWORD")),
				PriceText.itemKey("Aspect of the End!!", List.of("RARE SWORD")));
		equal("an unnamed item has no key", "", PriceText.itemKey("", List.of("RARE")));
		equal("lore with no rarity line", "wooden pickaxe", PriceText.itemKey("Wooden Pickaxe", List.of("Old")));
	}

	private static void formatting() {
		section("writing coins");

		equal("units", "999", PriceText.coins(999L));
		equal("thousands", "45.3k", PriceText.coins(45_300L));
		equal("millions", "1.23m", PriceText.coins(1_234_567L));
		equal("billions", "2.50b", PriceText.coins(2_500_000_000L));
		equal("losses read as losses", "-12.0k", PriceText.coins(-12_000L));
	}

	private static void priceModel() {
		section("pricing the market");

		long now = 1_000_000_000_000L;
		// Four pages agreeing, and one listing at ten times the price.
		MarketModel model =
				model("hyperion#legendary sword", now, 100_000L, 101_000L, 99_000L, 100_500L, 1_000_000L);

		MarketModel.Appraisal appraisal = model.appraise("hyperion#legendary sword", now);
		equal("the tenfold listing is thrown out, the rest kept", 4, appraisal.samples());
		near("one silly listing does not move the median", 100_250.0, appraisal.fairValue(), 1_000.0);
		// Three samples that agree closely make for a tiny deviation; the one a
		// couple of per cent out is still an honest listing, not an outlier.
		MarketModel tight = model("bow", now, 200_000L, 205_000L, 199_000L);
		equal("an honest listing slightly out is not thrown away", 3, tight.appraise("bow", now).samples());
		check("a settled market has low dispersion", appraisal.dispersion() < 0.05);
		check("and earns confidence", appraisal.confidence() > 0.5);
		near("seen on every page", 1.0, appraisal.supplyRate(), 0.001);

		MarketModel.Appraisal wild = model("junk", now, 100L, 500L, 900L).appraise("junk", now);
		near("a scattered market is measured as such", 0.8, wild.dispersion(), 0.01);
		check("and earns little confidence", wild.confidence() < 0.4);

		MarketModel.Appraisal stale = model.appraise("hyperion#legendary sword", now + 3L * 60L * 60L * 1000L);
		check("old prices lose confidence", stale.confidence() < 0.05);
		equal("an item never seen is not priced", false, model.appraise("nothing", now).isKnown());

		MarketModel rare = new MarketModel();
		for (int i = 0; i < 4; i++) {
			rare.noteScan();
		}
		rare.observe("rare thing", 500L, now);
		rare.observe("rare thing", 520L, now);
		near("supply is sightings over pages", 0.5, rare.appraise("rare thing", now).supplyRate(), 0.001);
	}

	private static void modelPersistence() throws Exception {
		section("remembering the market");

		Path file = Files.createTempFile("blueprint-market", ".properties");
		Files.delete(file);

		long now = System.currentTimeMillis();
		MarketModel saved = new MarketModel();
		saved.loadIfNeeded(file);
		for (long price : new long[] {200_000L, 205_000L, 199_000L}) {
			saved.noteScan();
			saved.observe("terminator#mythic bow", price, now);
		}
		saved.save();

		MarketModel reloaded = new MarketModel();
		reloaded.loadIfNeeded(file);
		MarketModel.Appraisal appraisal = reloaded.appraise("terminator#mythic bow", now);
		equal("every sample came back", 3, appraisal.samples());
		near("and so did the price", 200_000.0, appraisal.fairValue(), 1_000.0);
		Files.deleteIfExists(file);
	}

	private static void scoring() {
		section("scoring a listing");

		FlipSettings settings = testSettings();
		MarketModel.Appraisal solid = new MarketModel.Appraisal(100_000L, 0.05, 0.8, 10, 0.6);

		FlipMath.Assessment bargain = FlipMath.assess(60_000L, 0L, solid, settings);
		equal("a clear bargain is bought", FlipMath.Verdict.BUY, bargain.verdict());
		equal("listed just under the market", 97_000L, bargain.sale());
		equal("after the sale tax", 95_060L, bargain.net());
		equal("profit is what lands in the purse", 35_060L, bargain.profit());
		near("margin is profit over outlay", 0.584, bargain.margin(), 0.01);

		FlipMath.Assessment undercut = FlipMath.assess(60_000L, 70_000L, solid, settings);
		check("a cheaper rival caps the resale", undercut.sale() < bargain.sale());
		equal("the rival sets the price", 67_900L, undercut.sale());

		equal(
				"fair prices are left alone",
				FlipMath.Verdict.THIN_MARGIN,
				FlipMath.assess(90_000L, 0L, solid, settings).verdict());
		equal(
				"a small win is not worth the clicks",
				FlipMath.Verdict.THIN_PROFIT,
				FlipMath.assess(94_000L, 0L, solid, settings).verdict());
		equal(
				"an item seen twice is not priced",
				FlipMath.Verdict.TOO_FEW_SAMPLES,
				FlipMath.assess(10L, 0L, new MarketModel.Appraisal(100_000L, 0.05, 0.9, 2, 0.5), settings).verdict());
		equal(
				"an unsure model does not buy",
				FlipMath.Verdict.LOW_CONFIDENCE,
				FlipMath.assess(10L, 0L, new MarketModel.Appraisal(100_000L, 0.05, 0.2, 10, 0.5), settings).verdict());
		equal(
				"an unsettled market does not buy",
				FlipMath.Verdict.UNSTABLE,
				FlipMath.assess(10L, 0L, new MarketModel.Appraisal(100_000L, 0.9, 0.8, 10, 0.5), settings).verdict());
		equal(
				"too good to be true, on too little evidence",
				FlipMath.Verdict.TOO_GOOD,
				FlipMath.assess(1_000L, 0L, new MarketModel.Appraisal(100_000L, 0.05, 0.8, 5, 0.5), settings)
						.verdict());
		equal(
				"the same bargain, once it is well evidenced",
				FlipMath.Verdict.BUY,
				FlipMath.assess(1_000L, 0L, new MarketModel.Appraisal(100_000L, 0.05, 0.8, 30, 0.5), settings)
						.verdict());
		equal(
				"an unpriced item is never bought",
				FlipMath.Verdict.UNPRICED,
				FlipMath.assess(1_000L, 0L, new MarketModel.Appraisal(0L, 1.0, 0.0, 0, 0.0), settings).verdict());

		FlipMath.Assessment sure = FlipMath.assess(60_000L, 0L, solid, settings);
		FlipMath.Assessment unsure =
				FlipMath.assess(60_000L, 0L, new MarketModel.Appraisal(100_000L, 0.05, 0.4, 10, 0.6), settings);
		check("the same profit scores lower when the model is less sure", unsure.score() < sure.score());
	}

	private static void asking() {
		section("pricing the relist");

		FlipSettings settings = testSettings();
		equal("round numbers", 1_230_000L, FlipMath.roundNicely(1_238_491L));
		equal("small numbers are left alone", 999L, FlipMath.roundNicely(999L));
		equal("exact thousands survive", 1_000L, FlipMath.roundNicely(1_000L));

		equal("just under the market", 97_000L, FlipMath.askingPrice(60_000L, 97_000L, settings));
		check(
				"never at a loss, even if the market moved under us",
				FlipMath.askingPrice(60_000L, 10_000L, settings) > 60_000L);
	}

	/** The flipper should buy nothing until it has watched the market for a while. */
	private static void warmUp() {
		section("warming up");

		FlipSettings settings = testSettings();
		MarketModel model = new MarketModel();
		long now = System.currentTimeMillis();

		int bought = 0;
		for (int page = 1; page <= 6; page++) {
			model.noteScan();
			model.observe("aote#rare sword", 100_000L, now);
			// A mispriced copy shows up on every page; only the well evidenced
			// pages should act on it.
			FlipMath.Assessment assessment =
					FlipMath.assess(50_000L, 0L, model.appraise("aote#rare sword", now), settings);
			if (assessment.worthBuying()) {
				bought++;
			}
			if (page < settings.minSamples) {
				equal("page " + page + " is too early to buy", false, assessment.worthBuying());
			}
		}
		check("but it does buy once the price is established", bought > 0);
	}

	// ----------------------------------------------------------------- parts

	/** A model that has seen each of these prices on a page of its own. */
	private static MarketModel model(String key, long now, long... prices) {
		MarketModel model = new MarketModel();
		for (long price : prices) {
			model.noteScan();
			model.observe(key, price, now);
		}
		return model;
	}

	private static FlipSettings testSettings() {
		FlipSettings settings = FlipSettings.get();
		settings.minProfit = 5_000L;
		settings.minMargin = 0.12;
		settings.minConfidence = 0.35;
		settings.minSamples = 3;
		settings.maxDispersion = 0.35;
		settings.suspiciousMargin = 3.0;
		settings.trustedSamples = 12;
		settings.saleTax = 0.02;
		settings.undercut = 0.03;
		return settings;
	}

	private static void section(String name) {
		System.out.println("\n" + name);
	}

	private static void check(String what, boolean condition) {
		checks++;
		if (condition) {
			System.out.println("  ok    " + what);
		} else {
			failures++;
			System.out.println("  FAIL  " + what);
		}
	}

	private static void equal(String what, Object expected, Object actual) {
		checks++;
		if (expected.equals(actual)) {
			System.out.println("  ok    " + what);
		} else {
			failures++;
			System.out.println("  FAIL  " + what + ": expected " + expected + ", got " + actual);
		}
	}

	private static void near(String what, double expected, double actual, double tolerance) {
		check(what + " (" + actual + " ~ " + expected + ")", Math.abs(expected - actual) <= tolerance);
	}
}
