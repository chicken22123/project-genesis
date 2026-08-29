package com.blueprintclient.flip;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * What every item is worth, learned from the auction house itself.
 *
 * <p>There is no price list to download: the only market data a client has is
 * what the auction house shows it. So every page that is scanned records, for
 * each item on it, the <em>cheapest</em> copy on sale - the price a seller
 * actually has to beat. Those observations pile up per item and the model turns
 * them into a fair value.
 *
 * <p>The average is deliberately not used. One mispriced listing (a fat finger,
 * or bait) would drag it a long way, and a flipper that believes a bad number
 * buys rubbish. Instead:
 *
 * <ul>
 *   <li>the <b>median</b> is taken, which a single silly price cannot move;
 *   <li>the <b>median absolute deviation</b> measures the spread around it;
 *   <li>samples further than three deviations from the median are dropped as
 *       outliers, and the median of what is left is the fair value;
 *   <li>the leftover spread becomes <b>dispersion</b>, which is how unsure the
 *       model is about that fair value.
 * </ul>
 *
 * <p>Confidence then combines three things that all have to be true before a
 * number is worth betting coins on: enough samples, recent samples, and a
 * market that agrees with itself.
 */
public final class MarketModel {
	private static final String FILE_NAME = "blueprintclient-market.properties";

	/** Old prices describe an old market. */
	private static final long SAMPLE_TTL_MS = 6L * 60L * 60L * 1000L;
	private static final int MAX_SAMPLES_PER_ITEM = 48;
	private static final int MAX_ITEMS = 4_000;
	private static final long SAVE_INTERVAL_MS = 120_000L;

	/** How fast confidence decays with age, and how many samples count as "enough". */
	private static final double RECENCY_HALF_LIFE_MINUTES = 45.0;
	private static final double SAMPLE_SOFTENER = 3.0;

	/** What the model believes about one item. */
	public record Appraisal(long fairValue, double dispersion, double confidence, int samples, double supplyRate) {
		public boolean isKnown() {
			return samples > 0 && fairValue > 0;
		}
	}

	private static final Appraisal UNKNOWN = new Appraisal(0L, 1.0, 0.0, 0, 0.0);

	private record Sample(long price, long time) {
	}

	private static final class Series {
		private final Deque<Sample> samples = new ArrayDeque<>();
		private long lastSeen;
		private int appearances;
	}

	// Access order, so the least recently seen item is the one evicted when the
	// database gets too big.
	private final Map<String, Series> items = new LinkedHashMap<>(16, 0.75f, true);
	private long scans;
	private boolean loaded;
	private boolean dirty;
	private long lastSave;

	// ------------------------------------------------------------- recording

	/** Call once per auction house page, before the observations from that page. */
	public void noteScan() {
		scans++;
	}

	/**
	 * Record the cheapest copy of an item seen on one page.
	 *
	 * <p>One observation per page per item: a page holding twenty of the same
	 * thing says one thing about the market, not twenty.
	 */
	public void observe(String key, long lowestPrice, long now) {
		if (key == null || key.isEmpty() || lowestPrice <= 0) {
			return;
		}

		Series series = items.computeIfAbsent(key, ignored -> new Series());
		series.samples.addLast(new Sample(lowestPrice, now));
		series.lastSeen = now;
		series.appearances++;
		while (series.samples.size() > MAX_SAMPLES_PER_ITEM) {
			series.samples.pollFirst();
		}
		dirty = true;

		if (items.size() > MAX_ITEMS) {
			Iterator<String> oldest = items.keySet().iterator();
			if (oldest.hasNext()) {
				oldest.next();
				oldest.remove();
			}
		}
	}

	// ------------------------------------------------------------- appraisal

	/** What the model thinks one item is worth, and how much it means it. */
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

		List<Long> kept = new ArrayList<>(prices.size());
		for (long price : prices) {
			if (deviation <= 0.0 || Math.abs(price - median) <= 3.0 * deviation) {
				kept.add(price);
			}
		}
		if (kept.isEmpty()) {
			kept = prices;
		}

		double fair = median(kept);
		double dispersion = fair > 0.0 ? Math.min(1.0, deviation / fair) : 1.0;

		int count = kept.size();
		double sampleTerm = count / (count + SAMPLE_SOFTENER);
		double ageMinutes = Math.max(0.0, (now - series.lastSeen) / 60_000.0);
		double freshTerm = Math.exp(-ageMinutes / RECENCY_HALF_LIFE_MINUTES);
		double agreementTerm = Math.max(0.05, 1.0 - dispersion);
		double confidence = clamp(sampleTerm * freshTerm * agreementTerm);

		double supplyRate = scans <= 0 ? 0.0 : clamp(series.appearances / (double) scans);

		return new Appraisal(Math.round(fair), dispersion, confidence, count, supplyRate);
	}

	private void expire(Series series, long now) {
		while (!series.samples.isEmpty() && now - series.samples.peekFirst().time() > SAMPLE_TTL_MS) {
			series.samples.pollFirst();
			dirty = true;
		}
	}

	private static double median(List<Long> sorted) {
		int size = sorted.size();
		if (size == 0) {
			return 0.0;
		}
		if (size % 2 == 1) {
			return sorted.get(size / 2);
		}
		return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
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
	 * <p>One line per item: {@code appearances|price:age,price:age,...}, with
	 * ages written relative to the save so a clock change cannot make every
	 * sample look like it came from the future.
	 */
	public void loadIfNeeded() {
		if (loaded) {
			return;
		}
		loaded = true;
		lastSave = System.currentTimeMillis();

		Path file = path();
		if (!Files.isRegularFile(file)) {
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
			Series series = new Series();
			String[] halves = properties.getProperty(name).split("\\|", 2);
			if (halves.length != 2) {
				continue;
			}
			try {
				series.appearances = Integer.parseInt(halves[0].trim());
			} catch (NumberFormatException exception) {
				continue;
			}
			for (String entry : halves[1].split(",")) {
				String[] parts = entry.split(":");
				if (parts.length != 2) {
					continue;
				}
				try {
					long price = Long.parseLong(parts[0].trim());
					long age = Long.parseLong(parts[1].trim());
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
		if (!loaded) {
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
			StringBuilder line = new StringBuilder().append(series.appearances).append('|');
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

		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream output = Files.newOutputStream(file)) {
				properties.store(output, "Blueprint Client auction price history");
			}
		} catch (IOException exception) {
			// Losing the history costs a few minutes of relearning, nothing more.
		}
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** For the overlay: a one line summary of what the model knows. */
	public String describe() {
		return String.format(Locale.ROOT, "%d items from %d scans", items.size(), scans);
	}
}
