package com.spatial.restaurantfinder;

import com.google.maps.model.LatLng;
import com.spatial.restaurantfinder.model.Restaurant;
import com.spatial.restaurantfinder.service.GooglePlacesClient;
import com.spatial.restaurantfinder.service.GooglePlacesClient.PlacesClientException;
import com.spatial.restaurantfinder.spatial.KDTree;
import com.spatial.restaurantfinder.spatial.KDTree.RestaurantDistance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
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
    private static final int MAX_LOCATION_ATTEMPTS = 3;

    private static final Pattern LAT_LNG_PATTERN = Pattern.compile(
            "^\\s*\\(?\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*([-+]?\\d{1,3}(?:\\.\\d+)?)\\s*\\)?\\s*$");

    private static final int W_RANK = 4;
    private static final int W_NAME = 24;
    private static final int W_DIST = 9;
    private static final int W_RATING = 6;
    private static final int W_DIET = 9;
    private static final int W_CUISINE = 16;
    private static final int W_ADDRESS = 36;

    private final BufferedReader in;
    private final PrintStream out;

    public enum DietFilter {
        ALL("All"),
        PURE_VEG("Pure Veg"),
        NON_VEG("Non-Veg");

        private final String label;

        DietFilter(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

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

    public static void main(String[] args) {
        Charset consoleCharset = System.console() != null ? System.console().charset() : Charset.defaultCharset();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, consoleCharset));
        int exitCode = new Main(reader, System.out).run();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

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
        out.println("API key loaded: " + maskKey(apiKey));
        out.println();

        try (GooglePlacesClient client = new GooglePlacesClient(apiKey)) {
            boolean keepRunning = true;

            while (keepRunning) {
                out.println("================================================================================");
                out.println("  Search a City or Location (enter 'q' to quit)");
                out.println("================================================================================");

                // 2–3. Location.
                Optional<double[]> origin = promptForLocation(client);
                if (origin.isEmpty()) {
                    break;
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

                // 5. Fetch.
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

                // 6. Build index.
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
                boolean first = true;
                while (true) {
                    String prompt = first
                            ? "How many nearest restaurants (k)? [default " + DEFAULT_K + "]: "
                            : "Another query? Enter k (or 'f' for filters, 'c' for new city, 'q' to quit): ";

                    String raw = readLine(prompt);
                    if (raw == null || raw.isBlank()) {
                        if (first) {
                            raw = String.valueOf(DEFAULT_K);
                        } else {
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
                    if (trimmed.equals("c") || trimmed.equals("city") || trimmed.equals("new")) {
                        out.println("\nSwitching to a new city/location...\n");
                        break;
                    }
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

                    Optional<Integer> k = parseBoundedInt(trimmed, 1, tree.size() * 10);
                    if (k.isEmpty()) {
                        continue;
                    }
                    first = false;

                    final DietFilter activeDiet = diet;
                    final double activeMinRating = minRating;
                    final String activeCuisine = cuisineFilter;
                    Predicate<Restaurant> predicate = r -> activeDiet.matches(r)
                            && (activeMinRating <= 0.0 || (r.hasRating() && r.rating() >= activeMinRating))
                            && (r.matchesCuisine(activeCuisine));

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

    private Map<String, Integer> getCuisineDistribution(List<Restaurant> restaurants) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Restaurant r : restaurants) {
            String c = r.cuisine();
            counts.put(c, counts.getOrDefault(c, 0) + 1);
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((e1, e2) -> e2.getValue().compareTo(e1.getValue()));
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : list) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    private void printCuisineSummary(Map<String, Integer> counts) {
        out.println("Available Cuisines in this area:");
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

    private String promptForCuisineFilter(String current, Map<String, Integer> cuisineCounts) {
        out.println("Select Cuisine Filter:");
        out.println("  [1] All Cuisines");
        List<String> topCuisines = new ArrayList<>(cuisineCounts.keySet());
        int maxToShow = Math.min(topCuisines.size(), 7);
        for (int i = 0; i < maxToShow; i++) {
            String c = topCuisines.get(i);
            out.printf(Locale.ROOT, "  [%d] %s (%d)%n", (i + 2), c, cuisineCounts.get(c));
        }
        out.println("  (Or type any cuisine/dish keyword, e.g. 'Biryani', 'Pizza', 'Italian')");

        String input = readLine("Choice [default " + current + "]: ");
        if (input == null || input.isBlank()) {
            return current;
        }
        String clean = input.strip();
        if (clean.equals("1") || clean.equalsIgnoreCase("all")) {
            return "All";
        }
        try {
            int num = Integer.parseInt(clean);
            if (num >= 2 && num <= maxToShow + 1) {
                return topCuisines.get(num - 2);
            }
        } catch (NumberFormatException ignored) {
            // User typed text like "Biryani" or "Pizza"
        }
        return clean;
    }

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

    /** @return {lat, lng} or empty on EOF / repeated failure */
    private Optional<double[]> promptForLocation(GooglePlacesClient client) {
        for (int attempt = 1; attempt <= MAX_LOCATION_ATTEMPTS; attempt++) {
            String input = readLine("Enter location (City/Address or 'lat,lng', 'q' to quit): ");
            if (input == null) {
                return Optional.empty();
            }
            String clean = input.strip();
            if (clean.equalsIgnoreCase("q") || clean.equalsIgnoreCase("quit") || clean.equalsIgnoreCase("exit")) {
                return Optional.empty();
            }
            if (clean.isBlank()) {
                out.println("  Location is required.");
                continue;
            }

            Optional<double[]> direct = parseLatLng(clean);
            if (direct.isPresent()) {
                return direct;
            }
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

    /** Reads an int; blank input yields {@code defaultValue}. Empty Optional only on EOF. */
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

    private Optional<Integer> parseBoundedInt(String input, int min, int max) {
        String cleaned = input.strip().replace(",", "").replace("_", "");
        if (cleaned.toLowerCase(Locale.ROOT).endsWith("m")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).strip();
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

    private void printBanner() {
        out.println("==============================================================");
        out.println("  Spatial Restaurant Finder  |  KD-Tree k-NN over Google Places");
        out.println("==============================================================");
    }

    private void printTable(List<RestaurantDistance> rows) {
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

            List<String> nameLines = wrapText(r.name(), W_NAME);
            List<String> cuisineLines = wrapText(cuisine, W_CUISINE);
            List<String> addrLines = wrapText(r.address(), W_ADDRESS);
            int maxLines = Math.max(nameLines.size(), Math.max(cuisineLines.size(), addrLines.size()));

            for (int i = 0; i < maxLines; i++) {
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

    /** Wraps text into lines of at most {@code width} characters without breaking words unless necessary. */
    static List<String> wrapText(String text, int width) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        String[] words = text.trim().split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            if (current.length() == 0) {
                while (word.length() > width) {
                    lines.add(word.substring(0, width));
                    word = word.substring(width);
                }
                current.append(word);
            } else if (current.length() + 1 + word.length() <= width) {
                current.append(" ").append(word);
            } else {
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

    static String formatDistance(double km) {
        if (km < 1.0) {
            return String.format(Locale.ROOT, "%.0f m", km * 1000.0);
        }
        return String.format(Locale.ROOT, "%.2f km", km);
    }

    /** Truncates by code points so multi-byte names are never split mid-character. */
    static String truncate(String text, int width) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        int length = clean.codePointCount(0, clean.length());
        if (length <= width) {
            return clean;
        }
        int end = clean.offsetByCodePoints(0, width - 3);
        return clean.substring(0, end) + "...";
    }

    private static String padRight(String text, int width) {
        int length = text.codePointCount(0, text.length());
        return length >= width ? text : text + " ".repeat(width - length);
    }

    private static String padLeftOrRight(String text, int width, boolean alignRight) {
        if (!alignRight) {
            return padRight(text, width);
        }
        int length = text.codePointCount(0, text.length());
        return length >= width ? text : " ".repeat(width - length) + text;
    }

    private static String maskKey(String key) {
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
