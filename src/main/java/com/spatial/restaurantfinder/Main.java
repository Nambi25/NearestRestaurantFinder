package com.spatial.restaurantfinder;

import com.google.maps.model.LatLng;
import com.spatial.restaurantfinder.model.Restaurant;
import com.spatial.restaurantfinder.service.GooglePlacesClient;
import com.spatial.restaurantfinder.service.GooglePlacesClient.PlacesClientException;
import com.spatial.restaurantfinder.spatial.KDTree;
import com.spatial.restaurantfinder.spatial.KDTree.RestaurantDistance;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interactive command-line entry point.
 *
 * <ol>
 *   <li>Validate the Google Maps API key.</li>
 *   <li>Read a location (address, landmark or {@code lat,lng}) and resolve it to coordinates.</li>
 *   <li>Read a search radius and fetch nearby restaurants from Google Places.</li>
 *   <li>Index the restaurants in a 2D KD-Tree.</li>
 *   <li>Run k-nearest-neighbour queries with ratings and veg/non-veg filters.</li>
 * </ol>
 *
 * <p><b>Cost per city</b> (n = restaurants fetched, k = neighbours requested, c = distinct cuisines):</p>
 * <ul>
 *   <li>Fetch: network-bound (2–3 HTTP calls), see {@link GooglePlacesClient}.</li>
 *   <li>Build KD-Tree: O(n log² n), done <b>once</b> per city.</li>
 *   <li>Cuisine breakdown: O(n + c log c), done once per city.</li>
 *   <li>Each query: average O((log n + k) log k) search + O(k) to print the table.</li>
 * </ul>
 * <p>The index is built once and then reused for every query and every filter change in that city —
 * that reuse is what justifies building a tree instead of scanning the list each time.</p>
 *
 * <p>The reader/writer are injected through the constructor (rather than using {@code System.in/out}
 * directly everywhere) so the class could be driven by test input without a real console.</p>
 */
public final class Main {

    static {
        // Must run before any SLF4J logger is created; keeps library INFO chatter out of the CLI.
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.showShortLogName", "true");
    }

    private static final int DEFAULT_RADIUS_METERS = 5_000;
    private static final int DEFAULT_K = 10;
    /** After this many failed location inputs we go back to the main loop instead of asking forever. */
    private static final int MAX_LOCATION_ATTEMPTS = 3;

    /**
     * Matches "12.97, 77.59" or "(12.97,77.59)": two signed decimal numbers separated by a comma,
     * optionally wrapped in brackets. Compiled once as a constant because compiling a regex is far more
     * expensive than matching with it.
     */
    private static final Pattern LAT_LNG_PATTERN = Pattern.compile(
            "^\\s*\\(?\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)\\s*\\)?\\s*$");

    // Column widths (in characters) for the results table.
    private static final int W_RANK = 4;
    private static final int W_NAME = 24;
    private static final int W_DIST = 9;
    private static final int W_RATING = 6;
    private static final int W_DIET = 9;
    private static final int W_CUISINE = 16;
    private static final int W_ADDRESS = 36;

    private final BufferedReader in;
    private final PrintStream out;

    /**
     * Dietary filter options. Using an enum instead of raw strings/ints means an invalid choice is
     * impossible once parsed, and each option carries its own label and matching logic.
     */
    public enum DietFilter {
        ALL("All"),
        PURE_VEG("Pure Veg"),
        NON_VEG("Non-Veg");

        private final String label;

        DietFilter(String label) {
            this.label = label;
        }

        /** Time: O(1). */
        public String getLabel() {
            return label;
        }

        /**
         * @return true when the restaurant passes this diet filter.
         * Time: O(1) for ALL, O(t + L) for the veg checks (see {@link Restaurant#isPureVeg()}).
         */
        public boolean matches(Restaurant r) {
            return switch (this) {
                case ALL -> true;
                case PURE_VEG -> r.isPureVeg();
                case NON_VEG -> r.isNonVeg();
            };
        }
    }

