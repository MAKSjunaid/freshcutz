package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** Just the "new orders" count — backs the small badge on the Orders tab
 * button, which needs to stay live everywhere in the admin panel, not
 * just while the Sales tab is open. Deliberately its own tiny endpoint
 * (a single COUNT(*)) instead of reusing SalesSummaryResponse, which also
 * computes four SUM+COUNT buckets the badge doesn't need. */
@Getter
@Builder
@AllArgsConstructor
public class NewOrdersCountResponse {
    private long newOrders;
}
