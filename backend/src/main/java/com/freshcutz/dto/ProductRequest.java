package com.freshcutz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Payload the admin panel sends to create or update a product. */
@Getter
@Setter
public class ProductRequest {

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    private String category;

    @NotBlank(message = "First variant label is required, e.g. '500g'")
    private String variant1Label;

    @NotNull(message = "First variant price is required")
    @PositiveOrZero
    private BigDecimal variant1Price;

    private BigDecimal variant1Mrp;

    /** Optional second size, e.g. "1kg". Leave both null if not needed. */
    private String variant2Label;

    private BigDecimal variant2Price;

    private BigDecimal variant2Mrp;

    private boolean enabled = true;

    private boolean outOfStock = false;

    private int displayOrder = 0;
}
