package com.spatial.restaurantfinder.service;

import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.PlacesApi;
import com.google.maps.errors.ApiException;
import com.google.maps.errors.InvalidRequestException;
import com.google.maps.errors.OverDailyLimitException;
import com.google.maps.errors.OverQueryLimitException;
import com.google.maps.errors.RequestDeniedException;
import com.google.maps.errors.ZeroResultsException;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LatLng;
import com.google.maps.model.PlaceType;
import com.google.maps.model.PlacesSearchResponse;
import com.google.maps.model.PlacesSearchResult;
import com.spatial.restaurantfinder.model.Restaurant;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Thin HTTPS client over {@link GeoApiContext} for geocoding addresses and fetching nearby restaurants.
 *
 * <p>The API key is resolved in this order:</p>
 * <ol>
 *   <li>The {@code GOOGLE_MAPS_API_KEY} environment variable.</li>
 *   <li>A {@code GOOGLE_MAPS_API_KEY=...} line in a {@code .env} file in the working directory.</li>
 * </ol>
 * <p>If neither is present, construction fails with an {@link IllegalStateException} explaining how to set it.</p>
 */
public final class GooglePlacesClient implements AutoCloseable {

    public static final String API_KEY_NAME = "GOOGLE_MAPS_API_KEY";
    public static final int MAX_RADIUS_METERS = 50_000;
    public static final int DEFAULT_MAX_RESULTS = 60;
    /** Google returns at most 20 results per page and at most 3 pages for Nearby Search. */
    private static final int PAGE_SIZE = 20;
    private static final int MAX_PAGES = 3;

    /** A next_page_token takes a short moment to become valid on Google's side. */
    private static final long NEXT_PAGE_INITIAL_DELAY_MS = 2_000L;
    private static final long NEXT_PAGE_RETRY_DELAY_MS = 1_500L;
    private static final int NEXT_PAGE_MAX_ATTEMPTS = 5;

    private static final Logger LOG = LoggerFactory.getLogger(GooglePlacesClient.class);

    private final GeoApiContext context;
    private final String rawApiKey;

    /** Creates a client using the API key resolved from the environment or {@code .env}. */
    public GooglePlacesClient() {
        this(resolveApiKey());
    }

