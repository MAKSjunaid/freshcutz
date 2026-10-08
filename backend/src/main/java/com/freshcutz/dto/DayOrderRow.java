package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** One order within a day's breakdown on the Sales tab's By Day view —
 * lightweight (no address, no items), like DailySalesRow, but for any
 * status: `at` is the moment this order's status happened (placed time
 * for New, delivered time for Delivered, cancelled time for Cancelled)
 * and `status` says which one it is, so the row can show a badge. The
 * full order is still fetched separately, only when a row is tapped. */
@Getter
@Builder
@AllArgsConstructor
public class DayOrderRow {
    private Long id;
    private String orderCode;
    private String customerName;
    private Instant at;
    private BigDecimal totalAmount;
    private String status;
}
