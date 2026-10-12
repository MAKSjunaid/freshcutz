package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A lighter stand-in for Product, used only by the catalog LIST endpoints
 * (customer storefront + admin Products tab). It deliberately leaves out
 * Product.imageData: loading full Product entities for every list request
 * was pulling every product's raw photo bytes out of the database on each
 * request, even though the JSON response never includes them (see
 * ProductResponse, which only ever exposes a computed image URL). With the
 * storefront polling every 15 seconds, that wasted internal database
 * traffic is what exhausted Neon's free monthly transfer allowance in a
 * few hours. hasImage — not the bytes themselves — is all ProductResponse
 * needs to build that URL. See ProductRepository.listViewForCustomers()/
 * listViewForAdmin() for the query that fills this in.
 */
@Getter
@AllArgsConstructor
public class ProductListRow {

    private final Long id;
    private final String name;
    private final String description;
    private final String category;
    private final String imageUrl;
    private final boolean hasImage;
    private final Instant updatedAt;
    private final String variant1Label;
    private final BigDecimal variant1Price;
    private final BigDecimal variant1Mrp;
    private final String variant2Label;
    private final BigDecimal variant2Price;
    private final BigDecimal variant2Mrp;
    private final boolean enabled;
    private final boolean outOfStock;
    private final int displayOrder;
}