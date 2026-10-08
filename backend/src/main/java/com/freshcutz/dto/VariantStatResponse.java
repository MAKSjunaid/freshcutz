package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One variant's slice of a single product's sales — e.g. "500 gm: 42
 * sold, ₹7,560" versus "1 kg: 18 sold, ₹6,480" for the same product.
 * Backs the Items tab's tap-to-expand drill-down (see
 * ProductStatsService.listVariantBreakdown), scoped to whatever date
 * range (if any) is currently applied on the tab. */
@Getter
@Builder
@AllArgsConstructor
public class VariantStatResponse {
    private String variantLabel;
    private long quantity;
    private BigDecimal revenue;
}
