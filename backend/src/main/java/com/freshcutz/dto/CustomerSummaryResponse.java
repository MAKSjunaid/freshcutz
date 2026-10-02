package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** One customer's summary for the admin "Customers" tab — grouped by phone
 * number, with their current delivery address so an admin searching by
 * phone or name can see where to deliver without having to open a past
 * order to find it. flatNo/addressLine/landmark come from their saved
 * Customer profile when one exists, falling back to their most recent
 * order's address otherwise (see CustomerRepository.findCustomerSummariesPage)
 * — the same fallback the checkout-side lookup already uses.
 *
 * Deliberately doesn't carry this customer's full order history anymore
 * (it used to, as a `List<OrderResponse> orders` field) — that meant every
 * single customer's every order, items included, was being sent down on
 * every tab visit and every search keystroke, whether or not anyone ever
 * looked at it. See AdminCustomerController's GET /{phone}/orders for
 * where that now lives instead: fetched once, only for whichever
 * customer the admin actually taps "View past orders" on. */
@Getter
@Builder
@AllArgsConstructor
public class CustomerSummaryResponse {

    private String phone;
    private String name;
    private String flatNo;
    private String addressLine;
    private String landmark;
    private int orderCount;
    private BigDecimal totalSpent;
    private Instant lastOrderAt;
}
