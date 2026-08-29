import com.blueprintclient.flip.FlipMath;
import com.blueprintclient.flip.FlipSettings;
import com.blueprintclient.flip.ItemRules;
import com.blueprintclient.flip.MarketModel;
import com.blueprintclient.flip.PriceText;
import com.blueprintclient.flip.SellFlow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the half of the auction flipper that decides how money gets spent.
 *
 * <p>Nothing here needs Minecraft: price parsing, the price model and the
 * scoring are plain Java, so they can be run and checked on any machine.
 * {@code tools/check-flip-math.sh} compiles and runs it.
 */
public final class FlipMathCheck {
	private static final long NOW = 1_000_000_000_000L;

	private static int checks;
	private static int failures;

	public static void main(String[] args) throws Exception {
		priceParsing();
		itemNames();
		formatting();
		priceModel();
		evidence();
		bait();
		shopping();
		shoppingLists();
		modelPersistence();
		scoring();
		haircut();
		stacks();
		asking();
		defaults();
		sellCommand();
		sellChains();
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
		equal("trillions", 1_500_000_000_000L, PriceText.parseAmount("1.5T"));
		equal("spaces as separators", 1_500L, PriceText.parseAmount("1 500"));
		equal("nonsense is refused", -1L, PriceText.parseAmount("lots"));
		equal("zero is refused", -1L, PriceText.parseAmount("0"));
		equal("absurd numbers are refused", -1L, PriceText.parseAmount("99999999999999999"));

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
		equal(
				"a line that is just money",
				1_250_000L,
				PriceText.buyItNowPrice(List.of("Seller: Notch", "$1,250,000", "Time left: 4h"), true));
		equal("money with a suffix", 4_200_000L, PriceText.buyItNowPrice(List.of("$4.2M"), true));
		equal("lore with no price at all", -1L, PriceText.buyItNowPrice(List.of("A very nice hat"), true));
		equal("no lore at all", -1L, PriceText.buyItNowPrice(List.of(), true));
		equal(
				"a wrapped line still reads",
				7_500L,
				PriceText.buyItNowPrice(List.of("Buy it now", "7,500 coins"), true));

		equal("who is selling", "notch", PriceText.seller(List.of("$1,000", "Seller: Notch")));
		equal("however it is worded", "notch", PriceText.seller(List.of("Sold by Notch")));
		equal("and nobody when it is not said", "", PriceText.seller(List.of("$1,000", "Time left: 4h")));
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
		equal(
				"an enchanted sword is not a plain one",
				false,
				PriceText.itemKey("Netherite Sword", List.of(), PriceText.identity("sharpness 5"))
						.equals(PriceText.itemKey("Netherite Sword", List.of(), PriceText.identity(""))));
		equal(
				"the same enchantments give the same key",
				PriceText.itemKey("Netherite Sword", List.of(), PriceText.identity("minecraft:sword", "sharp 5")),
				PriceText.itemKey("Netherite Sword", List.of(), PriceText.identity("minecraft:sword", "sharp 5")));
	}

	private static void formatting() {
		section("writing money");

		equal("units", "999", PriceText.coins(999L));
		equal("thousands", "45.3k", PriceText.coins(45_300L));
		equal("millions", "1.23m", PriceText.coins(1_234_567L));
		equal("billions", "2.50b", PriceText.coins(2_500_000_000L));
		equal("trillions", "1.20t", PriceText.coins(1_200_000_000_000L));
		equal("with the server's currency", "$4.20m", PriceText.money(4_200_000L, "$"));
		equal("losses keep the sign in front", "-$3.0k", PriceText.money(-3_000L, "$"));
	}

