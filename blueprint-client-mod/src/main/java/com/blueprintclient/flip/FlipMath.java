package com.blueprintclient.flip;

import java.util.Locale;

/**
 * Whether one listing is worth buying, and for how much it should go back up.
 *
 * <p>All of the flipper's arithmetic is here, away from the clicking, and none
 * of it touches Minecraft: given a price, what the rest of the page is asking
 * and what {@link MarketModel} believes, it returns a verdict. That makes the
 * part that decides how coins get spent something that can be run and checked
 * on its own - see {@code tools/FlipMathCheck.java}.
 */
public final class FlipMath {
	/** Why a listing was or was not bought. */
	public enum Verdict {
		BUY("worth buying"),
		UNPRICED("not priced yet"),
		TOO_FEW_SAMPLES("too new"),
		LOW_CONFIDENCE("unsure"),
		UNSTABLE("price all over the place"),
		THIN_PROFIT("profit too small"),
		THIN_MARGIN("margin too thin"),
		TOO_GOOD("too good to be true");

		private final String description;

		Verdict(String description) {
			this.description = description;
		}

		public String description() {
			return description;
		}
	}

	/** What the arithmetic made of a listing. */
	public record Assessment(
			Verdict verdict, long sale, long net, long profit, double margin, double confidence, double score) {
		public boolean worthBuying() {
			return verdict == Verdict.BUY;
		}
	}

	private static final Assessment UNPRICED = new Assessment(Verdict.UNPRICED, 0L, 0L, 0L, 0.0, 0.0, 0.0);

	private FlipMath() {
	}

	/**
	 * Price a listing against the market.
	 *
	 * <p>For a listing at {@code price} of an item the model values at {@code v},
	 * with the next cheapest copy on the same page at {@code competitor}:
	 *
	 * <pre>
	 *   sale   = min(v, competitor) x (1 - undercut)   what it can be sold for
	 *   net    = sale x (1 - tax)                      what lands in the purse
	 *   profit = net - price
	 *   margin = profit / price
	 *   score  = profit x confidence x (0.5 + 0.5 x supply)
	 * </pre>
	 *
	 * <p>Scoring by profit alone would keep picking the one huge margin the
	 * model is least sure about, which is exactly the listing most likely to be
	 * a trap. Multiplying by confidence prefers the flip most likely to be real,
	 * and the supply term leans towards items that appear on most pages, because
	 * those are the ones that sell on again quickly.
	 */
	public static Assessment assess(
			long price, long competitor, MarketModel.Appraisal appraisal, FlipSettings settings) {
		if (price <= 0 || !appraisal.isKnown()) {
			return UNPRICED;
		}
		if (appraisal.samples() < settings.minSamples) {
			return verdict(Verdict.TOO_FEW_SAMPLES, appraisal);
		}
		if (appraisal.confidence() < settings.minConfidence) {
			return verdict(Verdict.LOW_CONFIDENCE, appraisal);
		}
		if (appraisal.dispersion() > settings.maxDispersion) {
			return verdict(Verdict.UNSTABLE, appraisal);
		}

		// The cheapest copy still on sale caps what anyone will pay, however
		// much history says the item is worth.
		long reference = competitor > 0 ? Math.min(appraisal.fairValue(), competitor) : appraisal.fairValue();
		long sale = Math.round(reference * (1.0 - settings.undercut));
		long net = Math.round(sale * (1.0 - settings.saleTax));
		long profit = net - price;
		double margin = profit / (double) price;
		double score = profit * appraisal.confidence() * (0.5 + 0.5 * appraisal.supplyRate());

		Verdict verdict = Verdict.BUY;
		if (profit < settings.minProfit) {
			verdict = Verdict.THIN_PROFIT;
		} else if (margin < settings.minMargin) {
			verdict = Verdict.THIN_MARGIN;
		} else if (margin > settings.suspiciousMargin && appraisal.samples() < settings.trustedSamples) {
			// A margin this big usually means two different items are sharing a
			// name: a reforge, an enchantment, a pet level the lore did not show.
			verdict = Verdict.TOO_GOOD;
		}

		return new Assessment(verdict, sale, net, profit, margin, appraisal.confidence(), score);
	}

	private static Assessment verdict(Verdict verdict, MarketModel.Appraisal appraisal) {
		return new Assessment(verdict, 0L, 0L, 0L, 0.0, appraisal.confidence(), 0.0);
	}

	/**
	 * What to ask for the item once it is bought: just under the cheapest
	 * competing copy, and never so little that the flip loses money.
	 */
	public static long askingPrice(long buyPrice, long sale, FlipSettings settings) {
		long floor = Math.round(buyPrice * (1.0 + settings.minMargin));
		return roundNicely(Math.max(sale, floor));
	}

	/** 1,238,491 becomes 1,230,000: round numbers look like a person typed them. */
	public static long roundNicely(long amount) {
		if (amount < 1_000L) {
			return amount;
		}
		long magnitude = 1L;
		long scratch = amount;
		while (scratch >= 1_000L) {
			scratch /= 10L;
			magnitude *= 10L;
		}
		return Math.max(1L, amount / magnitude * magnitude);
	}

	/** One line of arithmetic, for the overlay. */
	public static String explain(String display, long price, Assessment assessment) {
		return String.format(
				Locale.ROOT,
				"%s  %s -> %s  +%s (%.0f%%)",
				display,
				PriceText.coins(price),
				PriceText.coins(assessment.sale()),
				PriceText.coins(assessment.profit()),
				assessment.margin() * 100.0);
	}
}
