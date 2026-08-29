package com.blueprintclient.flip;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * The bridge from an auction house GUI to {@link PriceText}.
 *
 * <p>An auction house is a chest full of items whose lore says the price, so
 * everything the flipper needs comes off the display name and the lore lines.
 * This class does the unwrapping; the judgement about what those strings mean
 * lives next door, where it can be tested.
 */
public final class AuctionParser {
	private AuctionParser() {
	}

	/** A {@link Text} with the colour codes taken back out. */
	public static String plain(Text text) {
		if (text == null) {
			return "";
		}
		String stripped = Formatting.strip(text.getString());
		return stripped == null ? "" : stripped.trim();
	}

	public static List<String> lore(ItemStack stack) {
		List<String> lines = new ArrayList<>();
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore != null) {
			for (Text line : lore.lines()) {
				lines.add(plain(line));
			}
		}
		return lines;
	}

	/** The name two listings have to share to be worth comparing. */
	public static String itemKey(ItemStack stack) {
		return PriceText.itemKey(plain(stack.getName()), lore(stack));
	}

	/** The buy it now price on a listing, or -1 when there is not one. */
	public static long buyItNowPrice(ItemStack stack, boolean binOnly) {
		return PriceText.buyItNowPrice(lore(stack), binOnly);
	}

	/** Whether an item is one of the buttons named in the settings. */
	public static boolean isButton(ItemStack stack, List<String> names) {
		return PriceText.isButton(plain(stack.getName()), names);
	}

	/** Coins, short enough for a HUD line: 1234567 becomes "1.23m". */
	public static String coins(long amount) {
		return PriceText.coins(amount);
	}
}