	private static void priceModel() {
		section("pricing the market");

		// Five pages, a new listing on each, one of them at ten times the price.
		MarketModel model = new MarketModel();
		String[] sellers = {"ann", "bob", "cat", "dan", "eve"};
		long[] prices = {100_000L, 101_000L, 99_000L, 100_500L, 1_000_000L};
		for (int i = 0; i < prices.length; i++) {
			page(model, "hyperion", new long[] {prices[i]}, new String[] {sellers[i]});
		}

		MarketModel.Appraisal appraisal = model.appraise("hyperion", NOW);
		equal("the tenfold listing is thrown out, the rest kept", 4, appraisal.samples());
		near("the fair value sits low among the asks", 99_500.0, appraisal.fairValue(), 1_000.0);
		check("a settled market has low dispersion", appraisal.dispersion() < 0.05);
		equal("five different people were selling it", 5, appraisal.sellers());
		near("seen on every page", 1.0, appraisal.supplyRate(), 0.001);

		// Three samples that agree closely make for a tiny deviation; the one a
		// couple of per cent out is still an honest listing, not an outlier.
		MarketModel tight = new MarketModel();
		page(tight, "bow", new long[] {200_000L}, new String[] {"ann"});
		page(tight, "bow", new long[] {205_000L}, new String[] {"bob"});
		page(tight, "bow", new long[] {199_000L}, new String[] {"cat"});
		equal("an honest listing slightly out is not thrown away", 3, tight.appraise("bow", NOW).samples());

		MarketModel scattered = new MarketModel();
		page(scattered, "junk", new long[] {100L}, new String[] {"ann"});
		page(scattered, "junk", new long[] {500L}, new String[] {"bob"});
		page(scattered, "junk", new long[] {900L}, new String[] {"cat"});
		MarketModel.Appraisal wild = scattered.appraise("junk", NOW);
		near("a scattered market is measured as such", 0.8, wild.dispersion(), 0.01);
		check("and earns little confidence", wild.confidence() < 0.4);

		MarketModel.Appraisal stale = model.appraise("hyperion", NOW + 3L * 60L * 60L * 1000L);
		check("old prices lose confidence", stale.confidence() < 0.05);
		equal("an item never seen is not priced", false, model.appraise("nothing", NOW).isKnown());

		MarketModel rare = new MarketModel();
		page(rare, "rare thing", new long[] {500L}, new String[] {"ann"});
		page(rare, "rare thing", new long[] {520L}, new String[] {"bob"});
		page(rare, "other thing", new long[] {10L}, new String[] {"ann"});
		page(rare, "other thing", new long[] {10L}, new String[] {"ann"});
		near("supply is pages seen on over pages read", 0.5, rare.appraise("rare thing", NOW).supplyRate(), 0.001);
	}

	/** The evidence a price has to come with before it is believed. */
	private static void evidence() {
		section("weighing the evidence");

		MarketModel model = new MarketModel();
		for (int refresh = 0; refresh < 20; refresh++) {
			page(model, "dirt", new long[] {2_000L}, new String[] {"troll"});
		}
		MarketModel.Appraisal planted = model.appraise("dirt", NOW);
		equal("twenty refreshes of one listing is one listing", 1, planted.samples());
		equal("by one person", 1, planted.sellers());
		near("that has never moved", 0.0, planted.churn(), 0.001);

		MarketModel moving = new MarketModel();
		page(moving, "netherite", new long[] {100L, 110L}, new String[] {"ann", "bob"});
		page(moving, "netherite", new long[] {110L, 120L}, new String[] {"bob", "cat"});
		page(moving, "netherite", new long[] {120L, 130L}, new String[] {"cat", "dan"});
		MarketModel.Appraisal busy = moving.appraise("netherite", NOW);
		equal("four distinct listings", 4, busy.samples());
		equal("from four people", 4, busy.sellers());
		check("and most of them have gone", busy.churn() > 0.4);
		check("which earns more confidence than a planted price", busy.confidence() > planted.confidence());
	}

