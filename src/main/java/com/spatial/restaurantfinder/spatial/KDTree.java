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
 * <p><b>Why a KD-Tree?</b> The naive way to find the k nearest restaurants is a linear scan: compute
 * the distance to all n restaurants and sort, which is O(n log n) per query (or O(n log k) with a
 * heap). A KD-Tree spends O(n log² n) once to build an index, after which each query only has to
 * look at the parts of space close to the target — on average O(log n) nodes for small k — because
 * whole sub-trees that are provably too far away are skipped ("pruned"). The user runs many queries
 * per city (different k, different filters) on the same data, so the one-off build cost pays off.</p>
 *
 * <p><b>Complexity summary</b> (n = restaurants indexed, k = neighbours requested):</p>
 * <table>
 *   <tr><th>Operation</th><th>Time</th><th>Space</th></tr>
 *   <tr><td>{@link #build}</td><td>O(n log² n)</td><td>O(n) nodes + O(log n) recursion stack</td></tr>
 *   <tr><td>{@link #findKNearest}</td><td>average O((log n + k) · log k), worst O(n log k)</td><td>O(k) heap + O(h) stack</td></tr>
 *   <tr><td>{@link #height}</td><td>O(n)</td><td>O(h) stack</td></tr>
 *   <tr><td>{@link #size}, {@link #isEmpty}</td><td>O(1)</td><td>O(1)</td></tr>
 * </table>
 * <p>(h = tree height = ⌈log₂(n + 1)⌉ because the build always splits at the median.)</p>
 *
 * <p><b>Distance metric.</b> Ranking uses squared Euclidean distance in an equirectangular projection
 * centred on the query: {@code dLat² + (dLng · cos(targetLat))²}. One degree of longitude is shorter
 * than one degree of latitude everywhere except the equator, so scaling the longitude difference makes
 * the coordinate-space ranking agree with real ground distance at city scale. The tree itself is built
 * on raw latitude/longitude; only the query-time metric is scaled, which keeps the axis bound a valid
 * lower bound for pruning. Final results also carry the exact Haversine distance in kilometres.</p>
 *
 * <p>Why squared distance? Comparing d² gives the same order as comparing d, but skips a
 * {@code Math.sqrt} on every visited node.</p>
 *
 * <p>Queries near the ±180° meridian are not wrapped; restaurants on the far side of the antimeridian
 * are treated as distant.</p>
 *
 * <p>Build and query are not synchronized. Build once, then query from a single thread, or guard
 * externally.</p>
 */
public final class KDTree {

    private static final int AXIS_LAT = 0;

    /**
     * Comparators used to sort a sub-range on the current axis during the build. The second key
     * (the other coordinate) is a tie-breaker so that sorting is deterministic when two restaurants
     * share the same latitude (or longitude).
     */
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
        /** Time: O(1). */
        public RestaurantDistance {
            Objects.requireNonNull(restaurant, "restaurant must not be null");
        }

        /** @return true great-circle distance in metres. Time: O(1). */
        public double haversineMeters() {
            return haversineKm * 1000.0;
        }
    }

    /** Root of the tree; null when the tree is empty. */
    private KDNode root;
    /** Number of restaurants indexed, cached so {@link #size()} is O(1) instead of an O(n) walk. */
    private int size;
    /** Diagnostic counter: how many nodes the last query touched (shows how well pruning worked). */
    private int lastVisitedNodes;

    /**
     * Builds a balanced tree from the given restaurants, replacing any previous contents.
     * Each level sorts its sub-range on the current axis and promotes the median, giving
     * O(n log² n) build time and a tree height of ⌈log₂(n + 1)⌉.
     *
     * <p><b>Why the median?</b> Picking the median as the split point puts half of the points on each
     * side, so the tree is balanced. A balanced tree has height O(log n), which is what makes queries
     * fast. Inserting points one at a time instead could produce a tree of height O(n) (e.g. if the
     * points arrive sorted), turning queries into a linear scan.</p>
     *
     * <p><b>Why O(n log² n)?</b> The recursion has O(log n) levels. On each level the sub-ranges together
     * cover all n points, and sorting them costs O(n log n) in total. log n levels × n log n = n log² n.
     * (A linear-time median selection such as quickselect would bring this down to O(n log n), but for
     * the ≤ 40 places Google returns, sorting is simpler and just as fast in practice.)</p>
     *
     * <p>Time: O(n log² n). Space: O(n) for the array copy and the nodes, plus O(log n) recursion depth.</p>
     */
    public void build(List<Restaurant> restaurants) {
        Objects.requireNonNull(restaurants, "restaurants must not be null");
        // Copy into an array (O(n)) so we can sort sub-ranges in place without touching the caller's list,
        // and drop any null entries so they can never reach a comparator.
        Restaurant[] points = restaurants.stream()
                .filter(Objects::nonNull)
                .toArray(Restaurant[]::new);
        this.size = points.length;
        this.root = buildRecursive(points, 0, points.length, 0);
    }

    /**
     * Recursively builds the sub-tree for {@code points[fromInclusive, toExclusive)}.
     *
     * <p>Recurrence: T(m) = 2·T(m/2) + O(m log m) → T(n) = O(n log² n).</p>
     *
     * @return the root of the sub-tree, or null when the range is empty (base case)
     */
    private KDNode buildRecursive(Restaurant[] points, int fromInclusive, int toExclusive, int depth) {
        // Base case: empty range → no node.
        if (fromInclusive >= toExclusive) {
            return null;
        }
        // Pick the axis for this level (lat on even depths, lng on odd) and sort only this sub-range.
        // Arrays.sort on objects is a stable TimSort: O(m log m) for a range of m points.
        Comparator<Restaurant> comparator = (depth % 2 == AXIS_LAT) ? BY_LAT : BY_LNG;
        Arrays.sort(points, fromInclusive, toExclusive, comparator);

        // The middle element is the median; everything before it goes left, everything after goes right.
        // Written as from + (to - from) / 2 instead of (from + to) / 2 to avoid int overflow.
        int median = fromInclusive + (toExclusive - fromInclusive) / 2;
        KDNode node = new KDNode(points[median], depth);
        node.setLeft(buildRecursive(points, fromInclusive, median, depth + 1));
        node.setRight(buildRecursive(points, median + 1, toExclusive, depth + 1));
        return node;
    }

    /**
     * Finds the {@code k} restaurants nearest to the target using branch-and-bound search.
     * Same as {@link #findKNearest(double, double, int, Predicate)} with no filter.
     *
     * @return up to {@code k} results sorted by ascending distance; empty when the tree is empty or k ≤ 0
     */
    public List<RestaurantDistance> findKNearest(double targetLat, double targetLng, int k) {
        return findKNearest(targetLat, targetLng, k, null);
    }

    /**
     * Finds the {@code k} restaurants nearest to the target that satisfy the given filter.
     *
     * <p><b>Algorithm (branch and bound):</b></p>
     * <ol>
     *   <li>Keep a <b>max-heap</b> of the best k candidates found so far. The heap's top is the
     *       <i>worst</i> of the current best k, i.e. the distance a new point must beat. A max-heap is
     *       used (not a min-heap) because we need O(1) access to that k-th best distance and O(log k)
     *       removal of it when a closer point turns up.</li>
     *   <li>Walk down the tree towards the target first ("near side"), which quickly fills the heap with
     *       good candidates and makes the pruning bound tight early.</li>
     *   <li>On the way back up, only visit the other side ("far side") if the splitting plane is closer
     *       than the current k-th best. If it isn't, nothing on that side can be closer either, so the
     *       whole sub-tree is skipped.</li>
     * </ol>
     *
     * <p><b>Filtering inside the search</b> (instead of filtering the results afterwards) guarantees we
     * return k <i>matching</i> restaurants, not "the k nearest, minus the ones that failed the filter".</p>
     *
     * <p><b>Time:</b> average O((log n + k) · log k) for evenly spread points — about log n nodes to reach
     * the target's leaf plus a few neighbouring cells, each costing an O(log k) heap operation. Worst
     * case O(n log k): every node is visited, e.g. when the filter rejects most restaurants so the heap
     * never fills up and nothing can be pruned. The final sort adds O(k log k).</p>
     *
     * <p><b>Space:</b> O(k) for the heap and result list, plus O(h) = O(log n) recursion stack.</p>
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

        // Can never return more than n results, so cap k (also keeps the heap's initial capacity sensible).
        int effectiveK = Math.min(k, size);
        // Computed once per query (O(1)) instead of once per node: cos(lat) shrinks longitude degrees
        // to match latitude degrees in ground distance at the query's latitude.
        double lngScale = Math.cos(Math.toRadians(targetLat));

        // Max-heap ordered by distance: reversed() makes the LARGEST distance sit at the top.
        PriorityQueue<RestaurantDistance> maxHeap = new PriorityQueue<>(
                effectiveK,
                Comparator.comparingDouble(RestaurantDistance::distanceSq).reversed());

        searchRecursive(root, targetLat, targetLng, lngScale, effectiveK, maxHeap, filter);

        // A heap is only partially ordered, so copy it out and sort nearest-first: O(k log k).
        // Ties are broken by real distance, then by name, so the output order is deterministic.
        List<RestaurantDistance> results = new ArrayList<>(maxHeap);
        results.sort(Comparator.comparingDouble(RestaurantDistance::distanceSq)
                .thenComparingDouble(RestaurantDistance::haversineKm)
                .thenComparing(rd -> rd.restaurant().name()));
        // Read-only view so callers can't modify the result list.
        return Collections.unmodifiableList(results);
    }

    /**
     * Recursive branch-and-bound step for one node.
     *
     * <p>Work per visited node: O(1) distance maths + O(1) filter test (cost of the predicate itself)
     * + O(log k) for a heap offer/poll. The number of visited nodes is what pruning reduces.</p>
     */
    private void searchRecursive(KDNode node,
                                  double targetLat,
                                  double targetLng,
                                  double lngScale,
                                  int k,
                                  PriorityQueue<RestaurantDistance> maxHeap,
                                  Predicate<Restaurant> filter) {
        // Base case: fell off the tree.
        if (node == null) {
            return;
        }
        lastVisitedNodes++;

        // 1. Consider the point stored at this node as a candidate.
        Restaurant current = node.getData();
        double dLat = targetLat - current.lat();
        double dLng = (targetLng - current.lng()) * lngScale;
        double distSq = dLat * dLat + dLng * dLng;

        if (filter == null || filter.test(current)) {
            if (maxHeap.size() < k) {
                // Heap not full yet: every matching point is a candidate. O(log k).
                maxHeap.offer(toResult(current, distSq, targetLat, targetLng));
            } else if (distSq < maxHeap.peek().distanceSq()) {
                // Heap full: only keep this point if it beats the current worst (the top). Evict the
                // worst and insert the new one: O(log k) + O(log k).
                maxHeap.poll();
                maxHeap.offer(toResult(current, distSq, targetLat, targetLng));
            }
            // else: farther than all k current candidates → ignore.
        }

        // 2. Decide which child is on the target's side of this node's splitting plane.
        boolean splitsOnLat = node.axis() == AXIS_LAT;
        double targetCoord = splitsOnLat ? targetLat : targetLng;
        double nodeCoord = node.splitValue();
        double axisDiff = targetCoord - nodeCoord; // signed distance from the target to the plane

        KDNode nearSide = axisDiff < 0 ? node.getLeft() : node.getRight();
        KDNode farSide = axisDiff < 0 ? node.getRight() : node.getLeft();

        // 3. Always search the near side first — the closest points are most likely there.
        searchRecursive(nearSide, targetLat, targetLng, lngScale, k, maxHeap, filter);

        // 4. Bound check for the far side. Any point on the far side is at least |axisDiff| away along
        //    this axis, so planeDistSq is a lower bound on its distance (the other axis can only add to it).
        //    Longitude differences are scaled the same way as in the distance formula above so the bound
        //    is in the same units as the heap's distances.
        double scaledAxisDiff = splitsOnLat ? axisDiff : axisDiff * lngScale;
        double planeDistSq = scaledAxisDiff * scaledAxisDiff;
        // We can only prune once we have k candidates; before that, every matching point still counts.
        boolean heapFull = maxHeap.size() == k;
        if (heapFull && planeDistSq >= maxHeap.peek().distanceSq()) {
            return; // Prune: the splitting plane is already farther than the current k-th best.
        }
        // 5. The far side might still hold something closer than our current k-th best: search it.
        searchRecursive(farSide, targetLat, targetLng, lngScale, k, maxHeap, filter);
    }

    /**
     * Wraps a candidate in a result object. The exact Haversine distance is only computed here, i.e.
     * for points that actually enter the heap, rather than for every visited node — trig is far more
     * expensive than the squared-distance arithmetic used for ranking.
     *
     * <p>Time: O(1).</p>
     */
    private static RestaurantDistance toResult(Restaurant r, double distSq, double targetLat, double targetLng) {
        double km = Restaurant.calculateHaversineDistance(targetLat, targetLng, r.lat(), r.lng());
        return new RestaurantDistance(r, distSq, km);
    }

    /** @return number of restaurants indexed. Time: O(1) (cached during build). */
    public int size() {
        return size;
    }

    /** @return true when nothing is indexed. Time: O(1). */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Height is not cached, so it is recomputed by visiting every node. It is only called once per
     * city for the "Indexed ... height H" message, so the O(n) cost doesn't matter.
     *
     * <p>Time: O(n). Space: O(h) recursion stack.</p>
     *
     * @return number of levels in the tree (0 for an empty tree).
     */
    public int height() {
        return heightOf(root);
    }

    /** Height of a sub-tree = 1 + height of its taller child. Time: O(size of sub-tree). */
    private static int heightOf(KDNode node) {
        if (node == null) {
            return 0;
        }
        return 1 + Math.max(heightOf(node.getLeft()), heightOf(node.getRight()));
    }

    /**
     * Time: O(1).
     *
     * @return nodes examined by the most recent {@link #findKNearest} call (pruning diagnostic).
     */
    public int getLastVisitedNodes() {
        return lastVisitedNodes;
    }
}
