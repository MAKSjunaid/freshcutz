package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/** One delivered order within a day's breakdownn — see DailySalesGroup.
 * Deliberately lightweight (no address, no items) — just the columns the
 * Sales tab's collapsed row shows. The full order is fetched separately,
 * one at a time, only when an admin actually taps a row open. */
@Getter
@Builder
@AllArgsConstructor
public class DailySalesRow {
    private Long id;
    private String orderCode;
    private String customerName;
    private Instant deliveredAt;
    private BigDecimal totalAmount;
}