    private Main(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    /**
     * Program entry point. Reads input using the console's own character set (if there is a console)
     * so names typed with non-ASCII characters are decoded correctly on Windows.
     * Exits with a non-zero code on error so scripts can detect failure.
     */
    public static void main(String[] args) {
        Charset consoleCharset = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, consoleCharset));
        // Write output as UTF-8 regardless of the console's legacy code page (437/1252 on Windows).
        // Otherwise names in scripts such as Tamil or Hindi, which Google returns as-is, are printed
        // as '?' because the code page cannot represent them. Pair with `chcp 65001` in the launcher
        // so the terminal decodes the bytes as UTF-8.
        PrintStream utf8Out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        int exitCode = new Main(reader, utf8Out).run();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Main program loop: outer loop = one city per iteration, inner loop = queries within that city.
     *
     * <p>Exit codes: 0 = normal exit, 2 = no API key, 3 = Google API error, 4 = invalid argument.</p>
     */
    private int run() {
        printBanner();

        // 1. Validate API key presence.
        String apiKey;
        try {
            apiKey = GooglePlacesClient.resolveApiKey();
        } catch (IllegalStateException e) {
            System.err.println("ERROR: " + e.getMessage());
            return 2;
        }
        // Only show the first/last 4 characters so the key isn't leaked in screenshots.
        out.println("API key loaded: " + maskKey(apiKey));
        out.println();

        // try-with-resources guarantees client.close() runs (shuts down HTTP threads) however we leave.
        try (GooglePlacesClient client = new GooglePlacesClient(apiKey)) {
            boolean keepRunning = true;

            // ===== Outer loop: one iteration per city / location =====
            while (keepRunning) {
                out.println("================================================================================");
                out.println("  Search a City or Location (enter 'q' to quit)");
                out.println("================================================================================");

                // 2–3. Location.
                Optional<double[]> origin = promptForLocation(client);
                if (origin.isEmpty()) {
                    break; // user quit, input ended, or too many failed attempts
                }
                double originLat = origin.get()[0];
                double originLng = origin.get()[1];
                out.printf(Locale.ROOT, "Search origin: %.6f, %.6f%n%n", originLat, originLng);

                // 4. Radius.
                Optional<Integer> radius = promptForInt(
                        "Enter search radius in meters [default " + DEFAULT_RADIUS_METERS + ", max "
                                + GooglePlacesClient.MAX_RADIUS_METERS + "]: ",
                        DEFAULT_RADIUS_METERS, 1, GooglePlacesClient.MAX_RADIUS_METERS);
                if (radius.isEmpty()) {
                    continue;
                }

                // 5. Fetch. System.nanoTime() is used for timing because it is monotonic (unlike
                //    currentTimeMillis, it can't jump if the system clock changes).
                out.printf(Locale.ROOT, "%nFetching restaurants within %,d m...%n", radius.get());
                long fetchStart = System.nanoTime();
                List<Restaurant> restaurants;
                try {
                    restaurants = client.fetchNearbyRestaurants(originLat, originLng, radius.get());
                } catch (PlacesClientException e) {
                    out.println("  Failed to fetch: " + e.getMessage());
                    out.println("  Please try another location or radius.\n");
                    continue;
                }
                long fetchMs = (System.nanoTime() - fetchStart) / 1_000_000L;

                if (restaurants.isEmpty()) {
                    out.println("Google Places returned no restaurants in that area. Try a larger radius or another city.\n");
                    continue;
                }
                out.printf(Locale.ROOT, "Fetched %d restaurant(s) in %,d ms.%n", restaurants.size(), fetchMs);

                // 6. Build index. O(n log² n), done once per city and reused by every query below.
                KDTree tree = new KDTree();
                long buildStart = System.nanoTime();
                tree.build(restaurants);
                double buildMs = (System.nanoTime() - buildStart) / 1_000_000.0;
                out.printf(Locale.ROOT, "Indexed %d restaurant(s) in a KD-Tree of height %d (%.3f ms).%n%n",
                        tree.size(), tree.height(), buildMs);

                // Analyze and display cuisine breakdown (Hash Map frequency counter - DSA)
                Map<String, Integer> cuisineCounts = getCuisineDistribution(restaurants);
                printCuisineSummary(cuisineCounts);

                // Initial filters
                DietFilter diet = promptForDietFilter(DietFilter.ALL);
                double minRating = promptForMinRating(0.0);
                String cuisineFilter = promptForCuisineFilter("All", cuisineCounts);
                out.println();

                // 7–8. Query loop for current city.
                // ===== Inner loop: repeated k-NN queries on the same tree =====
                boolean first = true;
                while (true) {
                    String prompt = first
                            ? "How many nearest restaurants (k)? [default " + DEFAULT_K + "]: "
                            : "Another query? Enter k (or 'f' for filters, 'c' for new city, 'q' to quit): ";

                    String raw = readLine(prompt);
                    if (raw == null || raw.isBlank()) {
                        if (first) {
                            // Blank on the first prompt means "use the default k".
                            raw = String.valueOf(DEFAULT_K);
                        } else {
                            // Blank on later prompts means "I'm done with this city".
                            String another = readLine("Search another city? (y/n) [default y]: ");
                            if (another == null || another.isBlank() || another.strip().equalsIgnoreCase("y") || another.strip().equalsIgnoreCase("yes")) {
                                out.println();
                                break;
                            } else {
                                keepRunning = false;
                                break;
                            }
                        }
                    }
                    String trimmed = raw.strip().toLowerCase(Locale.ROOT);
                    // 'c' → leave the inner loop; the outer loop then asks for a new city.
                    if (trimmed.equals("c") || trimmed.equals("city") || trimmed.equals("new")) {
                        out.println("\nSwitching to a new city/location...\n");
                        break;
                    }
                    // 'q' → confirm whether to quit the program or just this city.
                    if (trimmed.equals("q") || trimmed.equals("quit") || trimmed.equals("exit")) {
                        String another = readLine("Search another city? (y/n) [default n]: ");
                        if (another != null && (another.strip().equalsIgnoreCase("y") || another.strip().equalsIgnoreCase("yes"))) {
                            out.println();
                            break;
                        } else {
                            keepRunning = false;
                            break;
                        }
                    }
                    // 'f' → change filters. The tree does NOT need rebuilding: filters are applied
                    //       during the search via the predicate, so changing them is O(1).
                    if (trimmed.equals("f") || trimmed.equals("filter") || trimmed.equals("filters")) {
                        out.println();
                        diet = promptForDietFilter(diet);
                        minRating = promptForMinRating(minRating);
                        cuisineFilter = promptForCuisineFilter(cuisineFilter, cuisineCounts);
                        out.printf(Locale.ROOT, "Updated filters -> Diet: %s | Min Rating: %s | Cuisine: %s%n%n",
                                diet.getLabel(),
                                minRating > 0.0 ? String.format(Locale.ROOT, "%.1f+", minRating) : "Any",
                                cuisineFilter);
                        first = true;
                        continue;
                    }

                    // Anything else should be a number k. (KDTree caps k at n internally, so a large
                    // k is safe — it just returns every matching restaurant.)
                    Optional<Integer> k = parseBoundedInt(trimmed, 1, tree.size() * 10);
                    if (k.isEmpty()) {
                        continue;
                    }
                    first = false;

                    // Lambdas can only capture effectively-final variables, so copy the current filters.
                    final DietFilter activeDiet = diet;
                    final double activeMinRating = minRating;
                    final String activeCuisine = cuisineFilter;
                    // Combined filter = Diet AND Rating AND Cuisine. && short-circuits, so cheaper checks
                    // that fail skip the more expensive string matching.
                    Predicate<Restaurant> predicate = r -> activeDiet.matches(r)
                            && (activeMinRating <= 0.0 || (r.hasRating() && r.rating() >= activeMinRating))
                            && (r.matchesCuisine(activeCuisine));

                    // The k-NN search itself: average O((log n + k) log k).
                    long queryStart = System.nanoTime();
                    List<RestaurantDistance> nearest = tree.findKNearest(originLat, originLng, k.get(), predicate);
                    double queryMs = (System.nanoTime() - queryStart) / 1_000_000.0;

                    out.println();
                    out.printf(Locale.ROOT, "Showing results for: Diet = %s | Min Rating = %s | Cuisine = %s%n",
                            diet.getLabel(),
                            minRating > 0.0 ? String.format(Locale.ROOT, "%.1f+", minRating) : "Any",
                            cuisineFilter);

                    if (nearest.isEmpty()) {
                        out.println("  No restaurants matched the current filters in this radius.");
                    } else {
                        printTable(nearest);
                    }
                    // "visited X of N nodes" shows the effect of pruning: X < N means sub-trees were skipped.
                    out.printf(Locale.ROOT, "k-NN search: %d result(s), visited %d of %d node(s), %.3f ms.%n%n",
                            nearest.size(), tree.getLastVisitedNodes(), tree.size(), queryMs);
                }
            }

            out.println("Goodbye. Thank you for using Spatial Restaurant Finder!");
            return 0;
        } catch (PlacesClientException e) {
            System.err.println();
            System.err.println("ERROR: " + e.getMessage());
            return 3;
        } catch (IllegalArgumentException e) {
            System.err.println();
            System.err.println("ERROR: " + e.getMessage());
            return 4;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Filters & Input
    // ------------------------------------------------------------------------------------------

    /**
     * Counts how many restaurants fall into each cuisine and returns the counts sorted from most to
     * least common.
     *
     * <p><b>Why a HashMap?</b> Each lookup/update is O(1) on average, so counting all n restaurants is
     * O(n). Without a map we'd have to search a list of cuisines for every restaurant, i.e. O(n · c).
     * A LinkedHashMap is returned so the sorted order is preserved when iterating.</p>
     *
     * <p>Time: O(n) to count + O(c log c) to sort the c distinct cuisines (c ≤ n) → O(n + c log c).
     * Space: O(c).</p>
     */
    private Map<String, Integer> getCuisineDistribution(List<Restaurant> restaurants) {
        // Step 1: frequency count, O(n).
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Restaurant r : restaurants) {
            String c = r.cuisine();
            counts.put(c, counts.getOrDefault(c, 0) + 1);
        }
        // Step 2: sort entries by count, descending, O(c log c). List.sort is stable, so cuisines with
        // equal counts keep their first-seen order.
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
        // Step 3: copy into a LinkedHashMap to keep that order, O(c).
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : list) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    /**
     * Prints the top 8 cuisines (and how many more exist). Map is already sorted by count.
     * Time: O(min(c, 8)) — stops after 8 entries.
     */
    private void printCuisineSummary(Map<String, Integer> counts) {
        out.println("Available Cuisines in this area:");
        // StringBuilder avoids creating a new String on every append (String + in a loop is O(n²)).
        StringBuilder sb = new StringBuilder("  ");
        int count = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (count > 0) sb.append(", ");
            sb.append(entry.getKey()).append(" (").append(entry.getValue()).append(")");
            count++;
            if (count >= 8) {
                if (counts.size() > 8) {
                    sb.append("... (+").append(counts.size() - 8).append(" more)");
                }
                break;
            }
        }
        out.println(sb);
        out.println();
    }

