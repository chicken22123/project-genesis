package com.blueprintclient.flip;

import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
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
	/**
	 * What makes an item itself, beyond its name.
	 *
	 * <p>An auction house full of vanilla gear is full of items that look
	 * identical in a list and are worth wildly different amounts: an enchanted
	 * sword beside a plain one, a full shulker box beside an empty one. These
	 * are the components that tell them apart. The lore is deliberately not one
	 * of them - the auction house writes the price and the seller into it, so it
	 * differs on every listing of the same thing.
	 */
	private static final ComponentType<?>[] IDENTITY = {
		DataComponentTypes.ENCHANTMENTS,
		DataComponentTypes.STORED_ENCHANTMENTS,
		DataComponentTypes.POTION_CONTENTS,
		DataComponentTypes.CONTAINER,
		DataComponentTypes.CUSTOM_NAME,
	};

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
		return PriceText.itemKey(plain(stack.getName()), lore(stack), identity(stack));
	}

	/** A stamp covering the item type and everything enchanted or stored on it. */
	private static String identity(ItemStack stack) {
		String[] parts = new String[IDENTITY.length + 1];
		parts[0] = Registries.ITEM.getId(stack.getItem()).toString();
		for (int i = 0; i < IDENTITY.length; i++) {
			Object value = stack.get(IDENTITY[i]);
			parts[i + 1] = value == null ? "" : value.toString();
		}
		return PriceText.identity(parts);
	}

	/** The buy it now price on a listing, or -1 when there is not one. */
	public static long buyItNowPrice(ItemStack stack, boolean binOnly) {
		return PriceText.buyItNowPrice(lore(stack), binOnly);
	}

	/** Who has this listed, or empty when the auction house does not say. */
	public static String seller(ItemStack stack) {
		return PriceText.seller(lore(stack));
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
