package com.freshcutz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Payload the storefront sends when a customer places an order. */
@Getter
@Setter
public class OrderRequest {

    @NotBlank(message = "Name is required")
    private String customerName;

    @NotBlank(message = "Phone number is required")
    private String phone;

    @NotBlank(message = "Flat / House no. is required")
    private String flatNo;

    @NotBlank(message = "Address is required")
    private String addressLine;

    private String landmark;

    private String notes;

    @NotEmpty(message = "Cart is empty")
    @Valid
    private List<OrderItemRequest> items;
}
