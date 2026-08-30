package com.blueprintclient.flip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything on sale across a sweep of the auction house, not just one page.
 *
 * <p>A big auction house opens on whatever was listed most recently, which is
 * mostly junk, and the same item almost never appears three times on it. Judging
 * the market from that one page means never gathering enough evidence to buy
 * anything. So the flipper walks several pages before deciding, and this holds
 * what it has seen: how many copies of each item are on sale, what the cheapest
 * one costs, and what the next cheapest costs - the price a reseller would have
 * to beat.
 */
public final class AuctionBoard {
	private final Map<String, Integer> depth = new HashMap<>();
	private final Map<String, Long> cheapest = new HashMap<>();
	private final Map<String, Long> runnerUp = new HashMap<>();
	private final List<AuctionListing> all = new ArrayList<>();

	/** Fold one page into the board. */
	public void add(List<AuctionListing> listings) {
		for (AuctionListing listing : listings) {
			all.add(listing);
			depth.merge(listing.key(), 1, Integer::sum);

			long unit = listing.unitPrice();
			Long best = cheapest.get(listing.key());
			if (best == null || unit < best) {
				if (best != null) {
					runnerUp.put(listing.key(), best);
				}
				cheapest.put(listing.key(), unit);
			} else {
				Long second = runnerUp.get(listing.key());
				if (second == null || unit < second) {
					runnerUp.put(listing.key(), unit);
				}
			}
		}
	}

	/** How many copies of an item are on sale: how much of a market it has. */
	public int depth(String key) {
		return depth.getOrDefault(key, 0);
	}

	/**
	 * The price per item the next seller has to beat, or 0 when this is the only
	 * copy anywhere in the sweep.
	 */
	public long competitor(String key) {
		Long second = runnerUp.get(key);
		return second == null ? 0L : second;
	}

	public long cheapest(String key) {
		Long best = cheapest.get(key);
		return best == null ? 0L : best;
	}

	public List<AuctionListing> everything() {
		return all;
	}

	public int listingCount() {
		return all.size();
	}

	public int itemCount() {
		return depth.size();
	}

	public boolean isEmpty() {
		return all.isEmpty();
	}
}
