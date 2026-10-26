package com.spatial.restaurantfinder.spatial;

import com.spatial.restaurantfinder.model.Restaurant;

/**
 * A single node of the 2D KD-Tree.
 * Nodes at even depth split on latitude, nodes at odd depth split on longitude.
 *
 * <p>Each node stores one restaurant (the median point of its sub-range) plus links to two
 * sub-trees: {@code left} holds points whose coordinate on this node's axis is &lt;= the split value,
 * {@code right} holds points whose coordinate is &gt;= it. Storing the depth lets the node work out
 * its own splitting axis in O(1) instead of the caller having to track it.</p>
 *
 * <p>Space: O(1) per node (one reference to the restaurant, one int, two child references), so a
 * tree of n restaurants uses O(n) memory.</p>
 */
public final class KDNode {

    /** The restaurant stored at this node; its coordinates define the splitting plane. */
    private final Restaurant data;
    /** Distance from the root (root = 0). Determines the splitting axis. */
    private final int depth;
    /** Sub-tree with smaller (or equal) coordinates on this node's axis. */
    private KDNode left;
    /** Sub-tree with larger (or equal) coordinates on this node's axis. */
    private KDNode right;

    /**
     * Creates a leaf node; children are attached later by {@link KDTree} during the build.
     * Time: O(1).
     */
    public KDNode(Restaurant data, int depth) {
        // Fail fast: a null point or negative depth would break axis selection and distance maths.
        if (data == null) {
            throw new IllegalArgumentException("KDNode data must not be null");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("KDNode depth must be >= 0");
        }
        this.data = data;
        this.depth = depth;
    }

    /** @return the restaurant stored at this node. Time: O(1). */
    public Restaurant getData() {
        return data;
    }

    /** @return this node's depth in the tree. Time: O(1). */
    public int getDepth() {
        return depth;
    }

    /** @return the left child, or null. Time: O(1). */
    public KDNode getLeft() {
        return left;
    }

    /** Attaches the left sub-tree. Time: O(1). */
    public void setLeft(KDNode left) {
        this.left = left;
    }

    /** @return the right child, or null. Time: O(1). */
    public KDNode getRight() {
        return right;
    }

    /** Attaches the right sub-tree. Time: O(1). */
    public void setRight(KDNode right) {
        this.right = right;
    }

    /**
     * Axis alternates with depth (lat, lng, lat, lng, ...). Alternating is the standard KD-Tree rule
     * for k = 2 dimensions: it cuts space into ever-smaller rectangles so both coordinates help pruning.
     *
     * <p>Time: O(1).</p>
     *
     * @return 0 when this node splits on latitude, 1 when it splits on longitude.
     */
    public int axis() {
        return depth % 2;
    }

    /**
     * Time: O(1).
     *
     * @return this node's coordinate on its splitting axis.
     */
    public double splitValue() {
        return axis() == 0 ? data.lat() : data.lng();
    }

    /** @return true when the node has no children. Time: O(1). */
    public boolean isLeaf() {
        return left == null && right == null;
    }

    /** Debug representation. Time: O(L) for the name. */
    @Override
    public String toString() {
        return "KDNode{depth=" + depth + ", axis=" + (axis() == 0 ? "lat" : "lng") + ", data=" + data.name() + "}";
    }
}
