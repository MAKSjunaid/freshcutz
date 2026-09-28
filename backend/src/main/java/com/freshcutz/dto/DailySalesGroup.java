package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/** One day's worth of delivered orders within an admin-picked date range —
 * see SalesService.getDailyRangeSummary(). Grouped and sorted by delivery
 * date (not order-placed date), same rule the rest of the Sales tab uses,
 * newest day first. `rows` is deliberately lightweight (code, customer,
 * date, total — no address, no items) so an Apply click only ever ships
 * one small SQL-projected row per order; the Sales tab fetches an order's
 * full detail (address, notes, items) separately, one at a time, only when
 * an admin taps that row open. */
@Getter
@Builder
@AllArgsConstructor
public class DailySalesGroup {
    private String date; // yyyy-MM-dd, shop-local
    private BigDecimal total;
    private long orders;
    private List<DailySalesRow> rows;
}
