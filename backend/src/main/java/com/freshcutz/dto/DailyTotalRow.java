package com.freshcutz.dto;

import java.math.BigDecimal;

/** Spring Data JPA projection interface for OrderRepository's
 * findDailyTotalsBetween — a NATIVE query (Postgres GROUP BY, not JPQL,
 * since grouping by a shop-timezone-local calendar day needs Postgres's
 * own AT TIME ZONE/to_char functions). Getter names match the query's
 * column aliases case-insensitively; there's no constructor to call
 * (unlike the SELECT NEW-based DTOs elsewhere), Spring Data implements
 * this interface itself at runtime, backed by each result row. */
public interface DailyTotalRow {
    String getDay();
    BigDecimal getTotal();
    Long getOrders();
}
