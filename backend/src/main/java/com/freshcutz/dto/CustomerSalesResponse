package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One row of the Sales tab's "By Customer" view: a customer plus the
 * total of their orders, and how many orders that covers, within the
 * selected period (or all time) and status filter. Which orders count
 * depends on that filter: Delivered gives actual sales, All adds still-New
 * and Cancelled orders too (newOrders/cancelledOrders say how many of
 * `orders` they are, so the card can show the split). Unlike
 * CustomerSummaryResponse (the Customer Info directory row, all-time). */
@Getter
@Builder
@AllArgsConstructor
public class CustomerSalesResponse {
    private String phone;
    private String name;
    private String flatNo;
    private String addressLine;
    private String landmark;
    private int orders;
    private BigDecimal spent;
    // How many of `orders` are New / Cancelled (the rest are Delivered) —
    // 0 unless the selected status filter includes them.
    private int newOrders;
    private int cancelledOrders;
    // How much of `spent` those New / Cancelled orders account for (the
    // rest is Delivered). With newOrders/cancelledOrders this lets the
    // browser re-derive the New / Delivered / Cancelled views from one
    // All response instead of asking the server again.
    private BigDecimal newSpent;
    private BigDecimal cancelledSpent;
}
