package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/** One page of the Sales tab's "By Customer" ranking — same shape as
 * PagedCustomersResponse, for the same infinite-scroll reason. */
@Getter
@Builder
@AllArgsConstructor
public class PagedCustomerSalesResponse {
    private List<CustomerSalesResponse> customers;
    private boolean hasMore;
}
