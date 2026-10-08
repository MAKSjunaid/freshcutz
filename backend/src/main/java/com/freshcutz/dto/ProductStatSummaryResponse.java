package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One product's row in the admin Items tab's ranked list — total quantity
 * sold and total revenue earned (DELIVERED orders only, optionally
 * restricted to an applied date range), summed across every variant of
 * this product. See ProductSalesStatRepository.findTopProductsPage for
 * where these two numbers actually come from. */
@Getter
@Builder
@AllArgsConstructor
public class ProductStatSummaryResponse {
    private Long productId;
    private String productName;
    private long quantity;
    private BigDecimal revenue;
    // How much of quantity/revenue comes from New / Cancelled orders (the
    // rest is Delivered) — lets the browser derive the New / Delivered /
    // Cancelled views from one All response without asking again. Zero
    // when the list was fetched for a Delivered-only filter.
    private long newQuantity;
    private BigDecimal newRevenue;
    private long cancelledQuantity;
    private BigDecimal cancelledRevenue;
}
