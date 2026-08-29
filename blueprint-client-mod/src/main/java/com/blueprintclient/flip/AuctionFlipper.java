package com.blueprintclient.flip;

import com.blueprintclient.module.Category;
import com.blueprintclient.module.Module;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Watches the auction house, works out what is underpriced, buys it and lists
 * it again.
 *
 * <p>The loop is: open the browser, read the page, feed every price into
 * {@link MarketModel}, and score each listing against what the model says the
 * item is worth. If nothing clears the thresholds the page is reloaded through
 * the anvil and read again. If something does, it is bought, and the item is
 * relisted just under the cheapest competing copy.
 *
 * <p>Two things are worth knowing before switching it on. It buys nothing for
 * the first minute or so: the model needs several sightings of an item before
 * it will price it, and until then every listing fails the sample test. And it
 * only ever clicks two kinds of slot - a listing it has priced, or a button
 * whose name is in {@link FlipSettings} - so a GUI it does not understand
 * leaves it turning over doing nothing rather than clicking blindly.
 *
 * <p>This automates play on whatever server you point it at. Most servers,
 * Hypixel included, treat auction house macros as a bannable offence, so the
 * risk is real and it is yours.
 */
public final class AuctionFlipper extends Module {
	/** Where the machine is in the loop. */
	public enum Stage {
		OFF,
		OPENING,
		BROWSING,
		BUYING,
		LISTING,
		LISTED,
		STOPPED
	}

	private static final long OPEN_TIMEOUT_MS = 5_000L;
	private static final long BUY_TIMEOUT_MS = 7_000L;
	private static final long LIST_TIMEOUT_MS = 8_000L;
	private static final long STEP_TIMEOUT_MS = 6_000L;
	private static final int MAX_ERRORS = 5;
	private static final int REPORT_LINES = 3;

	/** A listing the maths approved of, and the arithmetic behind it. */
	public record Candidate(int slotId, String key, String display, long price, FlipMath.Assessment assessment) {
	}

	private static AuctionFlipper active;

	private final MarketModel market = new MarketModel();
	private final FlipSettings settings = FlipSettings.get();
	private final Random random = new Random();
	private final Deque<Long> refreshTimes = new ArrayDeque<>();
	private final Set<Integer> clickedSlots = new HashSet<>();
	private final List<String> report = new ArrayList<>();

	private Stage stage = Stage.OFF;
	private long stageStart;
	private long nextActionAt;
	private int clickedSyncId = -1;
	private int errors;
	private boolean commandSent;
	private long lastPageSignature;

	private Candidate pending;
	private int pendingCountBefore;
	private SellFlow flow;
	private int flowIndex;
	private long stepStart;
	private boolean flowOpened;
	private long listingPrice;
	private int listingCountBefore;
	private String status = "off";

	// Running totals for the HUD.
	private long spent;
	private long expectedProfit;
	private int flips;
	private int scans;
	private int refreshes;

	// Set from the chat listener, read on the next tick.
	private volatile boolean boughtSignal;
	private volatile boolean buyFailedSignal;
	private volatile boolean listedSignal;
	private volatile boolean listFailedSignal;

	private boolean dryRun;

	public AuctionFlipper() {
		super("Auction Flipper", Category.ECONOMY, "Prices the auction house, buys the bargains and relists them.");
		active = this;
	}

