package com.spatial.restaurantfinder.spatial;

import com.spatial.restaurantfinder.model.Restaurant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * In-memory 2-dimensional KD-Tree over restaurant coordinates.
 *
 * <ul>
 *   <li>Even depth splits on latitude (x-axis).</li>
 *   <li>Odd depth splits on longitude (y-axis).</li>
 * </ul>
 *
 * <p><b>Distance metric.</b> Ranking uses squared Euclidean distance in an equirectangular projection
 * centred on the query: {@code dLat² + (dLng · cos(targetLat))²}. One degree of longitude is shorter
 * than one degree of latitude everywhere except the equator, so scaling the longitude difference makes
 * the coordinate-space ranking agree with real ground distance at city scale. The tree itself is built
 * on raw latitude/longitude; only the query-time metric is scaled, which keeps the axis bound a valid
 * lower bound for pruning. Final results also carry the exact Haversine distance in kilometres.</p>
 *
 * <p>Queries near the ±180° meridian are not wrapped; restaurants on the far side of the antimeridian
 * are treated as distant.</p>
 *
 * <p>Build and query are not synchronized. Build once, then query from a single thread, or guard
 * externally.</p>
 */
public final class KDTree {

    private static final int AXIS_LAT = 0;

    private static final Comparator<Restaurant> BY_LAT =
            Comparator.comparingDouble(Restaurant::lat).thenComparingDouble(Restaurant::lng);
    private static final Comparator<Restaurant> BY_LNG =
            Comparator.comparingDouble(Restaurant::lng).thenComparingDouble(Restaurant::lat);

    /**
     * A k-NN result entry.
     *
     * @param restaurant  the matched restaurant
     * @param distanceSq  squared projected coordinate distance (degrees²) used for ranking
     * @param haversineKm true great-circle distance from the query point in kilometres
     */
    public record RestaurantDistance(Restaurant restaurant, double distanceSq, double haversineKm) {
        public RestaurantDistance {
            Objects.requireNonNull(restaurant, "restaurant must not be null");
        }

        /** @return true great-circle distance in metres. */
        public double haversineMeters() {
            return haversineKm * 1000.0;
        }
    }

    private KDNode root;
    private int size;
    private int lastVisitedNodes;

    /**
     * Builds a balanced tree from the given restaurants, replacing any previous contents.
     * Each level sorts its sub-range on the current axis and promotes the median, giving
     * O(n log² n) build time and a tree height of ⌈log₂(n + 1)⌉.
     */
    public void build(List<Restaurant> restaurants) {
        Objects.requireNonNull(restaurants, "restaurants must not be null");
        Restaurant[] points = restaurants.stream()
                .filter(Objects::nonNull)
                .toArray(Restaurant[]::new);
        this.size = points.length;
        this.root = buildRecursive(points, 0, points.length, 0);
    }

    private KDNode buildRecursive(Restaurant[] points, int fromInclusive, int toExclusive, int depth) {
        if (fromInclusive >= toExclusive) {
            return null;
        }
        Comparator<Restaurant> comparator = (depth % 2 == AXIS_LAT) ? BY_LAT : BY_LNG;
        Arrays.sort(points, fromInclusive, toExclusive, comparator);

        int median = fromInclusive + (toExclusive - fromInclusive) / 2;
        KDNode node = new KDNode(points[median], depth);
        node.setLeft(buildRecursive(points, fromInclusive, median, depth + 1));
        node.setRight(buildRecursive(points, median + 1, toExclusive, depth + 1));
        return node;
    }

    /**
     * Finds the {@code k} restaurants nearest to the target using branch-and-bound search.
     *
     * @return up to {@code k} results sorted by ascending distance; empty when the tree is empty or k ≤ 0
     */
    public List<RestaurantDistance> findKNearest(double targetLat, double targetLng, int k) {
        return findKNearest(targetLat, targetLng, k, null);
    }

