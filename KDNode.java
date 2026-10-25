package com.spatial.restaurantfinder.spatial;

import com.spatial.restaurantfinder.model.Restaurant;

/**
 * A single node of the 2D KD-Tree.
 * Nodes at even depth split on latitude, nodes at odd depth split on longitude.
 */
public final class KDNode {

    private final Restaurant data;
    private final int depth;
    private KDNode left;
    private KDNode right;

    public KDNode(Restaurant data, int depth) {
        if (data == null) {
            throw new IllegalArgumentException("KDNode data must not be null");
        }
        if (depth < 0) {
            throw new IllegalArgumentException("KDNode depth must be >= 0");
        }
        this.data = data;
        this.depth = depth;
    }

    public Restaurant getData() {
        return data;
    }

    public int getDepth() {
        return depth;
    }

    public KDNode getLeft() {
        return left;
    }

    public void setLeft(KDNode left) {
        this.left = left;
    }

    public KDNode getRight() {
        return right;
    }

    public void setRight(KDNode right) {
        this.right = right;
    }

    /** @return 0 when this node splits on latitude, 1 when it splits on longitude. */
    public int axis() {
        return depth % 2;
    }

    /** @return this node's coordinate on its splitting axis. */
    public double splitValue() {
        return axis() == 0 ? data.lat() : data.lng();
    }

    public boolean isLeaf() {
        return left == null && right == null;
    }

    @Override
    public String toString() {
        return "KDNode{depth=" + depth + ", axis=" + (axis() == 0 ? "lat" : "lng") + ", data=" + data.name() + "}";
    }
}
