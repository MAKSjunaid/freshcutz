package com.freshcutz.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrderItemRequest {

    @NotNull(message = "productId is required")
    private Long productId;

    /** Which priced size was picked: "variant1" or "variant2". */
    @Pattern(regexp = "variant1|variant2", message = "variant must be 'variant1' or 'variant2'")
    private String variant;

    @Min(value = 1, message = "quantity must be at least 1")
    private int quantity;
}
