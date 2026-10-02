package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** One page of the admin Customers tab's list — backs its infinite scroll,
 * the same shape PagedOrdersResponse is for the Orders tab. `customers` is
 * just this page's rows; `hasMore` tells the frontend whether there's a
 * next page to load. No totalElements field here — nothing on this tab
 * reads a running total the way the Orders tab's New-tab badge does. */
@Getter
@Builder
@AllArgsConstructor
public class PagedCustomersResponse {
    private List<CustomerSummaryResponse> customers;
    private boolean hasMore;
}
