package com.blueprintclient.flip;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * What every item is worth, learned from the auction house itself.
 *
 * <p>There is no price list to download, and there is a trap in the only data
 * there is: <b>an asking price is not a sale price</b>. Anyone can list dirt for
 * two thousand and leave it there. A model that reads that as "dirt is worth two
 * thousand" will happily pay one thousand for dirt, and then own some dirt.
 *
 * <p>So the model is built out of things that are harder to fake than a number
 * on a sign:
 *
 * <ul>
 *   <li><b>Distinct listings, counted once.</b> A listing is recognised by its
 *       price and its seller, so the same listing seen on twenty refreshes is
 *       one piece of evidence, not twenty. This is what stops a single planted
 *       listing looking like a market.
 *   <li><b>How many different people are selling it.</b> One person can list an
 *       item ten times; ten people agreeing on a price is a market.
 *   <li><b>Churn: whether listings actually come and go.</b> Something priced
 *       where people buy it does not sit on the front page for ever. A price
 *       nothing ever moves at is a price nobody pays.
 *   <li><b>A low quantile, not the middle.</b> The fair value is the price a
 *       thirtieth of the way up the asks, because to sell something you have to
 *       beat the cheapest seller, not the average one. Optimistic asks sit in
 *       the tail above it and cannot drag it up.
 * </ul>
 *
 * <p>Spread is measured with the median absolute deviation, which one silly
 * listing cannot move, and confidence combines everything above: enough distinct
 * listings, recent ones, from enough people, in a market that moves, agreeing
 * with each other.
 */
public final class MarketModel {
	/** Old prices describe an old market. */
	private static final long SAMPLE_TTL_MS = 6L * 60L * 60L * 1000L;
	private static final int MAX_SAMPLES_PER_ITEM = 64;
	private static final int MAX_SELLERS_PER_ITEM = 32;
	private static final int MAX_ITEMS = 4_000;
	private static final long SAVE_INTERVAL_MS = 120_000L;

	/** How fast confidence decays with age, and how many samples count as "enough". */
	private static final double RECENCY_HALF_LIFE_MINUTES = 45.0;
	private static final double SAMPLE_SOFTENER = 3.0;

	/** Turning a median absolute deviation into something like a standard deviation. */
	private static final double MAD_TO_SIGMA = 1.4826;
	private static final double OUTLIER_SIGMAS = 3.0;
	/** No listing within this fraction of the median is an outlier, however tight the market. */
	private static final double OUTLIER_FLOOR = 0.05;

	/**
	 * Where in the asks the fair value sits. Selling means beating the cheapest
	 * seller, so the useful number is near the bottom of them - but not the very
	 * bottom, which is one person having a bad day.
	 */
	private static final double LOW_QUANTILE = 0.30;

	/** What the model believes about one item. */
	public record Appraisal(
			long fairValue,
			double dispersion,
			double confidence,
			int samples,
			double supplyRate,
			int sellers,
			double churn) {
		public boolean isKnown() {
			return samples > 0 && fairValue > 0;
		}
	}

	private static final Appraisal UNKNOWN = new Appraisal(0L, 1.0, 0.0, 0, 0.0, 0, 0.0);

	private record Sample(long price, long time) {
	}

	/** A listing the model has its eye on, so it can notice when it goes. */
	private record Live(long price, long lastSeen) {
	}

	private static final class Series {
		private final Deque<Sample> samples = new ArrayDeque<>();
		private final Map<String, Live> live = new LinkedHashMap<>();
		private final Map<String, Long> sellers = new LinkedHashMap<>();
		private long lastSeen;
		private int appearances;
		private int vanished;
	}

	// Access order, so the least recently seen item is the one evicted when the
	// database gets too big.
	private final Map<String, Series> items = new LinkedHashMap<>(16, 0.75f, true);
	private final Map<String, Set<String>> seenThisScan = new HashMap<>();

	private long scans;
	private Path file;
	private boolean loaded;
	private boolean dirty;
	private long lastSave;

	// ------------------------------------------------------------- recording

	/** Call before the listings from one page of the auction house. */
	public void beginScan() {
		seenThisScan.clear();
		scans++;
	}

