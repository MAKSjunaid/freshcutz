package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One day's total within an admin-picked date rangee — see
 * SalesService.getDailyRangeSummary(). Just the day, its total, and its
 * order count: deliberately NOT the individual orders themselves. An
 * Apply click used to ship every order in the whole range up front (as
 * lightweight rows, but still one per order — thousands of them for a
 * wide range); now it only ever ships one row per DAY. The Sales tab
 * fetches a given day's individual orders separately, only once an admin
 * actually expands that day — see AdminOrderController's
 * GET /summary/range/daily/{date} and SalesService.getDayOrders(). */
@Getter
@Builder
@AllArgsConstructor
public class DailySalesGroup {
    private String date; // yyyy-MM-dd, shop-local
    private BigDecimal total;
    private long orders;
}
