package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** One customer's history for the admin "Customers" tab — grouped by phone
 * number, with every past order (and what was in it) attached so the admin
 * can see at a glance who's a repeat customer and what they usually order. */
@Getter
@Builder
@AllArgsConstructor
public class CustomerSummaryResponse {

    private String phone;
    private String name;
    private int orderCount;
    private BigDecimal totalSpent;
    private Instant lastOrderAt;
    private List<OrderResponse> orders;
}
