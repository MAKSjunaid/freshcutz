package com.freshcutz.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A single line in an order. Product name/variant/price are snapshotted
 * at order time so historical orders never change if the catalog does.
 */
@Entity
@Table(name = "order_items", indexes = {
        // Every order's items are looked up by this foreign key — the
        // New tab's single EntityGraph fetch-join query joins on it, and
        // All/Delivered/Cancelled's paginated queries batch-load items
        // with "WHERE order_id IN (...)" (see OrderService.listPaged).
        // Postgres doesn't index foreign key columns automatically, so
        // without this, both of those access patterns fall back to a
        // full sequential scan of order_items on every single Orders-tab
        // request, regardless of how few orders are actually on the page.
        @Index(name = "idx_order_items_order_id", columnList = "order_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 120)
    private String productName;

    @Column(name = "product_image_url", length = 300)
    private String productImageUrl;

    @Column(name = "variant_label", nullable = false, length = 40)
    private String variantLabel;

    /** "variant1" or "variant2" — which priced size was picked. Nullable
     * because orders placed before this field existed won't have it; the
     * "Reorder" feature falls back to matching variantLabel text for those. */
    @Column(name = "variant", length = 20)
    private String variant;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;
}
