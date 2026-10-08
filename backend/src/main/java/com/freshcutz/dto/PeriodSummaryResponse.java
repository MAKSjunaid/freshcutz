package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** Everything the Sales tab's header shows for whichever period is
 * selected, in one response: the money and delivered-order count for
 * that period, plus the New/Delivered/Cancelled status boxes. Travels inside DailyOverviewResponse together
 * with the first page of the By Day list. See SalesService.getOverview for how
 * each number is dated (they deliberately use different date columns —
 * see OrderMixSql). */
@Getter
@Builder
@AllArgsConstructor
public class PeriodSummaryResponse {
    private BigDecimal total;
    private long orders;
    private long all;
    private long newOrders;
    private long delivered;
    private long cancelled;
}