	/** The whole point: a planted price must not be tradeable. */
	private static void bait() {
		section("bait");

		FlipSettings settings = testSettings();

		// Somebody lists dirt at $2,000 and leaves it there. Then a real seller
		// lists dirt at $1,000. The gap looks like a 90% margin.
		MarketModel planted = new MarketModel();
		for (int refresh = 0; refresh < 20; refresh++) {
			page(planted, "dirt", new long[] {2_000L}, new String[] {"troll"});
		}
		equal(
				"one listing seen twenty times is not a market",
				FlipMath.Verdict.TOO_FEW_SAMPLES,
				FlipMath.assess("thing", 1_000L, 1, 2_000L, 2, planted.appraise("dirt", NOW), settings).verdict());

		// The same person, three listings, still going nowhere.
		MarketModel oneSeller = new MarketModel();
		for (int refresh = 0; refresh < 20; refresh++) {
			page(oneSeller, "dirt", new long[] {2_000L, 2_100L, 2_200L}, new String[] {"troll", "troll", "troll"});
		}
		equal(
				"nor is one person listing it three times",
				FlipMath.Verdict.FEW_SELLERS,
				FlipMath.assess("thing", 1_000L, 1, 2_000L, 3, oneSeller.appraise("dirt", NOW), settings).verdict());

		// Two people, three listings, none of which ever move.
		MarketModel stuck = new MarketModel();
		for (int refresh = 0; refresh < 20; refresh++) {
			page(stuck, "dirt", new long[] {2_000L, 2_100L, 2_200L}, new String[] {"troll", "troll", "friend"});
		}
		MarketModel.Appraisal stale = stuck.appraise("dirt", NOW);
		equal("three listings", 3, stale.samples());
		equal("two sellers", 2, stale.sellers());
		equal(
				"but a price nothing ever moves at is not a price",
				FlipMath.Verdict.NO_CHURN,
				FlipMath.assess("thing", 1_000L, 1, 2_000L, 3, stale, settings).verdict());

		// A real market that happens to have only one copy on the page: there is
		// nothing to resell against right now.
		MarketModel thin = new MarketModel();
		page(thin, "netherite", new long[] {100_000L, 102_000L}, new String[] {"ann", "bob"});
		page(thin, "netherite", new long[] {101_000L}, new String[] {"cat"});
		page(thin, "netherite", new long[] {99_000L}, new String[] {"dan"});
		equal(
				"an item with no competition has no established price",
				FlipMath.Verdict.THIN_MARKET,
				FlipMath.assess("thing", 40_000L, 1, 0L, 1, thin.appraise("netherite", NOW), settings).verdict());

		// And the honest version of the same trade: several sellers, listings
		// coming and going, copies on the page to undercut.
		MarketModel real = new MarketModel();
		page(real, "netherite", new long[] {100_000L, 102_000L, 105_000L}, new String[] {"ann", "bob", "cat"});
		page(real, "netherite", new long[] {101_000L, 103_000L}, new String[] {"dan", "eve"});
		page(real, "netherite", new long[] {99_000L, 104_000L}, new String[] {"fay", "gus"});
		FlipMath.Assessment honest =
				FlipMath.assess("thing", 40_000L, 1, 99_000L, 3, real.appraise("netherite", NOW), settings);
		equal("a real market with a real gap is bought", FlipMath.Verdict.BUY, honest.verdict());
		check("at a profit that has been cut for uncertainty", honest.profit() > 0);
		check("and the cut is not nothing", honest.haircut() > 0.0);
	}

