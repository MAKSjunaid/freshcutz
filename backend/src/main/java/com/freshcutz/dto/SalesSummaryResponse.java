package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
@AllArgsConstructor
public class SalesSummaryResponse {
    private BigDecimal todayTotal;
    private long todayOrders;
    private BigDecimal weekTotal;
    private long weekOrders;
    private BigDecimal monthTotal;
    private long monthOrders;
    private BigDecimal allTimeTotal;
    private long allTimeOrders;
    private long newOrders;
}
