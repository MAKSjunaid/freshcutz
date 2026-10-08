package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** One page of the Items tab's ranked product list — same "ask for one
 * more than you need" hasMore convention every other paged tab in this
 * admin panel already uses (see PagedOrdersResponse/PagedCustomersResponse). */
@Getter
@Builder
@AllArgsConstructor
public class PagedProductStatsResponse {
    private List<ProductStatSummaryResponse> items;
    private boolean hasMore;
}
