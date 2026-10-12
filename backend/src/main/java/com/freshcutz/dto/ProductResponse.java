package com.freshcutz.dto;

import com.freshcutz.entity.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

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
                .imageUrl(imageUrlFor(p.getId(), p.getImageData() != null, p.getUpdatedAt(), p.getImageUrl()))
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

    /** Used by the catalog LIST endpoints (see ProductRepository.
     * listViewForCustomers()/listViewForAdmin()) — same output as
     * from(Product) above, just built from a row that was never fetched
     * with the photo's raw bytes in the first place. */
    public static ProductResponse from(ProductListRow p) {
        return ProductResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .category(p.getCategory())
                .imageUrl(imageUrlFor(p.getId(), p.isHasImage(), p.getUpdatedAt(), p.getImageUrl()))
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

    /** The photo lives in the database (see Product.imageData), so the URL
     * just points at the endpoint that streams it back out — with a `?v=`
     * cache-buster tied to the row's updatedAt, so replacing a photo shows
     * up immediately everywhere instead of serving a browser-cached copy
     * of the old one at the same URL. Falls back to the legacy disk path
     * for any product ImageMigrationRunner hasn't copied over yet.
     * Takes hasImage as a plain boolean (rather than the bytes) so this
     * works equally whether the caller has a full Product entity or just
     * a ProductListRow that never fetched the bytes at all. */
    private static String imageUrlFor(Long id, boolean hasImage, Instant updatedAt, String legacyImageUrl) {
        if (hasImage) {
            return "/api/products/" + id + "/image?v=" + updatedAt.toEpochMilli();
        }
        return legacyImageUrl == null ? "" : legacyImageUrl;
    }
}