    /**
     * Finds the {@code k} restaurants nearest to the target that satisfy the given filter.
     *
     * @param filter optional predicate to filter restaurants (e.g. minimum rating or veg/non-veg)
     * @return up to {@code k} results sorted by ascending distance; empty when the tree is empty or k ≤ 0
     */
    public List<RestaurantDistance> findKNearest(double targetLat, double targetLng, int k, Predicate<Restaurant> filter) {
        lastVisitedNodes = 0;
        if (root == null || k <= 0) {
            return Collections.emptyList();
        }
        if (!Double.isFinite(targetLat) || !Double.isFinite(targetLng)) {
            throw new IllegalArgumentException("Target coordinates must be finite numbers");
        }

        int effectiveK = Math.min(k, size);
        double lngScale = Math.cos(Math.toRadians(targetLat));

        PriorityQueue<RestaurantDistance> maxHeap = new PriorityQueue<>(
                effectiveK,
                Comparator.comparingDouble(RestaurantDistance::distanceSq).reversed());

        searchRecursive(root, targetLat, targetLng, lngScale, effectiveK, maxHeap, filter);

        List<RestaurantDistance> results = new ArrayList<>(maxHeap);
        results.sort(Comparator.comparingDouble(RestaurantDistance::distanceSq)
                .thenComparingDouble(RestaurantDistance::haversineKm)
                .thenComparing(rd -> rd.restaurant().name()));
        return Collections.unmodifiableList(results);
    }

    private void searchRecursive(KDNode node,
                                  double targetLat,
                                  double targetLng,
                                  double lngScale,
                                  int k,
                                  PriorityQueue<RestaurantDistance> maxHeap,
                                  Predicate<Restaurant> filter) {
        if (node == null) {
            return;
        }
        lastVisitedNodes++;

        Restaurant current = node.getData();
        double dLat = targetLat - current.lat();
        double dLng = (targetLng - current.lng()) * lngScale;
        double distSq = dLat * dLat + dLng * dLng;

        if (filter == null || filter.test(current)) {
            if (maxHeap.size() < k) {
                maxHeap.offer(toResult(current, distSq, targetLat, targetLng));
            } else if (distSq < maxHeap.peek().distanceSq()) {
                maxHeap.poll();
                maxHeap.offer(toResult(current, distSq, targetLat, targetLng));
            }
        }

        boolean splitsOnLat = node.axis() == AXIS_LAT;
        double targetCoord = splitsOnLat ? targetLat : targetLng;
        double nodeCoord = node.splitValue();
        double axisDiff = targetCoord - nodeCoord;

        KDNode nearSide = axisDiff < 0 ? node.getLeft() : node.getRight();
        KDNode farSide = axisDiff < 0 ? node.getRight() : node.getLeft();

        searchRecursive(nearSide, targetLat, targetLng, lngScale, k, maxHeap, filter);

        double scaledAxisDiff = splitsOnLat ? axisDiff : axisDiff * lngScale;
        double planeDistSq = scaledAxisDiff * scaledAxisDiff;
        boolean heapFull = maxHeap.size() == k;
        if (heapFull && planeDistSq >= maxHeap.peek().distanceSq()) {
            return; // Prune: the splitting plane is already farther than the current k-th best.
        }
        searchRecursive(farSide, targetLat, targetLng, lngScale, k, maxHeap, filter);
    }

    private static RestaurantDistance toResult(Restaurant r, double distSq, double targetLat, double targetLng) {
        double km = Restaurant.calculateHaversineDistance(targetLat, targetLng, r.lat(), r.lng());
        return new RestaurantDistance(r, distSq, km);
    }

    /** @return number of restaurants indexed. */
    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** @return number of levels in the tree (0 for an empty tree). */
    public int height() {
        return heightOf(root);
    }

    private static int heightOf(KDNode node) {
        if (node == null) {
            return 0;
        }
        return 1 + Math.max(heightOf(node.getLeft()), heightOf(node.getRight()));
    }

    /** @return nodes examined by the most recent {@link #findKNearest} call (pruning diagnostic). */
    public int getLastVisitedNodes() {
        return lastVisitedNodes;
    }
}
