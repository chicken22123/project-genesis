package com.blueprintclient.flip;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

import java.util.ArrayList;
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
	/**
	 * A fixed price item on the page.
	 *
	 * <p>Auction houses sell stacks, so the price on the listing and the price
	 * of the thing are not the same number. {@code price} is what the listing
	 * costs; {@code unitPrice} is what one of them costs, and that is what the
	 * market is measured in - otherwise a stack of sixty four looks like a
	 * wildly overpriced single.
	 */
	public record Listing(int slotId, String key, String display, long price, int count, long unitPrice) {
	}

	private final Map<String, Listing> cheapest = new HashMap<>();
	private final Map<String, Long> runnerUp = new HashMap<>();
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

			if (scan.refreshSlot < 0 && isRefresh(stack, settings)) {
				scan.refreshSlot = slot.id;
			}

			long price = AuctionParser.buyItNowPrice(stack, settings.binOnly);
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

			scan.listingCount++;
			Listing best = scan.cheapest.get(key);
			if (best == null || unitPrice < best.unitPrice()) {
				if (best != null) {
					scan.runnerUp.put(key, best.unitPrice());
				}
				scan.cheapest.put(key, new Listing(slot.id, key, display, price, count, unitPrice));
			} else {
				Long second = scan.runnerUp.get(key);
				if (second == null || unitPrice < second) {
					scan.runnerUp.put(key, unitPrice);
				}
			}
		}

		return scan;
	}

	/** The button that reloads the page: an anvil, or something named like one. */
	private static boolean isRefresh(ItemStack stack, FlipSettings settings) {
		if (stack.isOf(Items.ANVIL) || stack.isOf(Items.CHIPPED_ANVIL) || stack.isOf(Items.DAMAGED_ANVIL)) {
			return true;
		}
		return AuctionParser.isButton(stack, settings.refreshButtons);
	}

	/** The cheapest listing of each item on this page. */
	public List<Listing> listings() {
		return new ArrayList<>(cheapest.values());
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
		for (Listing listing : cheapest.values()) {
			value += listing.key().hashCode() * 31L + listing.price() + listing.count();
		}
		return value;
	}
}