    /**
     * Shows a numbered menu of the 7 most common cuisines. The user may pick a number, "1"/"all", or
     * type any free-text keyword (e.g. "Biryani"), which is later substring-matched by
     * {@link Restaurant#matchesCuisine}.
     *
     * <p>Time: O(c) to copy the keys into a list (needed for index-based access), O(1) per choice.</p>
     */
    private String promptForCuisineFilter(String current, Map<String, Integer> cuisineCounts) {
        out.println("Select Cuisine Filter:");
        out.println("  [1] All Cuisines");
        // Maps have no index access, so copy the (already sorted) keys into a list for get(i).
        List<String> topCuisines = new ArrayList<>(cuisineCounts.keySet());
        int maxToShow = Math.min(topCuisines.size(), 7);
        for (int i = 0; i < maxToShow; i++) {
            String c = topCuisines.get(i);
            out.printf(Locale.ROOT, "  [%d] %s (%d)%n", (i + 2), c, cuisineCounts.get(c));
        }
        out.println("  (Or type any cuisine/dish keyword, e.g. 'Biryani', 'Pizza', 'Italian')");

        String input = readLine("Choice [default " + current + "]: ");
        if (input == null || input.isBlank()) {
            return current; // Enter keeps the current filter.
        }
        String clean = input.strip();
        if (clean.equals("1") || clean.equalsIgnoreCase("all")) {
            return "All";
        }
        try {
            // Menu numbers start at 2 (1 is "All"), hence the "- 2" offset.
            int num = Integer.parseInt(clean);
            if (num >= 2 && num <= maxToShow + 1) {
                return topCuisines.get(num - 2);
            }
        } catch (NumberFormatException ignored) {
            // User typed text like "Biryani" or "Pizza"
        }
        return clean;
    }

