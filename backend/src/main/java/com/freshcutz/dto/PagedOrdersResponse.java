package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** One page of the admin Orders tab's list — backs infinite scroll.
 * `orders` is just this page's rows; `hasMore` tells the frontend
 * whether there's a next page to load or whether it's reached the end.
 * `totalElements` is only ever populated (status=NEW, no search, page 0)
 * — the one case the frontend actually reads it for, to drive the
 * Orders-tab "new" badge the same way the unpaged list's .length used
 * to, instead of a separate /summary/new-count call. Every other
 * tab/page leaves it 0, since computing a true total would mean an
 * extra COUNT(*) round trip that nothing uses — see
 * OrderService.listPaged. */
@Getter
@Builder
@AllArgsConstructor
public class PagedOrdersResponse {
    private List<OrderResponse> orders;
    private long totalElements;
    private boolean hasMore;
}
