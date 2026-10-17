package com.freshcutz.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * One product as the customer website receives it: all the normal product fields (flattened into the same JSON
 * object) plus two numbers that let the page know exactly where the product sits, so it can reuse products it
 * already holds:
 * <ul>
 *   <li>{@code rank} — its place among ALL products, 0 = first (the order of the "All" tab);</li>
 *   <li>{@code catRank} — its place inside its own category, 0 = first.</li>
 * </ul>
 */
public final class StoreItem {

    private final ProductResponse product;
    private final int rank;
    private final int catRank;

    public StoreItem(ProductResponse product, int rank, int catRank) {
        this.product = product;
        this.rank = rank;
        this.catRank = catRank;
    }

    @JsonUnwrapped
    public ProductResponse getProduct() {
        return product;
    }

    public int getRank() {
        return rank;
    }

    public int getCatRank() {
        return catRank;
    }
}