	/** The list of what you will and will not have bought on your behalf. */
	private static void shopping() {
		section("what to buy");

		FlipSettings settings = testSettings();
		MarketModel.Appraisal solid = appraisal(100_000L, 0.0, 0.8, 10, 1.0);

		settings.neverBuy = "dirt, cobblestone";
		equal(
				"dirt is on the list",
				FlipMath.Verdict.BLOCKED,
				FlipMath.assess("Dirt", 1_000L, 1, 0L, 5, solid, settings).verdict());
		equal(
				"a stack of it is still dirt",
				FlipMath.Verdict.BLOCKED,
				FlipMath.assess("64x Dirt", 1_000L, 1, 0L, 5, solid, settings).verdict());
		equal(
				"a dirty sword is not dirt",
				FlipMath.Verdict.BUY,
				FlipMath.assess("Dirty Sword", 60_000L, 1, 0L, 5, solid, settings).verdict());

		settings.neverBuy = "* spawn egg";
		equal(
				"a glob catches the whole family",
				FlipMath.Verdict.BLOCKED,
				FlipMath.assess("Zombie Spawn Egg", 60_000L, 1, 0L, 5, solid, settings).verdict());
		equal(
				"and leaves everything else",
				FlipMath.Verdict.BUY,
				FlipMath.assess("Zombie Head", 60_000L, 1, 0L, 5, solid, settings).verdict());

		settings.neverBuy = "";
		settings.onlyBuy = "netherite ingot, elytra";
		equal(
				"nothing off the only-buy list",
				FlipMath.Verdict.NOT_WANTED,
				FlipMath.assess("Diamond Block", 60_000L, 1, 0L, 5, solid, settings).verdict());
		equal(
				"and anything on it",
				FlipMath.Verdict.BUY,
				FlipMath.assess("Elytra", 60_000L, 1, 0L, 5, solid, settings).verdict());

		settings.onlyBuy = "";
		settings.priceRules = "elytra: 2m-5m";
		MarketModel.Appraisal dear = appraisal(10_000_000L, 0.0, 0.8, 10, 1.0);
		equal(
				"under the range it is left alone",
				FlipMath.Verdict.OUT_OF_RANGE,
				FlipMath.assess("Elytra", 1_000_000L, 1, 0L, 5, dear, settings).verdict());
		equal(
				"over the range too",
				FlipMath.Verdict.OUT_OF_RANGE,
				FlipMath.assess("Elytra", 6_000_000L, 1, 0L, 5, dear, settings).verdict());
		equal(
				"inside it, the maths gets its turn",
				FlipMath.Verdict.BUY,
				FlipMath.assess("Elytra", 3_000_000L, 1, 0L, 5, dear, settings).verdict());
		equal(
				"and the range is the price of one, not of the stack",
				FlipMath.Verdict.BUY,
				FlipMath.assess("64x Elytra", 192_000_000L, 64, 0L, 5, dear, settings).verdict());
		equal(
				"an item with no rule is not affected",
				FlipMath.Verdict.BUY,
				FlipMath.assess("Diamond Block", 60_000L, 1, 0L, 5, solid, settings).verdict());

		settings.priceRules = "";
		settings.minListingPrice = 500_000L;
		equal(
				"a listing under the floor",
				FlipMath.Verdict.OUT_OF_RANGE,
				FlipMath.assess("Diamond", 100_000L, 1, 0L, 5, solid, settings).verdict());
		settings.minListingPrice = 0L;
		settings.maxSpendPerItem = 1_000_000L;
		equal(
				"and one over the ceiling",
				FlipMath.Verdict.OUT_OF_RANGE,
				FlipMath.assess("Diamond", 2_000_000L, 1, 0L, 5, dear, settings).verdict());
		settings.maxSpendPerItem = 0L;
	}

