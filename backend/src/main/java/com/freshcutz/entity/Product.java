package com.freshcutz.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A sellable item, e.g. "Chicken Curry Cut".
 * A product can be priced in up to two variants (e.g. 500g and 1kg), or a
 * single variant (e.g. "30 pcs tray" for eggs). variant2* fields are left
 * null when only one size is sold.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 60)
    private String category;

    /** Legacy field — old deployments stored the photo as a file on disk
     * and kept its path here (e.g. "/uploads/products/xyz.jpg"). That
     * breaks on a free host like Render, whose disk isn't persistent
     * across deploys, so photos now live directly in imageData/
     * imageContentType below instead. This column is kept only so
     * ImageMigrationRunner can find and copy over any old files the very
     * first time this runs against an existing database; once imageData
     * is set, this is never read again (see ProductResponse.from). */
    @Column(name = "image_url", length = 300)
    private String imageUrl;

    /** The actual photo bytes, stored in the database (not on disk) so a
     * product's photo survives redeploys on hosts with no persistent
     * disk, and so re-uploading a photo just UPDATEs this same row's
     * bytes instead of ever creating a separate file/record to pile up.
     * VARBINARY (not @Lob alone) to reliably get a plain `bytea` column
     * on Postgres — @Lob's PostgreSQL behavior can instead map byte[] to
     * a large object (OID), which behaves very differently.
     * length=3MB (well above the 2MB upload cap) matters for H2: an
     * unspecified VARBINARY defaults to just 255 bytes there, nowhere
     * near a real photo. Postgres's bytea ignores this length entirely
     * (it's already unbounded), so it's harmless there. */
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "image_data", length = 3 * 1024 * 1024)
    private byte[] imageData;

    @Column(name = "image_content_type", length = 100)
    private String imageContentType;

    @Column(name = "variant1_label", nullable = false, length = 40)
    private String variant1Label;

    @Column(name = "variant1_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal variant1Price;

    @Column(name = "variant1_mrp", precision = 10, scale = 2)
    private BigDecimal variant1Mrp;

    @Column(name = "variant2_label", length = 40)
    private String variant2Label;

    @Column(name = "variant2_price", precision = 10, scale = 2)
    private BigDecimal variant2Price;

    @Column(name = "variant2_mrp", precision = 10, scale = 2)
    private BigDecimal variant2Mrp;

    @Column(nullable = false)
    private boolean enabled;

    /** Still shown on the storefront (unlike `enabled`, which hides it
     * entirely) but marked unavailable — can't be added to cart, and
     * blocked server-side if it's already in someone's cart at checkout.
     * @ColumnDefault is required here (not just nullable=false) so that
     * Hibernate's schema-update can ALTER the existing, already-populated
     * `products` table to add this NOT NULL column — without a DEFAULT,
     * both H2 and Postgres reject that ALTER on a non-empty table, which
     * is exactly what caused the "Column OUT_OF_STOCK not found" errors:
     * the app started fine but the column was silently never added. */
    @Column(name = "out_of_stock", nullable = false)
    @ColumnDefault("false")
    private boolean outOfStock;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (imageUrl == null) {
            imageUrl = "";
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
