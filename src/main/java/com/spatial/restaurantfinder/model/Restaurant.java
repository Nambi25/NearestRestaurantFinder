package com.spatial.restaurantfinder.model;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable domain object describing a single restaurant returned by Google Places.
 *
 * <p><b>Why a {@code record}?</b> A record gives us final fields, a canonical constructor,
 * {@code equals}/{@code hashCode} and accessors for free. Immutability matters here because the
 * same {@code Restaurant} object is shared by the KD-Tree, the result heap and the output table;
 * if any of them could mutate it, the tree's ordering invariant (points sorted by lat/lng) could
 * silently break.</p>
 *
 * <p><b>Complexity notation used in this file:</b> {@code t} = number of Google type tags on the
 * place (usually &lt; 10), {@code L} = length of the restaurant name. Keyword lists are fixed-size,
 * so they count as constants.</p>
 *
 * @param id       Google place id (stable unique identifier)
 * @param name     display name
 * @param lat      latitude in decimal degrees
 * @param lng      longitude in decimal degrees
 * @param rating   average user rating (0.0 when the place has no rating)
 * @param address  short street address / vicinity
 * @param pureVeg  flag indicating verified pure vegetarian restaurant
 * @param types    Google Places category types
 * @param cuisine  human-readable cuisine category (see {@link #resolveCuisine})
 */
public record Restaurant(
        String id,
        String name,
        double lat,
        double lng,
        double rating,
        String address,
        boolean pureVeg,
        List<String> types,
        String cuisine) {

    /**
     * Mean Earth radius in kilometres (IUGG). The Earth is not a perfect sphere, but using the mean
     * radius keeps Haversine error under ~0.5%, which is far below what matters at city scale.
     */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    /**
     * Convenience constructor for places with no type/diet information (e.g. in tests).
     * Time: O(1).
     */
    public Restaurant(String id, String name, double lat, double lng, double rating, String address) {
        this(id, name, lat, lng, rating, address, false, Collections.emptyList(), "Multi-Cuisine");
    }

    /**
     * Constructor that derives the cuisine automatically from the types and name.
     * Time: O(t + L) because {@link #resolveCuisine} scans the types and the name once each.
     */
    public Restaurant(String id, String name, double lat, double lng, double rating, String address, boolean pureVeg, List<String> types) {
        this(id, name, lat, lng, rating, address, pureVeg, types, resolveCuisine(null, types, name));
    }

    /**
     * Compact canonical constructor: validates and normalises every field before it is stored.
     *
     * <p>Why validate here? Every other constructor funnels into this one, so bad data (e.g. a
     * latitude of 999 from a broken API response) is rejected once, at the boundary, instead of
     * corrupting KD-Tree splits or Haversine results later.</p>
     *
     * <p>Time: O(t + L) — {@code List.copyOf} copies the t types, {@code strip()} walks the strings.
     * Space: O(t) for the defensive copy of the types list.</p>
     */
    public Restaurant {
        Objects.requireNonNull(id, "id must not be null");
        // Latitude must lie in [-90, 90] and longitude in [-180, 180]; NaN/Infinity are also rejected
        // because they would poison every distance comparison in the KD-Tree.
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude out of range: " + lat);
        }
        if (!Double.isFinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("Longitude out of range: " + lng);
        }
        // Replace missing text with placeholders so the output table never prints "null".
        name = (name == null || name.isBlank()) ? "(unnamed)" : name.strip();
        address = (address == null || address.isBlank()) ? "(no address)" : address.strip();
        // 0.0 is used as the sentinel for "no rating" (Google ratings are always >= 1.0).
        rating = (Double.isFinite(rating) && rating > 0.0) ? rating : 0.0;
        // Defensive, unmodifiable copy: callers can't change our types list after construction.
        types = (types == null) ? Collections.emptyList() : List.copyOf(types);
        cuisine = (cuisine == null || cuisine.isBlank()) ? "Multi-Cuisine" : cuisine.strip();
    }

    /**
     * @return true when Google supplied a rating for this place.
     * Time: O(1).
     */
    public boolean hasRating() {
        return rating > 0.0;
    }

    /**
     * Decides whether the restaurant is pure vegetarian, using three signals from strongest to weakest:
     * <ol>
     *   <li>the explicit {@code pureVeg} flag (set when Google returned it from a vegetarian search),</li>
     *   <li>a {@code vegetarian_restaurant}/{@code vegan_restaurant} type tag,</li>
     *   <li>well-known pure-veg chain/keyword names (e.g. "Udupi", "Bhavan").</li>
     * </ol>
     * The cheap checks run first so most calls return early (short-circuit evaluation).
     *
     * <p>Time: O(t + L) — one pass over the types, then a constant number of substring searches on the
     * lower-cased name. Space: O(L) for the lower-cased copy of the name.</p>
     *
     * @return true if the restaurant is identified as pure vegetarian.
     */
    public boolean isPureVeg() {
        if (pureVeg) {
            return true; // O(1) fast path.
        }
        if (types != null) {
            for (String t : types) { // O(t) scan of the type tags.
                if ("vegetarian_restaurant".equalsIgnoreCase(t) || "vegan_restaurant".equalsIgnoreCase(t)) {
                    return true;
                }
            }
        }
        // Heuristic fallback: Locale.ROOT makes lower-casing independent of the user's language settings.
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("pure veg") || lower.contains("pure-veg") || lower.contains("bhavan")
                || lower.contains("anandhaas") || lower.contains("ananda bhavan") || lower.contains("annapoorna")
                || lower.contains("shree anandhaas") || lower.contains("udupi") || lower.contains("sangeetha");
    }

    /**
     * @return true if the restaurant is non-vegetarian (simply the complement of {@link #isPureVeg()}).
     * Time: O(t + L), same as {@link #isPureVeg()}.
     */
    public boolean isNonVeg() {
        return !isPureVeg();
    }

    /**
     * @return "Pure Veg" or "Non-Veg" for the output table.
     * Time: O(t + L).
     */
    public String dietaryLabel() {
        return isPureVeg() ? "Pure Veg" : "Non-Veg";
    }

    /**
     * Determines the most appropriate cuisine category from Google Places data and restaurant attributes.
     *
     * <p>Strategy (most reliable source first, stop at the first match):</p>
     * <ol>
     *   <li><b>primaryTypeDisplayName</b> from Places API (New), e.g. "South Indian Restaurant".
     *       Generic values like "Restaurant" or "Food" carry no cuisine info, so they are skipped.</li>
     *   <li><b>Google type tags</b>, e.g. {@code pizza_restaurant} → "Italian &amp; Pizza".</li>
     *   <li><b>Keyword matching on the name</b>, e.g. a name containing "dosa" → "South Indian".
     *       This catches the many small Indian restaurants Google only tags as "restaurant".</li>
     *   <li>Otherwise "Multi-Cuisine".</li>
     * </ol>
     * The order of the keyword checks matters: more specific categories are tested before broader ones
     * (e.g. "Biryani" before the catch-all "Indian").
     *
     * <p>Time: O(t + L) — the regex and comparisons on the display name are linear in its length, the
     * type loop is O(t), and each keyword check is a substring search on the name (a fixed number of
     * keywords, so a constant factor). Space: O(L) for the lower-cased name.</p>
     */
    public static String resolveCuisine(String primaryTypeDisplayName, List<String> types, String name) {
        // --- Step 1: Google's own primary type label (Places API New only). ---
        if (primaryTypeDisplayName != null && !primaryTypeDisplayName.isBlank()) {
            // Remove a trailing generic word: "South Indian Restaurant" -> "South Indian".
            String clean = primaryTypeDisplayName.strip()
                    .replaceAll("(?i)\\s+(restaurant|place|bar|shop|store|establishment)$", "")
                    .strip();
            boolean isGeneric = clean.isBlank()
                    || clean.equalsIgnoreCase("restaurant")
                    || clean.equalsIgnoreCase("food")
                    || clean.equalsIgnoreCase("establishment")
                    || clean.equalsIgnoreCase("point of interest")
                    || clean.equalsIgnoreCase("hotel")
                    || clean.equalsIgnoreCase("lodging")
                    || clean.equalsIgnoreCase("family")
                    || clean.equalsIgnoreCase("meal delivery")
                    || clean.equalsIgnoreCase("meal takeaway");
            if (!isGeneric) {
                // Map Google's labels onto our own smaller set of categories so the cuisine
                // frequency table groups similar places together (e.g. Pizza + Italian).
                if (clean.equalsIgnoreCase("South Indian")) return "South Indian";
                if (clean.equalsIgnoreCase("North Indian")) return "North Indian";
                if (clean.equalsIgnoreCase("Indian")) return "Indian";
                if (clean.equalsIgnoreCase("Pizza") || clean.equalsIgnoreCase("Italian")) return "Italian & Pizza";
                if (clean.equalsIgnoreCase("Chinese")) return "Chinese";
                if (clean.equalsIgnoreCase("Fast Food") || clean.equalsIgnoreCase("Hamburger")) return "Fast Food";
                if (clean.equalsIgnoreCase("Coffee") || clean.equalsIgnoreCase("Cafe")) return "Cafe";
                if (clean.equalsIgnoreCase("Bakery") || clean.equalsIgnoreCase("Dessert")) return "Bakery & Desserts";
                if (clean.equalsIgnoreCase("Seafood")) return "Seafood";
                if (clean.equalsIgnoreCase("Barbecue") || clean.equalsIgnoreCase("Steak House")) return "Grill & BBQ";
                if (clean.equalsIgnoreCase("Middle Eastern") || clean.equalsIgnoreCase("Lebanese")) return "Middle Eastern";
                return clean; // Unknown but specific label (e.g. "Korean"): keep Google's wording.
            }
        }

        // --- Step 2: Google type tags. O(t) — first matching tag wins. ---
        if (types != null) {
            for (String t : types) {
                String tl = t.toLowerCase(Locale.ROOT);
                if (tl.equals("south_indian_restaurant")) return "South Indian";
                if (tl.equals("north_indian_restaurant")) return "North Indian";
                if (tl.equals("pizza_restaurant") || tl.equals("italian_restaurant")) return "Italian & Pizza";
                if (tl.equals("chinese_restaurant") || tl.equals("asian_restaurant") || tl.equals("ramen_restaurant")) return "Chinese & Asian";
                if (tl.equals("fast_food_restaurant") || tl.equals("hamburger_restaurant") || tl.equals("sandwich_shop")) return "Fast Food";
                if (tl.equals("cafe") || tl.equals("coffee_shop")) return "Cafe";
                if (tl.equals("bakery") || tl.equals("dessert_restaurant") || tl.equals("ice_cream_shop")) return "Bakery & Desserts";
                if (tl.equals("seafood_restaurant")) return "Seafood";
                if (tl.equals("barbecue_restaurant") || tl.equals("steak_house")) return "Grill & BBQ";
                if (tl.equals("middle_eastern_restaurant") || tl.equals("lebanese_restaurant") || tl.equals("turkish_restaurant")) return "Middle Eastern";
                if (tl.equals("japanese_restaurant") || tl.equals("sushi_restaurant")) return "Japanese";
                if (tl.equals("mexican_restaurant")) return "Mexican";
                if (tl.equals("thai_restaurant")) return "Thai";
                if (tl.equals("indian_restaurant")) return "Indian";
                if (tl.equals("brewpub") || tl.equals("pub") || tl.equals("bar")) return "Brewery & Bar";
            }
        }

        // --- Step 3: keyword heuristics on the restaurant name. ---
        // Each block is a constant number of substring searches, so the whole step is O(L).
        String lowerName = (name != null) ? name.toLowerCase(Locale.ROOT) : "";
        if (lowerName.contains("dosa") || lowerName.contains("idli") || lowerName.contains("udupi")
                || lowerName.contains("bhavan") || lowerName.contains("saravana") || lowerName.contains("annapoorna")
                || lowerName.contains("chettinad") || lowerName.contains("tiffin") || lowerName.contains("sambar")
                || lowerName.contains("andhra") || lowerName.contains("kerala") || lowerName.contains("south indian")
                || lowerName.contains("madras")) {
            return "South Indian";
        }
        if (lowerName.contains("chawal") || lowerName.contains("chole") || lowerName.contains("kulcha")
                || lowerName.contains("chaat") || lowerName.contains("samosa") || lowerName.contains("kachori")) {
            return "Street Food";
        }
        if (lowerName.contains("biryani") || lowerName.contains("briyani") || lowerName.contains("hyderabadi")
                || lowerName.contains("behrouz") || lowerName.contains("paradise")) {
            return "Biryani & Mughlai";
        }
        if (lowerName.contains("punjabi") || lowerName.contains("dhaba") || lowerName.contains("mughlai")
                || lowerName.contains("tandoori") || lowerName.contains("roti") || lowerName.contains("paneer")
                || lowerName.contains("naan") || lowerName.contains("paratha") || lowerName.contains("north indian")) {
            return "North Indian";
        }
        if (lowerName.contains("chinese") || lowerName.contains("noodle") || lowerName.contains("dim sum")
                || lowerName.contains("wok") || lowerName.contains("momo") || lowerName.contains("schezwan")
                || lowerName.contains("manchurian") || lowerName.contains("chopsticks")) {
            return "Chinese & Asian";
        }
        if (lowerName.contains("pizza") || lowerName.contains("pasta") || lowerName.contains("domino")
                || lowerName.contains("pizzeria") || lowerName.contains("trattoria")) {
            return "Italian & Pizza";
        }
        if (lowerName.contains("burger") || lowerName.contains("kfc") || lowerName.contains("mcdonald")
                || lowerName.contains("subway") || lowerName.contains("sandwich") || lowerName.contains("wrap")
                || lowerName.contains("fries") || lowerName.contains("rolls") || lowerName.contains("frankie")) {
            return "Fast Food";
        }
        if (lowerName.contains("cafe") || lowerName.contains("coffee") || lowerName.contains("tea")
                || lowerName.contains("chai") || lowerName.contains("starbucks") || lowerName.contains("barista")
                || lowerName.contains("ccd")) {
            return "Cafe";
        }
        if (lowerName.contains("bakery") || lowerName.contains("cake") || lowerName.contains("pastry")
                || lowerName.contains("dessert") || lowerName.contains("waffle") || lowerName.contains("ice cream")
                || lowerName.contains("mithai") || lowerName.contains("halwai") || lowerName.contains("sweet")) {
            return "Bakery & Desserts";
        }
        if (lowerName.contains("arabian") || lowerName.contains("shawarma") || lowerName.contains("mandi")
                || lowerName.contains("kebab") || lowerName.contains("kabab") || lowerName.contains("falafel")) {
            return "Middle Eastern";
        }
        if (lowerName.contains("barbecue") || lowerName.contains("bbq") || lowerName.contains("grill")
                || lowerName.contains("smoke house") || lowerName.contains("steak")) {
            return "Grill & BBQ";
        }
        if (lowerName.contains("seafood") || lowerName.contains("fish") || lowerName.contains("crab")
                || lowerName.contains("prawn") || lowerName.contains("coastal")) {
            return "Seafood";
        }
        // Broadest Indian bucket is checked last so the specific Indian categories above win first.
        if (lowerName.contains("thali") || lowerName.contains("rasoi") || lowerName.contains("zaika")
                || lowerName.contains("swad") || lowerName.contains("bhojanalaya") || lowerName.contains("indian")) {
            return "Indian";
        }

        // --- Step 4: nothing matched. ---
        return "Multi-Cuisine";
    }

    /**
     * Checks if this restaurant matches a specified cuisine query.
     *
     * <p>Matching is a case-insensitive substring search over the cuisine, then the name, then the
     * type tags, so a user can type a dish ("biryani") as well as a category ("South Indian").
     * "indian" is special-cased so it also matches the more specific Indian sub-categories.</p>
     *
     * <p>This is called from inside the KD-Tree search predicate, i.e. once per visited node, so it
     * is kept allocation-light and returns as soon as any field matches.</p>
     *
     * <p>Time: O(L + T) where T is the total length of all type strings (each substring search is
     * linear in the text it scans; the query is short). Space: O(L + T) for the lower-cased copies.</p>
     *
     * @param query cuisine name or keyword (e.g. "South Indian", "Italian", "All")
     * @return true if matching
     */
    public boolean matchesCuisine(String query) {
        // "All", "1" (menu option 1) or an empty query means "no cuisine filter". O(1).
        if (query == null || query.isBlank() || query.equalsIgnoreCase("all") || query.equalsIgnoreCase("1")) {
            return true;
        }
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (cuisine.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (name.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (types != null) {
            for (String t : types) { // O(T) in total across all types.
                if (t.toLowerCase(Locale.ROOT).contains(q)) {
                    return true;
                }
            }
        }
        // "indian" should include "South Indian", "North Indian" and "Biryani & Mughlai".
        if (q.equals("indian") && (cuisine.toLowerCase(Locale.ROOT).contains("south indian")
                || cuisine.toLowerCase(Locale.ROOT).contains("north indian")
                || cuisine.toLowerCase(Locale.ROOT).contains("biryani"))) {
            return true;
        }
        return false;
    }

    /**
     * Great-circle distance between two coordinates using the Haversine formula.
     *
     * <pre>
     *   a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)
     *   c = 2 · asin(√a)
     *   d = R · c
     * </pre>
     * where φ is latitude and λ is longitude, both in radians.
     *
     * <p>Why Haversine? Latitude/longitude are angles on a sphere, so plain Euclidean distance on the
     * raw numbers gives wrong kilometre values (a degree of longitude shrinks toward the poles).
     * Haversine is numerically stable even for very small distances, which is exactly the
     * "restaurants a few hundred metres away" case.</p>
     *
     * <p>Time: O(1) — a fixed number of trig operations. Space: O(1).</p>
     *
     * @return distance in kilometres
     */
    public static double calculateHaversineDistance(double lat1, double lng1, double lat2, double lng2) {
        // Java's trig functions work in radians, so convert degrees first.
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lng2 - lng1);

        double sinHalfDPhi = Math.sin(dPhi / 2.0);
        double sinHalfDLambda = Math.sin(dLambda / 2.0);
        double a = sinHalfDPhi * sinHalfDPhi
                + Math.cos(phi1) * Math.cos(phi2) * sinHalfDLambda * sinHalfDLambda;
        // Clamp guards against tiny floating-point overshoot above 1.0 for antipodal points.
        double c = 2.0 * Math.asin(Math.sqrt(Math.min(1.0, a)));
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Human-readable one-line summary, mainly for debugging/logging.
     * Time: O(L + address length).
     */
    @Override
    public String toString() {
        String ratingText = hasRating() ? String.format(Locale.ROOT, "%.1f/5", rating) : "n/a";
        return String.format(Locale.ROOT, "%s [%s | %s | %s] (%.6f, %.6f) — %s",
                name, ratingText, dietaryLabel(), cuisine, lat, lng, address);
    }
}