	/** The list syntax on its own. */
	private static void shoppingLists() {
		section("reading the lists");

		ItemRules rules = ItemRules.parse("dirt, cobblestone", "", "diamond block: 100k-5m, elytra: 2m-, tnt: -20m");
		equal("a name on the never list", true, rules.blocked("Dirt"));
		equal("counts are not part of the name", true, rules.blocked("64x Cobblestone"));
		equal("a longer name is not the same name", false, rules.blocked("Dirty Sword"));
		equal("nor is a word inside another", false, rules.blocked("Cobblestone Wall Sign Thing")
				&& !rules.blocked("Cobblestone Wall"));
		equal("nothing is unwanted without an only-buy list", false, rules.unwanted("Anything"));

		ItemRules only = ItemRules.parse("", "elytra", "");
		equal("off the list", true, only.unwanted("Diamond"));
		equal("on the list", false, only.unwanted("Elytra"));

		equal("a low and a high", true, rules.rangeFor("Diamond Block").allows(1_000_000L));
		equal("below the low", false, rules.rangeFor("Diamond Block").allows(50_000L));
		equal("above the high", false, rules.rangeFor("Diamond Block").allows(6_000_000L));
		equal("an open top", true, rules.rangeFor("Elytra").allows(900_000_000L));
		equal("but not an open bottom", false, rules.rangeFor("Elytra").allows(1_000_000L));
		equal("an open bottom", true, rules.rangeFor("TNT").allows(5L));
		equal("with a closed top", false, rules.rangeFor("TNT").allows(21_000_000L));
		equal("no rule for an item nobody mentioned", null, rules.rangeFor("Bread"));

		ItemRules bare = ItemRules.parse("", "", "bread: 5k");
		equal("a single number is a ceiling", true, bare.rangeFor("Bread").allows(4_000L));
		equal("and only a ceiling", false, bare.rangeFor("Bread").allows(6_000L));

		equal("nothing set is no rules at all", true, ItemRules.parse("", "", "").isEmpty());
		equal("unreadable entries are dropped", true, ItemRules.parse("", "", "bread, : , nonsense:").isEmpty());

		ItemRules globs = ItemRules.parse("* spawn egg, shulker*, *sword*", "", "");
		equal("a star at the front", true, globs.blocked("Zombie Spawn Egg"));
		equal("a star at the end", true, globs.blocked("Shulker Box"));
		equal("stars at both ends", true, globs.blocked("A Very Sharp Sword Indeed"));
		equal("and no false friends", false, globs.blocked("Spawn Egg Recipe Book") && globs.blocked("Bread"));
	}

	private static void modelPersistence() throws Exception {
		section("remembering the market");

		Path file = Files.createTempFile("blueprint-market", ".properties");
		Files.delete(file);

		// Real times here: what is written down is each sample's age at the
		// moment of saving, and anything older than the model's memory is
		// dropped when it is read back.
		long now = System.currentTimeMillis();
		MarketModel saved = new MarketModel();
		saved.loadIfNeeded(file);
		page(saved, "terminator", new long[] {200_000L}, new String[] {"ann"}, now);
		page(saved, "terminator", new long[] {205_000L}, new String[] {"bob"}, now);
		page(saved, "terminator", new long[] {199_000L}, new String[] {"cat"}, now);
		saved.save();

		MarketModel reloaded = new MarketModel();
		reloaded.loadIfNeeded(file);
		MarketModel.Appraisal appraisal = reloaded.appraise("terminator", now);
		equal("every sample came back", 3, appraisal.samples());
		equal("and so did the sellers", 3, appraisal.sellers());
		near("and the price", 199_600.0, appraisal.fairValue(), 1_500.0);
		Files.deleteIfExists(file);
	}