	/** Where the learned prices are kept, next to the rest of the config. */
	private static Path marketFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("blueprintclient-market.properties");
	}

	public static AuctionFlipper active() {
		return active;
	}

	public void setDryRun(boolean value) {
		dryRun = value;
	}

	// ------------------------------------------------------------- lifecycle

	@Override
	protected void onEnable(MinecraftClient client) {
		market.loadIfNeeded(marketFile());
		spent = 0;
		expectedProfit = 0;
		flips = 0;
		scans = 0;
		refreshes = 0;
		errors = 0;
		pending = null;
		report.clear();
		refreshTimes.clear();
		enter(Stage.OPENING, System.currentTimeMillis());
		nextActionAt = System.currentTimeMillis() + 500L;
		status = "starting";
		tell(client, "Flipper on - budget " + money(settings.sessionBudget)
				+ ", up to " + money(settings.maxSpendPerItem) + " a listing"
				+ (dryRun ? " (dry run: nothing will be bought)" : ""));
	}

	@Override
	protected void onDisable(MinecraftClient client) {
		market.save();
		boolean stopped = stage == Stage.STOPPED;
		stage = Stage.OFF;
		pending = null;
		// A stop explains itself; a plain toggle has nothing to say.
		if (!stopped) {
			status = "off";
		}
	}

	@Override
	public void tick(MinecraftClient client) {
		if (client.player == null || client.getNetworkHandler() == null || client.interactionManager == null) {
			return;
		}

		long now = System.currentTimeMillis();
		market.maybeSave(now);

		// A screen of our own, or any other menu, means the player is doing
		// something else. Wait it out instead of counting it as a timeout.
		if (client.currentScreen != null && !(client.currentScreen instanceof HandledScreen<?>)) {
			stageStart = now;
			nextActionAt = now + 300L;
			return;
		}

		if (now < nextActionAt) {
			return;
		}

		switch (stage) {
			case OFF -> enter(Stage.OPENING, now);
			case OPENING -> opening(client, now);
			case BROWSING -> browsing(client, now);
			case BUYING -> buying(client, now);
			case LISTING -> listing(client, now);
			case LISTED -> listed(client, now);
			case STOPPED -> {
				// Waiting for the player to switch the module off and on again.
			}
		}
	}

	// ---------------------------------------------------------------- stages

	private void opening(MinecraftClient client, long now) {
		if (isBrowser(client)) {
			enter(Stage.BROWSING, now);
			return;
		}

		if (container(client) != null) {
			// Some other GUI is in the way; step out of it first.
			client.player.closeHandledScreen();
			delay(now, 300);
			return;
		}

		if (!commandSent) {
			send(client, settings.browseCommand);
			commandSent = true;
			status = "opening the auction house";
			delay(now, 700);
			return;
		}

		// The command went out and nothing opened. Give the server a moment,
		// then try again, and give up rather than sit here typing forever.
		if (now - stageStart > OPEN_TIMEOUT_MS) {
			if (++errors > MAX_ERRORS) {
				stop(client, "the auction house did not open - check flip.browseCommand");
				return;
			}
			stageStart = now;
			commandSent = false;
		}
		delay(now, 400);
	}

	private void browsing(MinecraftClient client, long now) {
		ScreenHandler handler = container(client);
		if (handler == null || !isBrowser(client)) {
			enter(Stage.OPENING, now);
			return;
		}

		// Getting this far means the browser opened and is readable, so
		// whatever went wrong earlier in the session is behind us.
		errors = 0;

		AuctionScan scan = AuctionScan.of(handler, settings);
		// Reading the same page twice - the reload was rate limited, or the
		// server sent the same listings back - would count as two independent
		// sightings and let one page pull the median towards itself.
		long signature = scan.signature();
		if (scan.listingCount() > 0 && signature != lastPageSignature) {
			lastPageSignature = signature;
			market.beginScan();
			// Every listing, not just the cheapest: how many people are selling
			// a thing, and whether their listings come and go, is as much of the
			// evidence as the prices are.
			for (AuctionScan.Listing listing : scan.everything()) {
				market.observe(listing.key(), listing.unitPrice(), listing.seller(), now);
			}
			market.endScan(now);
			scans++;
		}

		Candidate best = evaluate(scan, now);
		if (best != null && !dryRun && !inventoryHasRoom(client)) {
			stop(client, "the inventory is full - nothing bought would have anywhere to go");
			return;
		}
		if (best != null && !dryRun && affordable(best)) {
			pending = best;
			pendingCountBefore = inventoryCount(client, best.key());
			clickedSlots.clear();
			click(client, handler, best.slotId());
			status = "buying " + best.display() + " for " + money(best.price());
			MarketModel.Appraisal evidence = market.appraise(best.key(), now);
			tell(client, String.format(
					Locale.ROOT,
					"Buying %s at %s, reselling about %s: +%s (%.0f%%) - %d listings from %d sellers, "
							+ "%.0f%% of them moved, estimate cut %.0f%%",
					best.display(),
					money(best.price()),
					money(best.assessment().sale()),
					money(best.assessment().profit()),
					best.assessment().margin() * 100.0,
					evidence.samples(),
					evidence.sellers(),
					evidence.churn() * 100.0,
					best.assessment().haircut() * 100.0));
			enter(Stage.BUYING, now);
			delay(now, settings.actionDelayMs);
			return;
		}

		if (best != null && dryRun) {
			status = String.format(
					Locale.ROOT,
					"would buy %s (+%s)",
					best.display(),
					money(best.assessment().profit()));
		} else {
			status = "watching - " + market.describe();
		}

		refresh(client, handler, scan, now);
	}

	/** Reload the page through the anvil, or reopen the browser if there is no anvil. */
	private void refresh(MinecraftClient client, ScreenHandler handler, AuctionScan scan, long now) {
		while (!refreshTimes.isEmpty() && now - refreshTimes.peekFirst() > 60_000L) {
			refreshTimes.pollFirst();
		}
		if (refreshTimes.size() >= settings.maxRefreshesPerMinute) {
			// Reloading faster than this is all load and no information.
			delay(now, 1_500);
			return;
		}

		if (scan.refreshSlot() >= 0) {
			click(client, handler, scan.refreshSlot());
		} else {
			client.player.closeHandledScreen();
			enter(Stage.OPENING, now);
		}
		refreshTimes.addLast(now);
		refreshes++;
		delay(now, settings.refreshDelayMs);
	}

	/**
	 * Work through whatever the buy click opened.
	 *
	 * <p>Auction houses put anywhere from one to three screens between a click
	 * and the item - an item view, a confirmation, sometimes a collect step - so
	 * rather than encoding a particular flow this presses the buttons it
	 * recognises, once each, until the item turns up in the inventory.
	 */
	private void buying(MinecraftClient client, long now) {
		if (buyFailedSignal) {
			buyFailedSignal = false;
			boughtSignal = false;
			pending = null;
			status = "that listing was already gone";
			backToBrowsing(client, now);
			return;
		}

		// Counted rather than looked for: the player may already own one of
		// these, and "it is in my inventory" would then be true before the
		// purchase ever went through.
		if (pending != null && (boughtSignal || inventoryCount(client, pending.key()) > pendingCountBefore)) {
			boughtSignal = false;
			errors = 0;
			spent += pending.price();
			status = "bought " + pending.display();
			enter(Stage.LISTING, now);
			return;
		}

		ScreenHandler handler = container(client);
		if (handler == null) {
			if (now - stageStart > BUY_TIMEOUT_MS) {
				pending = null;
				backToBrowsing(client, now);
			}
			return;
		}

		int button = findButton(handler, settings.buyButtons);
		if (button >= 0 && clickOnce(client, handler, button)) {
			status = "confirming the purchase";
			delay(now, settings.actionDelayMs);
			return;
		}

		if (now - stageStart > BUY_TIMEOUT_MS) {
			// Either the purchase went through quietly or the flow is one we do
			// not know; either way the inventory check above is the source of
			// truth, and it has not fired.
			errors++;
			pending = null;
			if (errors > MAX_ERRORS) {
				stop(client, "could not work out the buy screen - check flip.buyButtons");
				return;
			}
			backToBrowsing(client, now);
		}
	}

	/** List the item again: down the configured menu chain, or by command. */
	private void listing(MinecraftClient client, long now) {
		if (pending == null) {
			backToBrowsing(client, now);
			return;
		}

		if (flow == null) {
			flow = SellFlow.parse(settings.sellFlow);
			flowIndex = 0;
			flowOpened = false;
			stepStart = now;
			listingPrice = FlipMath.askingPrice(pending.price(), pending.assessment().sale(), settings);
			tell(client, "Listing " + pending.display() + " at " + money(listingPrice));
		}

		if (!flow.isEmpty()) {
			walkSellFlow(client, now);
			return;
		}

		listByCommand(client, now);
	}

	/**
	 * The {@code /ah sell 1230000} case: the server lists whatever is in hand.
	 *
	 * <p>Which means the item has to actually be in hand before the command
	 * goes anywhere. It arrives from a purchase wherever there was room, so it
	 * gets swapped into the held slot first, and the command is only sent once
	 * the held stack is the right one - a sell command sent while holding a
	 * pickaxe lists the pickaxe.
	 */
	private void listByCommand(MinecraftClient client, long now) {
		if (client.currentScreen != null) {
			// The swap goes through the player's own inventory, which is not
			// the handler on screen while a menu is open.
			client.player.closeHandledScreen();
			delay(now, 400);
			return;
		}

		if (now - stageStart > LIST_TIMEOUT_MS) {
			tell(client, "Could not get " + describePending() + " into my hand to sell it");
			errors++;
			pending = null;
			if (errors > MAX_ERRORS) {
				stop(client, "the bought items cannot be put in hand to be sold");
				return;
			}
			backToBrowsing(client, now);
			return;
		}

		int slotId = inventorySlot(client, pending.key());
		if (slotId < 0) {
			tell(client, "Bought item is not in the inventory, skipping the relist");
			pending = null;
			backToBrowsing(client, now);
			return;
		}

		int selected = client.player.getInventory().getSelectedSlot();
		Slot slot = client.player.playerScreenHandler.getSlot(slotId);
		if (slot.getIndex() != selected) {
			// Swap it into the hand: the sell command reads what is held.
			client.interactionManager.clickSlot(
					client.player.playerScreenHandler.syncId,
					slotId,
					selected,
					SlotActionType.SWAP,
					client.player);
			delay(now, 300);
			return;
		}

		// What the hand holds now, so the listing can be told to have worked by
		// the item leaving rather than by hoping the server's wording matches.
		listingCountBefore = inventoryCount(client, pending.key());
		send(client, settings.sellCommandFor(listingPrice));
		status = "listing " + pending.display() + " at " + money(listingPrice);
		listedSignal = false;
		listFailedSignal = false;
		enter(Stage.LISTED, now);
		delay(now, 700);
	}

	/** Confirm the listing if the server asks, then go back to browsing. */
	private void listed(MinecraftClient client, long now) {
		if (listFailedSignal) {
			listFailedSignal = false;
			listedSignal = false;
			tell(client, "The server would not list " + describePending()
					+ " - an auction limit, or a price it will not take");
			errors++;
			pending = null;
			if (errors > MAX_ERRORS) {
				stop(client, "the server keeps refusing to list what is bought");
				return;
			}
			backToBrowsing(client, now);
			return;
		}

		// The item leaving the inventory is the listing having worked. It says
		// so whatever the server's wording, and it is the same thing the buy
		// side trusts.
		// Only the command path can be judged this way. In a menu chain the item
		// leaves the inventory when it goes into the auction slot, which is
		// several clicks before the listing is actually made.
		boolean gone = settings.sellFlow.isBlank()
				&& pending != null
				&& inventoryCount(client, pending.key()) < listingCountBefore;
		if (listedSignal || gone) {
			listedSignal = false;
			finishListing(client, now);
			return;
		}

		// With a chain configured, the chain did the confirming; pressing more
		// buttons here would be clicking at a menu we no longer understand.
		if (settings.sellFlow.isBlank()) {
			ScreenHandler handler = container(client);
			if (handler != null) {
				int button = findButton(handler, settings.sellButtons);
				if (button >= 0 && clickOnce(client, handler, button)) {
					delay(now, settings.actionDelayMs);
					return;
				}
			}
		}

		if (now - stageStart > LIST_TIMEOUT_MS) {
			// The item is still here and nothing was said about it, so the
			// command did not do what it was meant to.
			tell(client, "Still holding " + describePending() + " - check flip.sellCommand");
			errors++;
			pending = null;
			if (errors > MAX_ERRORS) {
				stop(client, "nothing bought is getting listed - check flip.sellCommand");
				return;
			}
			backToBrowsing(client, now);
		}
	}

	private String describePending() {
		return pending == null ? "the item" : pending.display();
	}

	private void finishListing(MinecraftClient client, long now) {
		if (pending != null) {
			flips++;
			expectedProfit += pending.assessment().profit();
		}
		pending = null;

		if (settings.stopAfterFlips > 0 && flips >= settings.stopAfterFlips) {
			stop(client, "done " + flips + " flips");
			return;
		}
		if (settings.sessionBudget > 0 && spent >= settings.sessionBudget) {
			stop(client, "session budget of " + money(settings.sessionBudget) + " is spent");
			return;
		}
		backToBrowsing(client, now);
	}

	private void backToBrowsing(MinecraftClient client, long now) {
		if (isBrowser(client)) {
			enter(Stage.BROWSING, now);
		} else {
			enter(Stage.OPENING, now);
		}
		delay(now, settings.actionDelayMs);
	}

	// ----------------------------------------------------------------- maths

	/**
	 * Price every listing on the page and pick the best one worth buying.
	 *
	 * <p>The arithmetic itself is {@link FlipMath}; what happens here is the
	 * bookkeeping around it - the ranking, and a tally of why the rest were
	 * turned down, which is the only way to tell a quiet market from a
	 * misconfigured one while watching it work.
	 */
	private Candidate evaluate(AuctionScan scan, long now) {
		List<Candidate> found = new ArrayList<>();
		Map<FlipMath.Verdict, Integer> tally = new EnumMap<>(FlipMath.Verdict.class);

		for (AuctionScan.Listing listing : scan.listings()) {
			MarketModel.Appraisal appraisal = market.appraise(listing.key(), now);
			FlipMath.Assessment assessment = FlipMath.assess(
					listing.display(),
					listing.price(),
					listing.count(),
					scan.competitor(listing.key()),
					scan.depth(listing.key()),
					appraisal,
					settings);
			tally.merge(assessment.verdict(), 1, Integer::sum);
			if (assessment.worthBuying()) {
				found.add(new Candidate(
						listing.slotId(), listing.key(), listing.display(), listing.price(), assessment));
			}
		}

		found.sort((left, right) -> Double.compare(right.assessment().score(), left.assessment().score()));

		report.clear();
		report.add(summarise(scan.listingCount(), tally));
		for (int i = 0; i < Math.min(REPORT_LINES, found.size()); i++) {
			Candidate candidate = found.get(i);
			report.add(FlipMath.explain(
					shorten(candidate.display()), candidate.price(), candidate.assessment(), settings.currency));
		}

		return found.isEmpty() ? null : found.get(0);
	}

	/** "42 listings: 28 not priced yet, 11 too new, 3 worth buying". */
	private static String summarise(int listings, Map<FlipMath.Verdict, Integer> tally) {
		StringBuilder line = new StringBuilder().append(listings).append(" listings");
		boolean first = true;
		for (Map.Entry<FlipMath.Verdict, Integer> entry : tally.entrySet()) {
			line.append(first ? ": " : ", ").append(entry.getValue()).append(' ').append(entry.getKey().description());
			first = false;
		}
		return line.toString();
	}

	private boolean affordable(Candidate candidate) {
		// Zero is no ceiling, the same as it means to the maths.
		if (settings.maxSpendPerItem > 0 && candidate.price() > settings.maxSpendPerItem) {
			return false;
		}
		return settings.sessionBudget <= 0 || spent + candidate.price() <= settings.sessionBudget;
	}

	// --------------------------------------------------------------- clicking

	private void click(MinecraftClient client, ScreenHandler handler, int slotId) {
		if (slotId < 0 || client.interactionManager == null || client.player == null) {
			return;
		}
		client.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.PICKUP, client.player);
	}

	/** Click a button at most once per screen, so a slow server is not spammed. */
	private boolean clickOnce(MinecraftClient client, ScreenHandler handler, int slotId) {
		if (handler.syncId != clickedSyncId) {
			clickedSyncId = handler.syncId;
			clickedSlots.clear();
		}
		if (!clickedSlots.add(slotId)) {
			return false;
		}
		click(client, handler, slotId);
		return true;
	}

	private int findButton(ScreenHandler handler, List<String> names) {
		for (Slot slot : handler.slots) {
			if (slot.inventory instanceof PlayerInventory) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (!stack.isEmpty() && AuctionParser.isButton(stack, names)) {
				return slot.id;
			}
		}
		return -1;
	}

	/** The player inventory slot holding an item with this key, or -1. */
	private int inventorySlot(MinecraftClient client, String key) {
		PlayerScreenHandler handler = client.player.playerScreenHandler;
		for (Slot slot : handler.slots) {
			if (!(slot.inventory instanceof PlayerInventory)) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (!stack.isEmpty() && key.equals(AuctionParser.itemKey(stack))) {
				return slot.id;
			}
		}
		return -1;
	}

	/** Whether a purchase would have somewhere to land. */
	private boolean inventoryHasRoom(MinecraftClient client) {
		for (Slot slot : client.player.playerScreenHandler.slots) {
			// Below 36 is the inventory proper and the hotbar; the armour and
			// offhand slots above it are no use to a bought item.
			if (slot.inventory instanceof PlayerInventory && slot.getIndex() < 36 && !slot.hasStack()) {
				return true;
			}
		}
		return false;
	}

	/** How many stacks of this item the player is carrying. */
	private int inventoryCount(MinecraftClient client, String key) {
		int count = 0;
		for (Slot slot : client.player.playerScreenHandler.slots) {
			if (!(slot.inventory instanceof PlayerInventory)) {
				continue;
			}
			ItemStack stack = slot.getStack();
			if (!stack.isEmpty() && key.equals(AuctionParser.itemKey(stack))) {
				count++;
			}
		}
		return count;
	}

	// ----------------------------------------------------------------- state

	private void enter(Stage next, long now) {
		stage = next;
		stageStart = now;
		flow = null;
		clickedSyncId = -1;
		commandSent = false;
		clickedSlots.clear();
	}

	private void delay(long now, int base) {
		nextActionAt = now + base + random.nextInt(Math.max(1, settings.actionJitterMs));
	}

	private void stop(MinecraftClient client, String reason) {
		stage = Stage.STOPPED;
		status = "stopped: " + reason;
		pending = null;
		tell(client, "Flipper stopped - " + reason);
		market.save();
		setEnabled(false);
	}

	private ScreenHandler container(MinecraftClient client) {
		if (client.currentScreen instanceof HandledScreen<?> screen) {
			ScreenHandler handler = screen.getScreenHandler();
			return handler instanceof PlayerScreenHandler ? null : handler;
		}
		return null;
	}

	private boolean isBrowser(MinecraftClient client) {
		if (container(client) == null || client.currentScreen == null) {
			return false;
		}
		String title = AuctionParser.plain(client.currentScreen.getTitle()).toLowerCase(Locale.ROOT);
		for (String needle : settings.browseTitles) {
			if (title.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private void send(MinecraftClient client, String command) {
		if (client.getNetworkHandler() == null) {
			return;
		}
		String trimmed = command.trim();
		if (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1);
		}
		if (!trimmed.isEmpty()) {
			client.getNetworkHandler().sendChatCommand(trimmed);
		}
	}

	/** A plain chat message, for a menu that asks you to type something. */
	private void sendChat(MinecraftClient client, String message) {
		if (client.getNetworkHandler() != null && !message.isBlank()) {
			client.getNetworkHandler().sendChatMessage(message);
		}
	}

	private void tell(MinecraftClient client, String message) {
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[Blueprint] " + message), false);
		}
	}

	// ------------------------------------------------------------------- chat

	/** Called for every server message, so the machine can hear how a click went. */
	public static void onServerMessage(String raw) {
		AuctionFlipper flipper = active;
		if (flipper == null || !flipper.isEnabled()) {
			return;
		}
		String message = raw.toLowerCase(Locale.ROOT);
		FlipSettings settings = flipper.settings;
		if (contains(message, settings.boughtMessages)) {
			flipper.boughtSignal = true;
		}
		if (contains(message, settings.buyFailedMessages)) {
			flipper.buyFailedSignal = true;
		}
		if (contains(message, settings.listedMessages)) {
			flipper.listedSignal = true;
		}
		if (contains(message, settings.listFailedMessages)) {
			flipper.listFailedSignal = true;
		}
	}

	private static boolean contains(String message, List<String> needles) {
		for (String needle : needles) {
			if (!needle.isEmpty() && message.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	// --------------------------------------------------------------- readouts

	public Stage stage() {
		return stage;
	}

	public String statusLine() {
		return status;
	}

	public MarketModel market() {
		return market;
	}

	public List<String> report() {
		return report;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	/** The HUD line: what it is doing, and what it has done. */
	public String summary() {
		return String.format(
				Locale.ROOT,
				"Flip: %s | %d scans | %d flips | spent %s | est %s%s",
				stage.name().toLowerCase(Locale.ROOT),
				scans,
				flips,
				money(spent),
				money(expectedProfit),
				dryRun ? " | dry run" : "");
	}

	/** The panel drawn over the auction house, so the reasoning is visible. */
	public void renderOverlay(DrawContext context, MinecraftClient client) {
		if (!isEnabled() || client.textRenderer == null) {
			return;
		}

		List<String> lines = new ArrayList<>();
		lines.add(summary());
		lines.add(status);
		if (report.isEmpty()) {
			lines.add("no listing clears the thresholds yet");
		} else {
			lines.addAll(report);
		}

		int width = 0;
		for (String line : lines) {
			width = Math.max(width, client.textRenderer.getWidth(line));
		}
		int height = lines.size() * (client.textRenderer.fontHeight + 2) + 6;
		int x = 4;
		int y = 4;

		context.fill(x, y, x + width + 8, y + height, 0xC0050D1A);
		context.fill(x, y, x + width + 8, y + 1, 0xFF1B3A63);
		context.fill(x, y + height - 1, x + width + 8, y + height, 0xFF1B3A63);

		int line = y + 4;
		for (String text : lines) {
			context.drawTextWithShadow(client.textRenderer, text, x + 4, line, HUD_COLOR);
			line += client.textRenderer.fontHeight + 2;
		}
	}

	/** An amount with the server's currency in front of it. */
	private String money(long amount) {
		return PriceText.money(amount, settings.currency);
	}

	private static String shorten(String display) {
		return display.length() <= 22 ? display : display.substring(0, 21) + "…";
	}
}
