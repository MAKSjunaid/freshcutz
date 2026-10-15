package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** How many orders are currently New/Delivered/Cancelled within the
 * requested window. New counts by placement date (createdAt) — "of the
 * orders placed in this window, how many are still unactioned" —  while
 * Delivered/Cancelled count by when they were actually actioned
 * (statusUpdatedAt, the same instant as deliveredAt for a Delivered
 * order), matching the date basis the Items tab's own product rankings
 * use (delivery date), so an order placed on one day but delivered on
 * another counts as Delivered for the day it was actually delivered.
 * New deliberately does NOT follow statusUpdatedAt the same way,
 * because reopening an order back to New bumps statusUpdatedAt to now
 * without changing when it was placed — see SalesService.getStatusCounts
 * / OrderRepository.findStatusCountsBetween for the full reasoning.
 * `all` is just newOrders+delivered+cancelled, summed server-side so
 * the frontend never has to. */
@Getter
@Builder
@AllArgsConstructor
public class OrderStatusCountsResponse {
    private long all;
    private long newOrders;
    private long delivered;
    private long cancelled;
}