	private static void scoring() {
		section("scoring a listing");

		FlipSettings settings = testSettings();
		MarketModel.Appraisal solid = appraisal(100_000L, 0.0, 0.8, 10, 1.0);

		FlipMath.Assessment bargain = FlipMath.assess("thing", 60_000L, 1, 0L, 5, solid, settings);
		equal("a clear bargain is bought", FlipMath.Verdict.BUY, bargain.verdict());
		equal("listed just under the market", 97_000L, bargain.sale());
		equal("after the sale tax", 95_060L, bargain.net());
		equal("profit is what lands in the purse", 35_060L, bargain.profit());
		near("margin is profit over outlay", 0.584, bargain.margin(), 0.01);

		FlipMath.Assessment undercut = FlipMath.assess("thing", 60_000L, 1, 70_000L, 5, solid, settings);
		check("a cheaper rival caps the resale", undercut.sale() < bargain.sale());
		equal("the rival sets the price", 67_900L, undercut.sale());

		equal(
				"fair prices are left alone",
				FlipMath.Verdict.THIN_MARGIN,
				FlipMath.assess("thing", 90_000L, 1, 0L, 5, solid, settings).verdict());
		equal(
				"a small win is not worth the clicks",
				FlipMath.Verdict.THIN_PROFIT,
				FlipMath.assess("thing", 94_000L, 1, 0L, 5, solid, settings).verdict());
		equal(
				"an item seen twice is not priced",
				FlipMath.Verdict.TOO_FEW_SAMPLES,
				FlipMath.assess("thing", 10L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.9, 2, 1.0), settings).verdict());
		equal(
				"an unsure model does not buy",
				FlipMath.Verdict.LOW_CONFIDENCE,
				FlipMath.assess("thing", 10L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.2, 10, 1.0), settings).verdict());
		equal(
				"an unsettled market does not buy",
				FlipMath.Verdict.UNSTABLE,
				FlipMath.assess("thing", 10L, 1, 0L, 5, appraisal(100_000L, 0.9, 0.8, 10, 1.0), settings).verdict());
		equal(
				"too good to be true, on too little evidence",
				FlipMath.Verdict.TOO_GOOD,
				FlipMath.assess("thing", 1_000L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.8, 5, 1.0), settings).verdict());
		equal(
				"the same bargain, once it is well evidenced",
				FlipMath.Verdict.BUY,
				FlipMath.assess("thing", 1_000L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.8, 30, 1.0), settings).verdict());
		equal(
				"an unpriced item is never bought",
				FlipMath.Verdict.UNPRICED,
				FlipMath.assess("thing", 1_000L, 1, 0L, 5, appraisal(0L, 1.0, 0.0, 0, 0.0), settings).verdict());

		settings.minUnitValue = 50_000L;
		equal(
				"cheap tat is beneath the flipper",
				FlipMath.Verdict.TOO_CHEAP,
				FlipMath.assess("thing", 1_000L, 1, 0L, 5, appraisal(10_000L, 0.0, 0.8, 30, 1.0), settings).verdict());
		settings.minUnitValue = 0L;

		// A server that does not say who is selling: distinct listings have to
		// stand in for knowing they came from different people.
		MarketModel.Appraisal anonymous = new MarketModel.Appraisal(100_000L, 0.0, 0.8, 4, 0.6, 0, 1.0);
		equal(
				"without sellers, twice the listings are wanted",
				FlipMath.Verdict.TOO_FEW_SAMPLES,
				FlipMath.assess("thing", 60_000L, 1, 0L, 5, anonymous, settings).verdict());
		equal(
				"and that is enough on its own",
				FlipMath.Verdict.BUY,
				FlipMath.assess("thing", 60_000L, 1, 0L, 5, new MarketModel.Appraisal(100_000L, 0.0, 0.8, 6, 0.6, 0, 1.0),
								settings)
						.verdict());

		FlipMath.Assessment sure = FlipMath.assess("thing", 60_000L, 1, 0L, 5, solid, settings);
		FlipMath.Assessment unsure =
				FlipMath.assess("thing", 60_000L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.4, 10, 1.0), settings);
		check("the same profit scores lower when the model is less sure", unsure.score() < sure.score());
	}

	/** How much of the resale estimate is disbelieved, and why. */
	private static void haircut() {
		section("cutting the estimate");

		FlipSettings settings = testSettings();
		FlipMath.Assessment busy = FlipMath.assess("thing", 60_000L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.8, 10, 1.0), settings);
		FlipMath.Assessment sluggish =
				FlipMath.assess("thing", 60_000L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.8, 10, 0.2), settings);
		FlipMath.Assessment messy =
				FlipMath.assess("thing", 60_000L, 1, 0L, 5, appraisal(100_000L, 0.2, 0.8, 10, 1.0), settings);

		near("a busy, tight market is taken at face value", 0.0, busy.haircut(), 0.001);
		near("a market that barely moves is cut", 0.2, sluggish.haircut(), 0.001);
		near("so is one that disagrees with itself", 0.2, messy.haircut(), 0.001);
		check("and a cut estimate means a smaller resale", sluggish.sale() < busy.sale());
		check("which means less profit", sluggish.profit() < busy.profit());

		// The trade only a full-price estimate would have taken.
		settings.minMargin = 0.30;
		equal(
				"a thin trade survives in a market that moves",
				FlipMath.Verdict.BUY,
				FlipMath.assess("thing", 70_000L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.8, 10, 1.0), settings).verdict());
		equal(
				"and is refused in one that does not",
				FlipMath.Verdict.THIN_MARGIN,
				FlipMath.assess("thing", 70_000L, 1, 0L, 5, appraisal(100_000L, 0.0, 0.8, 10, 0.2), settings).verdict());
	}

	private static void stacks() {
		section("stacks");

		FlipSettings settings = testSettings();
		// The model prices one diamond block; the listing is a stack of 64.
		MarketModel.Appraisal each = appraisal(100_000L, 0.0, 0.8, 10, 1.0);

		FlipMath.Assessment single = FlipMath.assess("thing", 60_000L, 1, 0L, 5, each, settings);
		FlipMath.Assessment stack = FlipMath.assess("thing", 3_840_000L, 64, 0L, 5, each, settings);
		equal("a stack sells for a stack's worth", 6_208_000L, stack.sale());
		equal("and the profit is on the whole stack", 2_243_840L, stack.profit());
		near("with the same margin as one of them", single.margin(), stack.margin(), 0.001);
		equal(
				"a stack barely under the market is not worth the risk",
				FlipMath.Verdict.THIN_MARGIN,
				FlipMath.assess("thing", 5_800_000L, 64, 0L, 5, each, settings).verdict());
		equal(
				"and one over the market is a loss",
				FlipMath.Verdict.THIN_PROFIT,
				FlipMath.assess("thing", 6_100_000L, 64, 0L, 5, each, settings).verdict());
		equal(
				"a listing of nothing is not priced",
				FlipMath.Verdict.UNPRICED,
				FlipMath.assess("thing", 1_000L, 0, 0L, 5, each, settings).verdict());
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

	/** The shipped defaults have to allow the things people actually flip. */
	private static void defaults() {
		section("out of the box");

		FlipSettings settings = FlipSettings.defaults();
		MarketModel.Appraisal elytra = new MarketModel.Appraisal(80_000_000L, 0.05, 0.8, 10, 0.6, 4, 1.0);

		check("an elytra at sixty million is not over the ceiling", settings.maxSpendPerItem >= 60_000_000L);
		equal(
				"and is bought like anything else",
				FlipMath.Verdict.BUY,
				FlipMath.assess("Elytra", 40_000_000L, 1, 0L, 5, elytra, settings).verdict());
		equal(
				"a stack of netherite ingots too",
				FlipMath.Verdict.BUY,
				FlipMath.assess(
								"64x Netherite Ingot",
								120_000_000L,
								64,
								0L,
								5,
								new MarketModel.Appraisal(4_000_000L, 0.05, 0.8, 10, 0.6, 4, 1.0),
								settings)
						.verdict());
		check("with a session budget that allows more than one of them",
				settings.sessionBudget > settings.maxSpendPerItem);
		equal(
				"dirt is still dirt",
				FlipMath.Verdict.BLOCKED,
				FlipMath.assess("Dirt", 1_000L, 1, 0L, 5, elytra, settings).verdict());
	}

	private static void sellCommand() {
		section("the sell command");

		FlipSettings settings = FlipSettings.defaults();
		equal("the default is the one DonutSMP takes", "ah sell %price%", settings.sellCommand);
		equal("filled in with a plain number", "ah sell 1230000", settings.sellCommandFor(1_230_000L));
		equal(
				"a price is a round number a server will accept",
				"ah sell 1230000",
				settings.sellCommandFor(FlipMath.roundNicely(1_238_491L)));

		settings.sellCommand = "/auction sell %price% confirm";
		equal("any wording works", "/auction sell 500 confirm", settings.sellCommandFor(500L));

		settings.sellCommand = "ah sell";
		equal("a command with no place for the price is left alone", "ah sell", settings.sellCommandFor(500L));
	}

	private static void sellChains() {
		section("walking the sell menus");

		equal("no chain means use the sell command", true, SellFlow.parse("").isEmpty());
		equal("nor does a chain of nothing", true, SellFlow.parse(" , , ").isEmpty());

		SellFlow flow = SellFlow.parse(
				"button:manage auctions, item, price, wait:800, Confirm, wait:oops, button:");
		equal("every readable step is kept", 5, flow.size());
		equal("a named button", SellFlow.Kind.BUTTON, flow.step(0).kind());
		equal("names are matched in lower case", "manage auctions", flow.step(0).argument());
		equal("handing the item over", SellFlow.Kind.ITEM, flow.step(1).kind());
		equal("sending the price", SellFlow.Kind.PRICE, flow.step(2).kind());
		equal("waiting", 800L, flow.step(3).millis());
		equal("a bare word is a button", SellFlow.Kind.BUTTON, flow.step(4).kind());
		equal("and is matched in lower case too", "confirm", flow.step(4).argument());

		equal("an unreadable wait is dropped", 1, SellFlow.parse("wait:oops, item").size());
		equal("a huge wait is capped", 10_000L, SellFlow.parse("wait:99999999").step(0).millis());

		equal("the price is typed plainly by default", "1230000",
				SellFlow.priceMessage(SellFlow.parse("price").step(0), 1_230_000L));
		equal("or through a template", "/ah price 1230000",
				SellFlow.priceMessage(SellFlow.parse("price:/ah price %price%").step(0), 1_230_000L));
		equal("steps say what they are", "click \"confirm\"", SellFlow.parse("confirm").step(0).describe());
	}

	/** The flipper should buy nothing until it has watched the market for a while. */
	private static void warmUp() {
		section("warming up");

		FlipSettings settings = testSettings();
		MarketModel model = new MarketModel();
		String[] sellers = {"ann", "bob", "cat", "dan", "eve", "fay"};

		int bought = 0;
		for (int page = 1; page <= 6; page++) {
			// A different seller each page, and a mispriced copy on every one of
			// them: only the well evidenced pages should act on it.
			page(model, "aote", new long[] {100_000L}, new String[] {sellers[page - 1]});
			FlipMath.Assessment assessment =
					FlipMath.assess("thing", 50_000L, 1, 0L, 3, model.appraise("aote", NOW), settings);
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

	/** One page of the auction house, read into the model. */
	private static void page(MarketModel model, String key, long[] prices, String[] sellers) {
		page(model, key, prices, sellers, NOW);
	}

	private static void page(MarketModel model, String key, long[] prices, String[] sellers, long when) {
		model.beginScan();
		for (int i = 0; i < prices.length; i++) {
			model.observe(key, prices[i], sellers[i], when);
		}
		model.endScan(when);
	}

	/** A believable appraisal, so a check can vary the one thing it is about. */
	private static MarketModel.Appraisal appraisal(
			long fairValue, double dispersion, double confidence, int samples, double churn) {
		return new MarketModel.Appraisal(fairValue, dispersion, confidence, samples, 0.6, 4, churn);
	}

	private static FlipSettings testSettings() {
		FlipSettings settings = FlipSettings.get();
		settings.neverBuy = "";
		settings.onlyBuy = "";
		settings.priceRules = "";
		settings.minListingPrice = 0L;
		// Zero is no ceiling; the checks that care set their own.
		settings.maxSpendPerItem = 0L;
		settings.minProfit = 5_000L;
		settings.minMargin = 0.12;
		settings.minConfidence = 0.35;
		settings.minSamples = 3;
		settings.minSellers = 2;
		settings.minDepth = 2;
		settings.requireChurn = true;
		settings.churnHaircut = 0.25;
		settings.minUnitValue = 0L;
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
		if (java.util.Objects.equals(expected, actual)) {
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
