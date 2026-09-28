package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/** SQL-side SUM+COUNT aggregate, built directly by a JPQL constructor
 * expression (see OrderRepository's sumAndCountDelivered* queries) so the
 * database does the adding-up instead of the app loading every matching
 * order into memory and summing them in a Java loop. */
@Getter
@AllArgsConstructor
public class SumCountRow {
    private BigDecimal total;
    private long count;
}