	/**
	 * Record one listing on the page.
	 *
	 * <p>Called for every listing, not just the cheapest: the count of different
	 * sellers and whether listings come and go are as much a part of the picture
	 * as the prices are.
	 *
	 * @param unitPrice what one of the item costs, so a stack and a single are
	 *     the same evidence
	 * @param seller who is selling, or empty when the auction house does not say
	 */
	public void observe(String key, long unitPrice, String seller, long now) {
		if (key == null || key.isEmpty() || unitPrice <= 0) {
			return;
		}

		Series series = items.computeIfAbsent(key, ignored -> new Series());
		Set<String> seen = seenThisScan.computeIfAbsent(key, ignored -> new HashSet<>());
		if (seen.isEmpty()) {
			// First listing of this item on this page: the item appeared once,
			// however many copies of it are here.
			series.appearances++;
		}

		String fingerprint = unitPrice + "/" + seller;
		seen.add(fingerprint);

		if (!series.live.containsKey(fingerprint)) {
			// A listing never seen before is one new piece of evidence. Seeing
			// the same one again on the next refresh is not.
			series.samples.addLast(new Sample(unitPrice, now));
			while (series.samples.size() > MAX_SAMPLES_PER_ITEM) {
				series.samples.pollFirst();
			}
			dirty = true;
		}
		series.live.put(fingerprint, new Live(unitPrice, now));

		if (!seller.isEmpty()) {
			series.sellers.put(seller, now);
			while (series.sellers.size() > MAX_SELLERS_PER_ITEM) {
				Iterator<String> oldest = series.sellers.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
		}

		series.lastSeen = now;

		if (items.size() > MAX_ITEMS) {
			Iterator<String> oldest = items.keySet().iterator();
			if (oldest.hasNext()) {
				oldest.next();
				oldest.remove();
			}
		}
	}

	/**
	 * Call after the listings from one page: whatever was there last time and is
	 * not there now has moved.
	 *
	 * <p>Moved, not sold - it may have been bought, cancelled, or pushed onto
	 * the next page by newer listings. What it is evidence of is an item people
	 * are doing something about, which is the opposite of a planted price that
	 * sits untouched for ever.
	 */
	public void endScan(long now) {
		for (Map.Entry<String, Set<String>> entry : seenThisScan.entrySet()) {
			Series series = items.get(entry.getKey());
			if (series == null) {
				continue;
			}
			Iterator<Map.Entry<String, Live>> live = series.live.entrySet().iterator();
			while (live.hasNext()) {
				Map.Entry<String, Live> listing = live.next();
				if (!entry.getValue().contains(listing.getKey())) {
					live.remove();
					series.vanished++;
					dirty = true;
				}
			}
		}
		seenThisScan.clear();
	}

	// ------------------------------------------------------------- appraisal

	/** What the model thinks one of an item is worth, and how much it means it. */
	public Appraisal appraise(String key, long now) {
		Series series = items.get(key);
		if (series == null) {
			return UNKNOWN;
		}

		expire(series, now);
		if (series.samples.isEmpty()) {
			return UNKNOWN;
		}

		List<Long> prices = new ArrayList<>(series.samples.size());
		for (Sample sample : series.samples) {
			prices.add(sample.price());
		}
		Collections.sort(prices);

		double median = median(prices);
		double deviation = medianAbsoluteDeviation(prices, median);

		// The deviation is scaled into the same units as a standard deviation
		// (the 1.4826 is the usual constant for that), and floored at a few per
		// cent of the price. Without the floor a handful of samples that happen
		// to agree closely makes the deviation tiny, and the next honest listing
		// a couple of per cent away gets thrown out as an outlier.
		double tolerance = Math.max(OUTLIER_SIGMAS * MAD_TO_SIGMA * deviation, OUTLIER_FLOOR * median);
		List<Long> kept = new ArrayList<>(prices.size());
		for (long price : prices) {
			if (Math.abs(price - median) <= tolerance) {
				kept.add(price);
			}
		}
		if (kept.isEmpty()) {
			kept = prices;
		}

		long fair = Math.round(quantile(kept, LOW_QUANTILE));
		double dispersion = median > 0.0 ? Math.min(1.0, deviation / median) : 1.0;

		int count = kept.size();
		int sellers = countSellers(series, now);
		double churn = churn(series);

		double sampleTerm = count / (count + SAMPLE_SOFTENER);
		double ageMinutes = Math.max(0.0, (now - series.lastSeen) / 60_000.0);
		double freshTerm = Math.exp(-ageMinutes / RECENCY_HALF_LIFE_MINUTES);
		double agreementTerm = Math.max(0.05, 1.0 - dispersion);
		// One seller is one opinion; several agreeing is a price.
		double sellerTerm = sellers <= 0 ? 0.5 : sellers / (sellers + 1.0) * 1.5;
		// A market where nothing ever moves is a market where nothing sells.
		double churnTerm = 0.4 + 0.6 * churn;
		double confidence = clamp(sampleTerm * freshTerm * agreementTerm * Math.min(1.0, sellerTerm) * churnTerm);

		double supplyRate = scans <= 0 ? 0.0 : clamp(series.appearances / (double) scans);

		return new Appraisal(fair, dispersion, confidence, count, supplyRate, sellers, churn);
	}

	private int countSellers(Series series, long now) {
		series.sellers.values().removeIf(seen -> now - seen > SAMPLE_TTL_MS);
		return series.sellers.size();
	}

	/** The share of the listings we have watched that have since gone. */
	private static double churn(Series series) {
		int watched = series.vanished + series.live.size();
		return watched <= 0 ? 0.0 : clamp(series.vanished / (double) watched);
	}

	private void expire(Series series, long now) {
		while (!series.samples.isEmpty() && now - series.samples.peekFirst().time() > SAMPLE_TTL_MS) {
			series.samples.pollFirst();
			dirty = true;
		}
		series.live.values().removeIf(listing -> now - listing.lastSeen() > SAMPLE_TTL_MS);
	}

	private static double median(List<Long> sorted) {
		return quantile(sorted, 0.5);
	}

	/** The value a given way up a sorted list, interpolating between samples. */
	private static double quantile(List<Long> sorted, double fraction) {
		int size = sorted.size();
		if (size == 0) {
			return 0.0;
		}
		if (size == 1) {
			return sorted.get(0);
		}
		double position = fraction * (size - 1);
		int below = (int) Math.floor(position);
		int above = Math.min(size - 1, below + 1);
		double between = position - below;
		return sorted.get(below) * (1.0 - between) + sorted.get(above) * between;
	}

	private static double medianAbsoluteDeviation(List<Long> sorted, double median) {
		List<Long> distances = new ArrayList<>(sorted.size());
		for (long price : sorted) {
			distances.add(Math.round(Math.abs(price - median)));
		}
		Collections.sort(distances);
		return median(distances);
	}

	private static double clamp(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	public int trackedItems() {
		return items.size();
	}

	public long scanCount() {
		return scans;
	}

	// ----------------------------------------------------------- persistence

	/**
	 * Read the price history back, so a session starts knowing the market.
	 *
	 * <p>One line per item:
	 * {@code appearances;vanished;seller|seller;price:age,price:age}, with ages
	 * written relative to the save so a clock change cannot make every sample
	 * look like it came from the future.
	 */
	public void loadIfNeeded(Path source) {
		if (loaded) {
			return;
		}
		loaded = true;
		file = source;
		lastSave = System.currentTimeMillis();

		if (file == null || !Files.isRegularFile(file)) {
			return;
		}

		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(file)) {
			properties.load(input);
		} catch (IOException exception) {
			return;
		}

		long now = System.currentTimeMillis();
		try {
			scans = Long.parseLong(properties.getProperty("scans", "0").trim());
		} catch (NumberFormatException exception) {
			scans = 0;
		}

		for (String name : properties.stringPropertyNames()) {
			if (!name.startsWith("item.")) {
				continue;
			}
			String key = name.substring("item.".length());
			String[] parts = properties.getProperty(name).split(";", 4);
			if (parts.length != 4) {
				continue;
			}

			Series series = new Series();
			try {
				series.appearances = Integer.parseInt(parts[0].trim());
				series.vanished = Integer.parseInt(parts[1].trim());
			} catch (NumberFormatException exception) {
				continue;
			}
			for (String seller : parts[2].split("\\|")) {
				if (!seller.isBlank()) {
					series.sellers.put(seller.trim(), now);
				}
			}
			for (String entry : parts[3].split(",")) {
				String[] sample = entry.split(":");
				if (sample.length != 2) {
					continue;
				}
				try {
					long price = Long.parseLong(sample[0].trim());
					long age = Long.parseLong(sample[1].trim());
					if (price > 0 && age >= 0 && age < SAMPLE_TTL_MS) {
						series.samples.addLast(new Sample(price, now - age));
						series.lastSeen = Math.max(series.lastSeen, now - age);
					}
				} catch (NumberFormatException exception) {
					// One unreadable sample is not worth losing the file over.
				}
			}
			if (!series.samples.isEmpty()) {
				items.put(key, series);
			}
		}
	}

	public void maybeSave(long now) {
		if (dirty && now - lastSave > SAVE_INTERVAL_MS) {
			save();
		}
	}

	public void save() {
		if (!loaded || file == null) {
			return;
		}
		lastSave = System.currentTimeMillis();
		dirty = false;

		Properties properties = new Properties();
		properties.setProperty("scans", Long.toString(scans));
		for (Map.Entry<String, Series> entry : items.entrySet()) {
			Series series = entry.getValue();
			if (series.samples.isEmpty()) {
				continue;
			}
			StringBuilder line = new StringBuilder()
					.append(series.appearances)
					.append(';')
					.append(series.vanished)
					.append(';')
					.append(String.join("|", series.sellers.keySet()))
					.append(';');
			boolean first = true;
			for (Sample sample : series.samples) {
				if (!first) {
					line.append(',');
				}
				first = false;
				line.append(sample.price()).append(':').append(Math.max(0L, lastSave - sample.time()));
			}
			properties.setProperty("item." + entry.getKey(), line.toString());
		}

		try {
			Files.createDirectories(file.getParent());
			try (OutputStream output = Files.newOutputStream(file)) {
				properties.store(output, "Blueprint Client auction price history");
			}
		} catch (IOException exception) {
			// Losing the history costs a few minutes of relearning, nothing more.
		}
	}

	/** For the overlay: a one line summary of what the model knows. */
	public String describe() {
		return String.format(Locale.ROOT, "%d items from %d scans", items.size(), scans);
	}
}