    /** Creates a client using an explicit API key. */
    public GooglePlacesClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Google Maps API key is empty.");
        }
        this.rawApiKey = apiKey.strip();
        this.context = new GeoApiContext.Builder()
                .apiKey(this.rawApiKey)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryTimeout(30, TimeUnit.SECONDS)
                .maxRetries(3)
                .queryRateLimit(10)
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // API key resolution
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves the API key from the environment, then from {@code ./.env}.
     *
     * @throws IllegalStateException when no key can be found
     */
    public static String resolveApiKey() {
        String fromEnv = System.getenv(API_KEY_NAME);
        if (fromEnv != null && !fromEnv.isBlank()
                && !fromEnv.equalsIgnoreCase("your-api-key-here")
                && !fromEnv.equalsIgnoreCase("your_api_key_here")) {
            return fromEnv.strip();
        }

        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        List<Path> candidateEnvFiles = List.of(
                cwd.resolve(".env"),
                cwd.resolve("restaurant-finder").resolve(".env"),
                cwd.resolve("..").resolve(".env")
        );

        for (Path envFile : candidateEnvFiles) {
            Optional<String> fromDotEnv = readKeyFromDotEnv(envFile);
            if (fromDotEnv.isPresent()) {
                return fromDotEnv.get();
            }
        }

        throw new IllegalStateException(
                API_KEY_NAME + " not found.\n"
                        + "  Set it as an environment variable, e.g.\n"
                        + "    export " + API_KEY_NAME + "=\"your-key\"          (macOS / Linux)\n"
                        + "    $env:" + API_KEY_NAME + "=\"your-key\"           (Windows PowerShell)\n"
                        + "  or create a .env file containing:\n"
                        + "    " + API_KEY_NAME + "=your-key");
    }

    /**
     * Minimal {@code .env} parser: supports {@code KEY=VALUE}, an optional {@code export } prefix,
     * single or double quotes around the value, blank lines and {@code #} comments.
     */
    static Optional<String> readKeyFromDotEnv(Path envFile) {
        if (envFile == null || !Files.isRegularFile(envFile) || !Files.isReadable(envFile)) {
            return Optional.empty();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not read {}: {}", envFile, e.getMessage());
            return Optional.empty();
        }

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            if (!API_KEY_NAME.equals(key)) {
                continue;
            }
            String value = line.substring(eq + 1).strip();
            value = stripQuotesAndComment(value);
            if (!value.isEmpty() && !value.equalsIgnoreCase("your-api-key-here") && !value.equalsIgnoreCase("your_api_key_here")) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static String stripQuotesAndComment(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if (first == '"' || first == '\'') {
                int closing = value.indexOf(first, 1);
                if (closing > 0) {
                    return value.substring(1, closing).strip();
                }
            }
        }
        int hash = value.indexOf(" #");
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        return value.strip();
    }

    // ------------------------------------------------------------------------------------------
    // Geocoding
    // ------------------------------------------------------------------------------------------

    /**
     * Resolves an address, city or landmark to coordinates using the Geocoding API.
     *
     * @throws PlacesClientException when the address cannot be resolved or the API call fails
     */
    public LatLng geocodeAddress(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Address must not be blank");
        }
        try {
            GeocodingResult[] results = GeocodingApi.geocode(context, address.strip()).await();
            if (results == null || results.length == 0 || results[0].geometry == null) {
                throw new PlacesClientException("No location found for \"" + address.strip() + "\".");
            }
            GeocodingResult best = results[0];
            LOG.debug("Geocoded '{}' -> {} ({})", address, best.geometry.location, best.formattedAddress);
            return best.geometry.location;
        } catch (ZeroResultsException e) {
            throw new PlacesClientException("No location found for \"" + address.strip() + "\".", e);
        } catch (ApiException e) {
            throw translate("Geocoding", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlacesClientException("Geocoding was interrupted.", e);
        } catch (IOException e) {
            throw new PlacesClientException("Network error while geocoding: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Nearby search
    // ------------------------------------------------------------------------------------------

    /** Fetches up to {@value #DEFAULT_MAX_RESULTS} restaurants around a coordinate. */
    public List<Restaurant> fetchNearbyRestaurants(double lat, double lng, int radiusMeters) {
        return fetchNearbyRestaurants(lat, lng, radiusMeters, DEFAULT_MAX_RESULTS);
    }

    /**
     * Fetches restaurants around a coordinate with the Places Nearby Search API, following
     * {@code next_page_token} pagination until {@code maxResults} places are collected or
     * Google runs out of pages (max 3 pages × 20 results).
     *
     * @param maxResults 1–60
     * @throws PlacesClientException when the first page fails; failures on later pages are logged and
     *                               the places collected so far are returned
     */
    public List<Restaurant> fetchNearbyRestaurants(double lat, double lng, int radiusMeters, int maxResults) {
        validateCoordinates(lat, lng);
        if (radiusMeters < 1 || radiusMeters > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException(
                    "Radius must be between 1 and " + MAX_RADIUS_METERS + " metres, got " + radiusMeters);
        }

        // 1. Try modern Places API (New) first (required for all modern Google Cloud projects).
        try {
            List<Restaurant> results = fetchNearbyRestaurantsNew(lat, lng, radiusMeters, maxResults);
            LOG.debug("Places API (New) returned {} restaurant(s)", results.size());
            return results;
        } catch (PlacesClientException e) {
            LOG.warn("Places API (New) failed ({}); attempting Legacy Places API fallback...", e.getMessage());
            return fetchNearbyRestaurantsLegacy(lat, lng, radiusMeters, maxResults);
        }
    }

    /**
     * Calls Google Maps Places API (New) — POST https://places.googleapis.com/v1/places:searchNearby
     * Fetches both general restaurants and vegetarian restaurants to ensure rich coverage.
     */
    private List<Restaurant> fetchNearbyRestaurantsNew(double lat, double lng, int radiusMeters, int maxResults) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();

        Map<String, Restaurant> merged = new LinkedHashMap<>();

        // 1. Fetch general restaurants (up to 20)
        List<Restaurant> general = searchNearbyByType(client, lat, lng, radiusMeters, "restaurant", 20);
        for (Restaurant r : general) {
            merged.put(r.id(), r);
        }

        // 2. Also fetch vegetarian restaurants (up to 20) to ensure high-quality veg options
        try {
            List<Restaurant> veg = searchNearbyByType(client, lat, lng, radiusMeters, "vegetarian_restaurant", 20);
            for (Restaurant r : veg) {
                merged.putIfAbsent(r.id(), r);
            }
        } catch (Exception e) {
            LOG.debug("Vegetarian query failed or empty: {}", e.getMessage());
        }

        return new ArrayList<>(merged.values());
    }

    private List<Restaurant> searchNearbyByType(HttpClient client, double lat, double lng, int radiusMeters, String placeType, int maxCount) {
        int limit = Math.max(1, Math.min(maxCount, 20));
        String jsonPayload = String.format(Locale.ROOT,
                "{\"includedTypes\":[\"%s\"],\"maxResultCount\":%d,"
                + "\"locationRestriction\":{\"circle\":{\"center\":{\"latitude\":%.6f,\"longitude\":%.6f},\"radius\":%.1f}}}",
                placeType, limit, lat, lng, (double) radiusMeters);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://places.googleapis.com/v1/places:searchNearby"))
                .header("Content-Type", "application/json")
                .header("X-Goog-Api-Key", rawApiKey)
                .header("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location,places.rating,places.types,places.primaryType,places.primaryTypeDisplayName,places.servesVegetarianFood")
                .timeout(java.time.Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new PlacesClientException("Places API (New) HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("places") || root.get("places").isJsonNull()) {
                return new ArrayList<>();
            }

            JsonArray places = root.getAsJsonArray("places");
            List<Restaurant> list = new ArrayList<>();
            for (JsonElement el : places) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();

                String id = p.has("id") ? p.get("id").getAsString() : String.format(Locale.ROOT, "place@%.7f,%.7f", lat, lng);
                String name = "(unnamed)";
                if (p.has("displayName") && p.getAsJsonObject("displayName").has("text")) {
                    name = p.getAsJsonObject("displayName").get("text").getAsString();
                }
                String address = p.has("formattedAddress") ? p.get("formattedAddress").getAsString() : "";
                double rating = p.has("rating") ? p.get("rating").getAsDouble() : 0.0;

                double pLat = lat;
                double pLng = lng;
                if (p.has("location")) {
                    JsonObject loc = p.getAsJsonObject("location");
                    if (loc.has("latitude") && loc.has("longitude")) {
                        pLat = loc.get("latitude").getAsDouble();
                        pLng = loc.get("longitude").getAsDouble();
                    }
                }

                List<String> typesList = new ArrayList<>();
                boolean isVeg = "vegetarian_restaurant".equalsIgnoreCase(placeType);
                if (p.has("types") && p.get("types").isJsonArray()) {
                    for (JsonElement te : p.getAsJsonArray("types")) {
                        String t = te.getAsString();
                        typesList.add(t);
                        if ("vegetarian_restaurant".equalsIgnoreCase(t) || "vegan_restaurant".equalsIgnoreCase(t)) {
                            isVeg = true;
                        }
                    }
                }

                String primaryTypeDisplayName = null;
                if (p.has("primaryTypeDisplayName") && p.getAsJsonObject("primaryTypeDisplayName").has("text")) {
                    primaryTypeDisplayName = p.getAsJsonObject("primaryTypeDisplayName").get("text").getAsString();
                }
                String cuisine = Restaurant.resolveCuisine(primaryTypeDisplayName, typesList, name);

                try {
                    list.add(new Restaurant(id, name, pLat, pLng, rating, address, isVeg, typesList, cuisine));
                } catch (IllegalArgumentException e) {
                    LOG.debug("Skipping place {} with invalid data: {}", id, e.getMessage());
                }
            }
            return list;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlacesClientException("Nearby search was interrupted.", e);
        } catch (IOException e) {
            throw new PlacesClientException("Network error during nearby search: " + e.getMessage(), e);
        }
    }

    /**
     * Legacy Places API search (for older Google Cloud projects).
     */
    private List<Restaurant> fetchNearbyRestaurantsLegacy(double lat, double lng, int radiusMeters, int maxResults) {
        int limit = Math.max(1, Math.min(maxResults, PAGE_SIZE * MAX_PAGES));

        Map<String, Restaurant> byId = new LinkedHashMap<>();
        LatLng location = new LatLng(lat, lng);

        PlacesSearchResponse page;
        try {
            page = PlacesApi.nearbySearchQuery(context, location)
                    .radius(radiusMeters)
                    .type(PlaceType.RESTAURANT)
                    .await();
        } catch (ZeroResultsException e) {
            return new ArrayList<>();
        } catch (ApiException e) {
            throw translate("Places Nearby Search", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlacesClientException("Nearby search was interrupted.", e);
        } catch (IOException e) {
            throw new PlacesClientException("Network error during nearby search: " + e.getMessage(), e);
        }

        int pagesFetched = 1;
        collect(page, byId, limit);

        while (byId.size() < limit
                && pagesFetched < MAX_PAGES
                && page != null
                && page.nextPageToken != null
                && !page.nextPageToken.isBlank()) {
            PlacesSearchResponse next = fetchNextPage(page.nextPageToken);
            if (next == null) {
                break;
            }
            page = next;
            pagesFetched++;
            collect(page, byId, limit);
        }

        LOG.debug("Fetched {} restaurant(s) across {} page(s)", byId.size(), pagesFetched);
        return new ArrayList<>(byId.values());
    }

    /**
     * Requests the next result page. Google rejects a token with INVALID_REQUEST until it becomes
     * active, so this waits and retries a few times before giving up.
     *
     * @return the next page, or {@code null} when it cannot be fetched
     */
    private PlacesSearchResponse fetchNextPage(String token) {
        try {
            Thread.sleep(NEXT_PAGE_INITIAL_DELAY_MS);
            for (int attempt = 1; attempt <= NEXT_PAGE_MAX_ATTEMPTS; attempt++) {
                try {
                    return PlacesApi.nearbySearchNextPage(context, token).await();
                } catch (InvalidRequestException e) {
                    if (attempt == NEXT_PAGE_MAX_ATTEMPTS) {
                        LOG.warn("Next page token never became valid; keeping results collected so far.");
                        return null;
                    }
                    Thread.sleep(NEXT_PAGE_RETRY_DELAY_MS);
                }
            }
        } catch (ZeroResultsException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Pagination interrupted; keeping results collected so far.");
        } catch (ApiException | IOException e) {
            LOG.warn("Could not fetch next page ({}); keeping results collected so far.", e.getMessage());
        }
        return null;
    }

    private static void collect(PlacesSearchResponse page, Map<String, Restaurant> sink, int limit) {
        if (page == null || page.results == null) {
            return;
        }
        for (PlacesSearchResult result : page.results) {
            if (sink.size() >= limit) {
                return;
            }
            toRestaurant(result).ifPresent(r -> sink.putIfAbsent(r.id(), r));
        }
    }

    /** Maps a Places result to the domain model, skipping entries without usable coordinates. */
    static Optional<Restaurant> toRestaurant(PlacesSearchResult result) {
        if (result == null || result.geometry == null || result.geometry.location == null) {
            return Optional.empty();
        }
        if ("CLOSED_PERMANENTLY".equalsIgnoreCase(result.businessStatus)) {
            return Optional.empty();
        }
        double lat = result.geometry.location.lat;
        double lng = result.geometry.location.lng;
        String id = (result.placeId != null && !result.placeId.isBlank())
                ? result.placeId
                : String.format("%s@%.7f,%.7f", result.name, lat, lng);
        String address = result.vicinity != null && !result.vicinity.isBlank()
                ? result.vicinity
                : result.formattedAddress;
        List<String> typesList = (result.types != null) ? List.of(result.types) : Collections.emptyList();
        String cuisine = Restaurant.resolveCuisine(null, typesList, result.name);
        try {
            return Optional.of(new Restaurant(id, result.name, lat, lng, result.rating, address, false, typesList, cuisine));
        } catch (IllegalArgumentException e) {
            LOG.debug("Skipping place {} with invalid data: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static void validateCoordinates(double lat, double lng) {
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90, got " + lat);
        }
        if (!Double.isFinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180, got " + lng);
        }
    }

    private static PlacesClientException translate(String operation, ApiException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage();
        if (e instanceof RequestDeniedException) {
            String hint = "Check that the key is valid, billing is enabled, and the Geocoding API and "
                    + "Places API are enabled for the key's Google Cloud project.";
            if (detail.toLowerCase().contains("legacy")) {
                hint = "This project cannot call Places API (Legacy), which this client library uses. "
                        + "Use a Google Cloud project that already has Places API (Legacy) enabled.";
            }
            return new PlacesClientException(operation + " request denied: " + detail + "\n  " + hint, e);
        }
        if (e instanceof OverQueryLimitException || e instanceof OverDailyLimitException) {
            return new PlacesClientException(operation + " quota exceeded: " + detail, e);
        }
        return new PlacesClientException(operation + " failed: " + detail, e);
    }

    /** Releases the HTTP client threads held by the underlying {@link GeoApiContext}. */
    @Override
    public void close() {
        context.shutdown();
    }

    /** Unchecked exception carrying a user-readable description of an API failure. */
    public static final class PlacesClientException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public PlacesClientException(String message) {
            super(message);
        }

        public PlacesClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