    /**
     * Reads the diet choice. Accepts menu numbers or words ("veg", "non veg", "pure").
     * "non" is checked so that "non veg" isn't mistaken for "veg". Time: O(input length).
     */
    private DietFilter promptForDietFilter(DietFilter current) {
        out.println("Select Dietary Preference:");
        out.println("  [1] All (Veg & Non-Veg)");
        out.println("  [2] Pure Veg only");
        out.println("  [3] Non-Veg only");
        String input = readLine("Choice [default " + current.getLabel() + "]: ");
        if (input == null || input.isBlank()) {
            return current;
        }
        String clean = input.strip().toLowerCase(Locale.ROOT);
        if (clean.equals("2") || (clean.contains("veg") && !clean.contains("non")) || clean.contains("pure")) {
            return DietFilter.PURE_VEG;
        }
        if (clean.equals("3") || clean.contains("non")) {
            return DietFilter.NON_VEG;
        }
        return DietFilter.ALL;
    }

    /**
     * Reads a minimum rating in [0.0, 5.0], re-prompting until the input is valid or blank.
     * Time: O(input length) per attempt.
     */
    private double promptForMinRating(double current) {
        String defaultLabel = current <= 0.0 ? "None" : String.format(Locale.ROOT, "%.1f+", current);
        while (true) {
            String input = readLine("Enter minimum rating 0.0-5.0 [default " + defaultLabel + "]: ");
            if (input == null || input.isBlank()) {
                return current;
            }
            try {
                double val = Double.parseDouble(input.strip());
                if (val < 0.0 || val > 5.0) {
                    out.println("  Please enter a number between 0.0 and 5.0.");
                    continue;
                }
                return val;
            } catch (NumberFormatException e) {
                out.println("  Invalid rating format. Example: 4.0 or 4.5");
            }
        }
    }

