package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** Sales total for a custom from/to date range, picked on the admin Sales tab. */
@Getter
@Builder
@AllArgsConstructor
public class RangeSummaryResponse {
    private String from;
    private String to;
    private BigDecimal total;
    private long orders;
}
