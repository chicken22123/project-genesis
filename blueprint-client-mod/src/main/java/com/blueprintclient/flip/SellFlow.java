package com.blueprintclient.flip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How an item gets listed on an auction house that has no sell command.
 *
 * <p>Plenty of servers - Hypixel among them - have nothing like
 * {@code /ah sell 1000}. Listing something means walking a chain of menus:
 * manage auctions, create auction, put the item in the slot, set a price, press
 * confirm. The chain differs from server to server, so rather than guessing it,
 * the flipper reads it from one setting:
 *
 * <pre>
 *   flip.sellFlow = button:manage auctions, button:create auction, item,
 *                   button:custom amount, price, button:create auction
 * </pre>
 *
 * <ul>
 *   <li>{@code button:name} - click the item in the open menu whose name
 *       contains {@code name};
 *   <li>{@code item} - click the bought item in the inventory half of the menu,
 *       which is how most auction houses take it from you;
 *   <li>{@code price} - send the asking price. Plain by default, for servers
 *       that ask you to type it in chat; {@code price:/ah price %price%} sends a
 *       command instead;
 *   <li>{@code wait:800} - stand still for that many milliseconds, for a menu
 *       that takes its time.
 * </ul>
 *
 * <p>Left empty, the flipper uses {@code flip.sellCommand} instead, which is all
 * a server with a real sell command needs.
 */
public final class SellFlow {
	/** What one step of the chain does. */
	public enum Kind {
		BUTTON,
		ITEM,
		PRICE,
		WAIT
	}

	/** One step: a button name, a price template, or a pause. */
	public record Step(Kind kind, String argument, long millis) {
		public String describe() {
			return switch (kind) {
				case BUTTON -> "click \"" + argument + '"';
				case ITEM -> "hand over the item";
				case PRICE -> "send the price";
				case WAIT -> "wait " + millis + "ms";
			};
		}
	}

	private static final SellFlow EMPTY = new SellFlow(List.of());
	private static final long MAX_WAIT_MS = 10_000L;

	private final List<Step> steps;

	private SellFlow(List<Step> steps) {
		this.steps = Collections.unmodifiableList(steps);
	}

	/** Read the chain out of a setting. Anything unreadable is left out. */
	public static SellFlow parse(String text) {
		if (text == null || text.isBlank()) {
			return EMPTY;
		}

		List<Step> steps = new ArrayList<>();
		for (String part : text.split(",")) {
			String token = part.trim();
			if (token.isEmpty()) {
				continue;
			}

			String head = token;
			String argument = "";
			int colon = token.indexOf(':');
			if (colon >= 0) {
				head = token.substring(0, colon).trim();
				argument = token.substring(colon + 1).trim();
			}

			switch (head.toLowerCase(Locale.ROOT)) {
				case "button" -> {
					if (!argument.isEmpty()) {
						steps.add(new Step(Kind.BUTTON, argument.toLowerCase(Locale.ROOT), 0L));
					}
				}
				case "item" -> steps.add(new Step(Kind.ITEM, "", 0L));
				case "price" -> steps.add(new Step(Kind.PRICE, argument, 0L));
				case "wait" -> {
					long millis = readMillis(argument);
					if (millis > 0) {
						steps.add(new Step(Kind.WAIT, "", millis));
					}
				}
				// A bare word is almost always meant to be a button.
				default -> steps.add(new Step(Kind.BUTTON, token.toLowerCase(Locale.ROOT), 0L));
			}
		}

		return steps.isEmpty() ? EMPTY : new SellFlow(steps);
	}

	private static long readMillis(String argument) {
		try {
			return Math.min(MAX_WAIT_MS, Math.max(0L, Long.parseLong(argument.trim())));
		} catch (NumberFormatException exception) {
			return 0L;
		}
	}

	public boolean isEmpty() {
		return steps.isEmpty();
	}

	public int size() {
		return steps.size();
	}

	public Step step(int index) {
		return steps.get(index);
	}

	public List<Step> steps() {
		return steps;
	}

	/** What to send for a price step: a bare number, or the template filled in. */
	public static String priceMessage(Step step, long price) {
		if (step.argument().isEmpty()) {
			return Long.toString(price);
		}
		return step.argument().replace("%price%", Long.toString(price));
	}
}
