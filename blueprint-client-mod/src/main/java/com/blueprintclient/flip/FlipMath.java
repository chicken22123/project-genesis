package com.blueprintclient.flip;

import java.util.Locale;

/**
 * Whether one listing is worth buying, and for how much it should go back up.
 *
 * <p>All of the flipper's arithmetic is here, away from the clicking, and none
 * of it touches Minecraft, so the part that decides how money gets spent can be
 * run and checked on its own - see {@code tools/FlipMathCheck.java}.
 *
 * <p>The job this does is not "find the biggest gap between two numbers". Any
 * auction house has plenty of those and most of them are bait: somebody lists
 * dirt at two thousand, the gap to a real listing at one thousand looks like a
 * hundred per cent margin, and the money goes on dirt. So a listing has to get
 * past a row of gates before the size of the margin is even looked at, and each
 * gate asks for a different kind of evidence that the resale price is real.
 */
public final class FlipMath {
	/** Why a listing was or was not bought. */
	public enum Verdict {
		BUY("worth buying"),
		UNPRICED("not priced yet"),
		TOO_FEW_SAMPLES("too few listings seen"),
		FEW_SELLERS("only one seller"),
		THIN_MARKET("nothing to resell against"),
		NO_CHURN("nothing ever moves"),
		LOW_CONFIDENCE("unsure"),
		UNSTABLE("price all over the place"),
		TOO_CHEAP("not worth the trouble"),
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
			Verdict verdict,
			long sale,
			long net,
			long profit,
			double margin,
			double confidence,
			double haircut,
			double score) {
		public boolean worthBuying() {
			return verdict == Verdict.BUY;
		}
	}

	private static final Assessment UNPRICED =
			new Assessment(Verdict.UNPRICED, 0L, 0L, 0L, 0.0, 0.0, 0.0, 0.0);

	/** However uncertain things look, the estimate is not cut by more than this. */
	private static final double MAX_HAIRCUT = 0.6;

	private FlipMath() {
	}

	/**
	 * Price a listing against the market.
	 *
	 * <p>The gates come first, and every one of them has to pass:
	 *
	 * <ul>
	 *   <li>enough <b>distinct listings</b> of the item have been seen - the
	 *       same listing on twenty refreshes is one, not twenty;
	 *   <li>from enough <b>different sellers</b>, because one person can list
	 *       the same lie ten times. Where the auction house does not say who is
	 *       selling, twice as many distinct listings are demanded instead;
	 *   <li>enough copies <b>on the page right now</b> to resell against, since
	 *       an item with no competition has no established price;
	 *   <li>the item's listings actually <b>come and go</b>. A price that has sat
	 *       untouched since it was first seen is a price nobody is paying;
	 *   <li>the model is <b>confident</b> and the market <b>agrees with itself</b>.
	 * </ul>
	 *
	 * <p>Only then is the money worked out. For {@code count} of an item the
	 * model values at {@code v} each, the cheapest rival copy on the page asking
	 * {@code competitor} each:
	 *
	 * <pre>
	 *   ceiling = min(v, competitor)                     nobody pays more than the cheapest
	 *   haircut = spread + (1 - churn) x weight          how much of that to disbelieve
	 *   sale    = ceiling x count x (1 - undercut) x (1 - haircut)
	 *   net     = sale x (1 - tax)
	 *   profit  = net - price
	 *   margin  = profit / price
	 *   score   = profit x confidence x (0.5 + 0.5 x supply)
	 * </pre>
	 *
	 * <p>The haircut is the difference between a sum that looks profitable and
	 * one that is: the resale estimate is cut by how much the market disagrees
	 * with itself and by how little it moves, so an item nobody trades has to
	 * show a huge gap before it clears the margin test, and a busy item with a
	 * tight spread barely gets touched.
	 */
	public static Assessment assess(
			long price,
			int count,
			long competitor,
			int depth,
			MarketModel.Appraisal appraisal,
			FlipSettings settings) {
		if (price <= 0 || count <= 0 || !appraisal.isKnown()) {
			return UNPRICED;
		}

		// With no seller names to go on, more independent listings stand in for
		// knowing they came from different people.
		boolean anonymous = appraisal.sellers() <= 0;
		int neededSamples = anonymous ? settings.minSamples * 2 : settings.minSamples;
		if (appraisal.samples() < neededSamples) {
			return refused(Verdict.TOO_FEW_SAMPLES, appraisal);
		}
		if (!anonymous && appraisal.sellers() < settings.minSellers) {
			return refused(Verdict.FEW_SELLERS, appraisal);
		}
		if (depth < settings.minDepth) {
			return refused(Verdict.THIN_MARKET, appraisal);
		}
		if (settings.requireChurn && appraisal.churn() <= 0.0) {
			return refused(Verdict.NO_CHURN, appraisal);
		}
		if (appraisal.confidence() < settings.minConfidence) {
			return refused(Verdict.LOW_CONFIDENCE, appraisal);
		}
		if (appraisal.dispersion() > settings.maxDispersion) {
			return refused(Verdict.UNSTABLE, appraisal);
		}
		if (appraisal.fairValue() < settings.minUnitValue) {
			return refused(Verdict.TOO_CHEAP, appraisal);
		}

		// The cheapest copy still on sale caps what anyone will pay, however
		// much history says the item is worth.
		long ceiling = competitor > 0 ? Math.min(appraisal.fairValue(), competitor) : appraisal.fairValue();
		double haircut = Math.min(
				MAX_HAIRCUT, appraisal.dispersion() + (1.0 - appraisal.churn()) * settings.churnHaircut);

		long sale = Math.round(ceiling * (double) count * (1.0 - settings.undercut) * (1.0 - haircut));
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
			// name, or that somebody wants it bought.
			verdict = Verdict.TOO_GOOD;
		}

		return new Assessment(verdict, sale, net, profit, margin, appraisal.confidence(), haircut, score);
	}

	private static Assessment refused(Verdict verdict, MarketModel.Appraisal appraisal) {
		return new Assessment(verdict, 0L, 0L, 0L, 0.0, appraisal.confidence(), 0.0, 0.0);
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
	public static String explain(String display, long price, Assessment assessment, String currency) {
		return String.format(
				Locale.ROOT,
				"%s  %s -> %s  +%s (%.0f%%)",
				display,
				PriceText.money(price, currency),
				PriceText.money(assessment.sale(), currency),
				PriceText.money(assessment.profit(), currency),
				assessment.margin() * 100.0);
	}
}
