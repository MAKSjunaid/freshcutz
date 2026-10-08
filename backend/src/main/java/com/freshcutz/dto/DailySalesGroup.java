package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One calendar day of the Sales tab's By Day list — see
 * SalesService.getOverview(). Carries the day's count and money for EVERY
 * status (each dated by its own moment — see OrderMixSql), so the browser
 * can show All / New / Delivered / Cancelled by adding up the parts it
 * needs instead of asking the server again for each filter. Deliberately
 * NOT the individual orders: those are fetched separately, only once an
 * admin expands that day — see AdminOrderController's
 * GET /summary/range/daily/{date} and SalesService.getDayOrders(). */
@Getter
@Builder
@AllArgsConstructor
public class DailySalesGroup {
    private String date; // yyyy-MM-dd, shop-local
    private long newOrders;
    private long deliveredOrders;
    private long cancelledOrders;
    private BigDecimal newTotal;
    private BigDecimal deliveredTotal;
    private BigDecimal cancelledTotal;
}
