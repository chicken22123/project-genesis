package com.blueprintclient.flip;

/**
 * One fixed price listing on a page of the auction house.
 *
 * <p>Auction houses sell stacks, so the price on the listing and the price of
 * the thing are not the same number. {@code price} is what the listing costs;
 * {@code unitPrice} is what one of them costs, and that is what the market is
 * measured in - otherwise a stack of sixty four looks like a wildly overpriced
 * single.
 *
 * <p>{@code slotId} is only good while the page it came from is open: it is
 * where to click, not what the item is.
 */
public record AuctionListing(
		int slotId, String key, String display, long price, int count, long unitPrice, String seller) {
}
