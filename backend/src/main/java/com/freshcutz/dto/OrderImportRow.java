package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One parsed row from an uploaded orders .xlsx file, ready to be applied
 * back onto the matching order by its order code. */
@Getter
@Builder
@AllArgsConstructor
public class OrderImportRow {
    private int rowNumber;
    private String orderCode;
    private String customerName;
    private String phone;
    private String flatNo;
    private String addressLine;
    private String landmark;
    private String notes;
    private String status;
    private BigDecimal totalAmount;
}