    /**
     * Asks for a location. Raw coordinates are used directly (saves an API call); anything else is sent
     * to the Geocoding API. Gives up after {@value #MAX_LOCATION_ATTEMPTS} failed attempts.
     *
     * <p>Time: O(input length) for the regex + at most one geocoding HTTP request per attempt.</p>
     *
     * @return {lat, lng} or empty on EOF / repeated failure
     */
    private Optional<double[]> promptForLocation(GooglePlacesClient client) {
        for (int attempt = 1; attempt <= MAX_LOCATION_ATTEMPTS; attempt++) {
            String input = readLine("Enter location (City/Address or 'lat,lng', 'q' to quit): ");
            if (input == null) {
                return Optional.empty(); // end of input (Ctrl+Z / Ctrl+D)
            }
            String clean = input.strip();
            if (clean.equalsIgnoreCase("q") || clean.equalsIgnoreCase("quit") || clean.equalsIgnoreCase("exit")) {
                return Optional.empty();
            }
            if (clean.isBlank()) {
                out.println("  Location is required.");
                continue;
            }

            // Looks like "lat,lng" and in range → no need to call Google.
            Optional<double[]> direct = parseLatLng(clean);
            if (direct.isPresent()) {
                return direct;
            }
            // Looks like coordinates but out of range → explain instead of geocoding a number pair.
            if (LAT_LNG_PATTERN.matcher(clean).matches()) {
                out.println("  Coordinates out of range: latitude must be -90..90 and longitude -180..180.");
                continue;
            }

            try {
                out.println("  Geocoding \"" + clean + "\"...");
                LatLng resolved = client.geocodeAddress(clean);
                return Optional.of(new double[]{resolved.lat, resolved.lng});
            } catch (PlacesClientException e) {
                out.println("  " + e.getMessage());
                if (e.getCause() instanceof com.google.maps.errors.RequestDeniedException) {
                    throw e; // A denied key will not succeed on retry.
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Parses "lat,lng" text into coordinates.
     * Time: O(input length) — a single regex match with no backtracking-heavy constructs.
     *
     * @return the coordinates, or empty if the text isn't a valid in-range coordinate pair
     */
    static Optional<double[]> parseLatLng(String input) {
        Matcher m = LAT_LNG_PATTERN.matcher(input);
        if (!m.matches()) {
            return Optional.empty();
        }
        double lat = Double.parseDouble(m.group(1));
        double lng = Double.parseDouble(m.group(2));
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            return Optional.empty();
        }
        return Optional.of(new double[]{lat, lng});
    }

    /**
     * Reads an int; blank input yields {@code defaultValue}. Empty Optional only on EOF.
     * Re-prompts on invalid input. Time: O(input length) per attempt.
     */
    private Optional<Integer> promptForInt(String prompt, int defaultValue, int min, int max) {
        while (true) {
            String input = readLine(prompt);
            if (input == null) {
                return Optional.empty();
            }
            if (input.isBlank()) {
                return Optional.of(defaultValue);
            }
            Optional<Integer> parsed = parseBoundedInt(input, min, max);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
    }

    /**
     * Lenient integer parser: accepts "5,000", "5_000" and "5000m" as well as plain "5000".
     * Prints a helpful message and returns empty when the value is invalid or out of [min, max].
     * Time: O(input length).
     */
    private Optional<Integer> parseBoundedInt(String input, int min, int max) {
        String cleaned = input.strip().replace(",", "").replace("_", "");
        if (cleaned.toLowerCase(Locale.ROOT).endsWith("m")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).strip(); // allow a trailing "m" for metres
        }
        try {
            int value = Integer.parseInt(cleaned);
            if (value < min || value > max) {
                out.printf(Locale.ROOT, "  Please enter a whole number between %,d and %,d.%n", min, max);
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (NumberFormatException e) {
            out.println("  \"" + input.strip() + "\" is not a whole number.");
            return Optional.empty();
        }
    }

    /**
     * Prints a prompt and reads one line. flush() makes sure the prompt is visible before we block
     * waiting for input. Returns null on end of input or an I/O error, which callers treat as "quit".
     * Time: O(line length).
     */
    private String readLine(String prompt) {
        out.print(prompt);
        out.flush();
        try {
            String line = in.readLine();
            if (line == null) {
                out.println();
            }
            return line;
        } catch (IOException e) {
            System.err.println("Failed to read input: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------------------------------

    /** Time: O(1). */
    private void printBanner() {
        out.println("==============================================================");
        out.println("  Spatial Restaurant Finder  |  KD-Tree k-NN over Google Places");
        out.println("==============================================================");
    }

    /**
     * Prints the results as an ASCII table. Long names, cuisines and addresses wrap onto extra lines
     * instead of being cut off; the single-value columns (rank, distance, rating, diet) only appear on
     * a row's first line.
     *
     * <p>Time: O(k · W) where W is the total table width — each of the k rows prints a bounded number of
     * characters per line. Space: O(W) per row for the wrapped lines.</p>
     */
    private void printTable(List<RestaurantDistance> rows) {
        // Width + 2 accounts for the single space padding on each side of a cell.
        String separator = "+" + "-".repeat(W_RANK + 2)
                + "+" + "-".repeat(W_NAME + 2)
                + "+" + "-".repeat(W_DIST + 2)
                + "+" + "-".repeat(W_RATING + 2)
                + "+" + "-".repeat(W_DIET + 2)
                + "+" + "-".repeat(W_CUISINE + 2)
                + "+" + "-".repeat(W_ADDRESS + 2) + "+";

        out.println(separator);
        out.println(row("Rank", "Name", "Distance", "Rating", "Diet", "Cuisine", "Full Address", true));
        out.println(separator);
        int rank = 1;
        for (RestaurantDistance rd : rows) {
            Restaurant r = rd.restaurant();
            String rating = r.hasRating() ? String.format(Locale.ROOT, "%.1f", r.rating()) : "n/a";
            String dist = formatDistance(rd.haversineKm());
            String diet = r.dietaryLabel();
            String cuisine = r.cuisine();
            String rankStr = String.valueOf(rank++);

            // Wrap each text column independently; the row needs as many lines as the tallest column.
            List<String> nameLines = wrapText(r.name(), W_NAME);
            List<String> cuisineLines = wrapText(cuisine, W_CUISINE);
            List<String> addrLines = wrapText(r.address(), W_ADDRESS);
            int maxLines = Math.max(nameLines.size(), Math.max(cuisineLines.size(), addrLines.size()));

            for (int i = 0; i < maxLines; i++) {
                // Shorter columns are padded with empty cells on the extra lines.
                String n = i < nameLines.size() ? nameLines.get(i) : "";
                String c = i < cuisineLines.size() ? cuisineLines.get(i) : "";
                String a = i < addrLines.size() ? addrLines.get(i) : "";
                String rk = (i == 0) ? rankStr : "";
                String d = (i == 0) ? dist : "";
                String rt = (i == 0) ? rating : "";
                String dt = (i == 0) ? diet : "";
                out.println(row(rk, n, d, rt, dt, c, a, false));
            }
            out.println(separator);
        }
    }

    /**
     * Formats one table line. Numeric columns are right-aligned in data rows (so decimals line up) and
     * left-aligned in the header. Time: O(W).
     */
    private static String row(String rank, String name, String dist, String rating, String diet, String cuisine, String address, boolean header) {
        return "| " + padLeftOrRight(rank, W_RANK, !header)
                + " | " + padRight(name, W_NAME)
                + " | " + padLeftOrRight(dist, W_DIST, !header)
                + " | " + padLeftOrRight(rating, W_RATING, !header)
                + " | " + padRight(diet, W_DIET)
                + " | " + padRight(cuisine, W_CUISINE)
                + " | " + padRight(address, W_ADDRESS)
                + " |";
    }

    /**
     * Wraps text into lines of at most {@code width} characters without breaking words unless necessary.
     *
     * <p>Greedy word wrap: keep adding words to the current line while they fit, otherwise start a new
     * line. A single word longer than the width is split into width-sized chunks.</p>
     *
     * <p>Time: O(len) where len is the text length — every character is copied a constant number of
     * times. Space: O(len) for the output lines.</p>
     */
    static List<String> wrapText(String text, int width) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (current.length() == 0) {
                // Start of a line: hard-split words that are too long to ever fit.
                while (word.length() > width) {
                    lines.add(word.substring(0, width));
                    word = word.substring(width);
                }
                current.append(word);
            } else if (current.length() + 1 + word.length() <= width) {
                // Word (plus a separating space) fits on the current line.
                current.append(" ").append(word);
            } else {
                // Doesn't fit: finish the current line and start a new one with this word.
                lines.add(current.toString());
                current.setLength(0);
                while (word.length() > width) {
                    lines.add(word.substring(0, width));
                    word = word.substring(width);
                }
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    /** Shows metres under 1 km ("350 m") and kilometres otherwise ("2.41 km"). Time: O(1). */
    static String formatDistance(double km) {
        if (km < 1.0) {
            return String.format(Locale.ROOT, "%.0f m", km * 1000.0);
        }
        return String.format(Locale.ROOT, "%.2f km", km);
    }

    /**
     * Truncates by code points so multi-byte names are never split mid-character.
     * (A Java char is a UTF-16 unit; emoji and some scripts need two, so counting chars would be wrong.)
     * Time: O(len).
     */
    static String truncate(String text, int width) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        int length = clean.codePointCount(0, clean.length());
        if (length <= width) {
            return clean;
        }
        int end = clean.offsetByCodePoints(0, width - 3);
        return clean.substring(0, end) + "...";
    }

    /** Left-aligns text in a cell of {@code width} characters (code-point aware). Time: O(width). */
    private static String padRight(String text, int width) {
        int length = text.codePointCount(0, text.length());
        return length >= width ? text : text + " ".repeat(width - length);
    }

    /** Right-aligns (or left-aligns when {@code alignRight} is false) text in a cell. Time: O(width). */
    private static String padLeftOrRight(String text, int width, boolean alignRight) {
        if (!alignRight) {
            return padRight(text, width);
        }
        int length = text.codePointCount(0, text.length());
        return length >= width ? text : " ".repeat(width - length) + text;
    }

    /** Hides the middle of the API key, e.g. "AIza...x9Q2". Time: O(1). */
    private static String maskKey(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
