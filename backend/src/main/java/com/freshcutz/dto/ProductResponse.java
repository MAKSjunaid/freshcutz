package com.freshcutz.dto;

import com.freshcutz.entity.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
@AllArgsConstructor
public class ProductResponse {

    private Long id;
    private String name;
    private String description;
    private String category;
    private String imageUrl;
    private String variant1Label;
    private BigDecimal variant1Price;
    private BigDecimal variant1Mrp;
    private String variant2Label;
    private BigDecimal variant2Price;
    private BigDecimal variant2Mrp;
    private boolean enabled;
    private boolean outOfStock;
    private int displayOrder;

    public static ProductResponse from(Product p) {
        return ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .category(p.getCategory())
                .imageUrl(imageUrlFor(p))
                .variant1Label(p.getVariant1Label())
                .variant1Price(p.getVariant1Price())
                .variant1Mrp(p.getVariant1Mrp())
                .variant2Label(p.getVariant2Label())
                .variant2Price(p.getVariant2Price())
                .variant2Mrp(p.getVariant2Mrp())
                .enabled(p.isEnabled())
                .outOfStock(p.isOutOfStock())
                .displayOrder(p.getDisplayOrder())
                .build();
    }

    /** The photo now lives in the database (see Product.imageData), so the
     * URL just points at the endpoint that streams it back out — with a
     * `?v=` cache-buster tied to the row's updatedAt, so replacing a photo
     * shows up immediately everywhere instead of serving a browser-cached
     * copy of the old one at the same URL. Falls back to the legacy disk
     * path for any product ImageMigrationRunner hasn't copied over yet. */
    private static String imageUrlFor(Product p) {
        if (p.getImageData() != null) {
            return "/api/products/" + p.getId() + "/image?v=" + p.getUpdatedAt().toEpochMilli();
        }
        return p.getImageUrl() == null ? "" : p.getImageUrl();
    }
}
