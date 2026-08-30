package com.blueprintclient.flip;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One page of an auction house, read into numbers.
 *
 * <p>Only the cheapest listing of each item matters. It is the one a buyer
 * takes, so it is the one that sets the price, and the second cheapest is what
 * the buyer would have to undercut to sell the thing on. Both come out of a
 * single pass over the slots.
 */
public final class AuctionScan {
	private final List<AuctionListing> all = new ArrayList<>();
	private final Map<String, AuctionListing> cheapest = new HashMap<>();
	private final Map<String, Long> runnerUp = new HashMap<>();
	private final Map<String, Integer> depth = new HashMap<>();
	private int refreshSlot = -1;
	private int listingCount;

	private AuctionScan() {
	}

	/** Read every non player slot of an open container. */
	public static AuctionScan of(ScreenHandler handler, FlipSettings settings) {
		AuctionScan scan = new AuctionScan();

		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof PlayerInventory) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				continue;
			}

			long price = AuctionParser.buyItNowPrice(stack, settings.binOnly);

			// Buttons come first, and only things with no price on them can be
			// buttons. An anvil is the reload button on most auction houses -
			// and it is also an item people sell, so an anvil with a price is a
			// listing and clicking it buys it.
			if (scan.refreshSlot < 0 && price <= 0 && isRefresh(stack, settings)) {
				scan.refreshSlot = slot.id;
			}

			if (price <= 0) {
				continue;
			}
			String key = AuctionParser.itemKey(stack);
			if (key.isEmpty()) {
				continue;
			}

			int count = Math.max(1, stack.getCount());
			long unitPrice = Math.max(1L, price / count);
			String display = (count > 1 ? count + "x " : "") + AuctionParser.plain(stack.getName());
			AuctionListing listing =
					new AuctionListing(slot.id, key, display, price, count, unitPrice, AuctionParser.seller(stack));

			scan.listingCount++;
			scan.all.add(listing);
			scan.depth.merge(key, 1, Integer::sum);
			AuctionListing best = scan.cheapest.get(key);
			if (best == null || unitPrice < best.unitPrice()) {
				if (best != null) {
					scan.runnerUp.put(key, best.unitPrice());
				}
				scan.cheapest.put(key, listing);
			} else {
				Long second = scan.runnerUp.get(key);
				if (second == null || unitPrice < second) {
					scan.runnerUp.put(key, unitPrice);
				}
			}
		}

		return scan;
	}

	/**
	 * The button that reloads the page: an anvil, or something named like one.
	 *
	 * <p>Only ever asked about items with no price, because the anvil on the
	 * bottom row and the anvil somebody is selling look identical otherwise.
	 */
	private static boolean isRefresh(ItemStack stack, FlipSettings settings) {
		if (stack.isOf(Items.ANVIL) || stack.isOf(Items.CHIPPED_ANVIL) || stack.isOf(Items.DAMAGED_ANVIL)) {
			return true;
		}
		return AuctionParser.isButton(stack, settings.refreshButtons);
	}

	/** The cheapest listing of each item on this page: the only ones worth buying. */
	public List<AuctionListing> listings() {
		return new ArrayList<>(cheapest.values());
	}

	/** Every listing on the page, which is what the market is measured from. */
	public List<AuctionListing> everything() {
		return Collections.unmodifiableList(all);
	}

	/** How many copies of an item are on the page - how much of a market it has. */
	public int depth(String key) {
		return depth.getOrDefault(key, 0);
	}

	/**
	 * The price per item the next seller has to beat, or 0 when there is no
	 * second copy of it on the page.
	 */
	public long competitor(String key) {
		Long second = runnerUp.get(key);
		return second == null ? 0L : second;
	}

	public int refreshSlot() {
		return refreshSlot;
	}

	public int listingCount() {
		return listingCount;
	}

	/** A cheap fingerprint of the page, for spotting a reload that changed nothing. */
	public long signature() {
		long value = listingCount;
		for (AuctionListing listing : cheapest.values()) {
			value += listing.key().hashCode() * 31L + listing.price() + listing.count();
		}
		return value;
	}
}
