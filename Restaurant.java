package com.spatial.restaurantfinder.model;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable domain object describing a single restaurant returned by Google Places.
 *
 * @param id       Google place id (stable unique identifier)
 * @param name     display name
 * @param lat      latitude in decimal degrees
 * @param lng      longitude in decimal degrees
 * @param rating   average user rating (0.0 when the place has no rating)
 * @param address  short street address / vicinity
 * @param pureVeg  flag indicating verified pure vegetarian restaurant
 * @param types    Google Places category types
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

    /** Mean Earth radius in kilometres (IUGG). */
    public static final double EARTH_RADIUS_KM = 6371.0088;

    public Restaurant(String id, String name, double lat, double lng, double rating, String address) {
        this(id, name, lat, lng, rating, address, false, Collections.emptyList(), "Multi-Cuisine");
    }

    public Restaurant(String id, String name, double lat, double lng, double rating, String address, boolean pureVeg, List<String> types) {
        this(id, name, lat, lng, rating, address, pureVeg, types, resolveCuisine(null, types, name));
    }

    public Restaurant {
        Objects.requireNonNull(id, "id must not be null");
        if (!Double.isFinite(lat) || lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude out of range: " + lat);
        }
        if (!Double.isFinite(lng) || lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("Longitude out of range: " + lng);
        }
        name = (name == null || name.isBlank()) ? "(unnamed)" : name.strip();
        address = (address == null || address.isBlank()) ? "(no address)" : address.strip();
        rating = (Double.isFinite(rating) && rating > 0.0) ? rating : 0.0;
        types = (types == null) ? Collections.emptyList() : List.copyOf(types);
        cuisine = (cuisine == null || cuisine.isBlank()) ? "Multi-Cuisine" : cuisine.strip();
    }

    /** @return true when Google supplied a rating for this place. */
    public boolean hasRating() {
        return rating > 0.0;
    }

    /** @return true if the restaurant is identified as pure vegetarian. */
    public boolean isPureVeg() {
        if (pureVeg) {
            return true;
        }
        if (types != null) {
            for (String t : types) {
                if ("vegetarian_restaurant".equalsIgnoreCase(t) || "vegan_restaurant".equalsIgnoreCase(t)) {
                    return true;
                }
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("pure veg") || lower.contains("pure-veg") || lower.contains("bhavan")
                || lower.contains("anandhaas") || lower.contains("ananda bhavan") || lower.contains("annapoorna")
                || lower.contains("shree anandhaas") || lower.contains("udupi") || lower.contains("sangeetha");
    }

    /** @return true if the restaurant is non-vegetarian. */
    public boolean isNonVeg() {
        return !isPureVeg();
    }

    public String dietaryLabel() {
        return isPureVeg() ? "Pure Veg" : "Non-Veg";
    }

    /**
     * Determines the most appropriate cuisine category from Google Places data and restaurant attributes.
     */
    public static String resolveCuisine(String primaryTypeDisplayName, List<String> types, String name) {
        if (primaryTypeDisplayName != null && !primaryTypeDisplayName.isBlank()) {
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
                return clean;
            }
        }

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
        if (lowerName.contains("thali") || lowerName.contains("rasoi") || lowerName.contains("zaika")
                || lowerName.contains("swad") || lowerName.contains("bhojanalaya") || lowerName.contains("indian")) {
            return "Indian";
        }

        return "Multi-Cuisine";
    }

    /**
     * Checks if this restaurant matches a specified cuisine query.
     *
     * @param query cuisine name or keyword (e.g. "South Indian", "Italian", "All")
     * @return true if matching
     */
    public boolean matchesCuisine(String query) {
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
            for (String t : types) {
                if (t.toLowerCase(Locale.ROOT).contains(q)) {
                    return true;
                }
            }
        }
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
     * @return distance in kilometres
     */
    public static double calculateHaversineDistance(double lat1, double lng1, double lat2, double lng2) {
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

    @Override
    public String toString() {
        String ratingText = hasRating() ? String.format(Locale.ROOT, "%.1f/5", rating) : "n/a";
        return String.format(Locale.ROOT, "%s [%s | %s | %s] (%.6f, %.6f) — %s",
                name, ratingText, dietaryLabel(), cuisine, lat, lng, address);
    }
}
