package com.freshcutz.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row per (product, variant, shop-local calendar day of delivery) —
 * e.g. "Chicken Curry Cut, 500 gm, 2026-10-03: 7 sold, ₹1,260 earned".
 * This is what backs the admin Items tab's "which item sold the most /
 * earned the most money" ranking and its per-variant drill-down.
 *
 * Kept as its own running rollup, updated incrementally the moment an
 * order is actually Delivered (see ProductStatsService.recordOrderDelivered,
 * called from OrderService.markDelivered) and adjusted back out if that
 * order is later reopened (recordOrderUndelivered, from OrderService
 * .reopen) — the exact same reasoning, and the exact same mistake this
 * project already made and fixed once on the Customers tab, applies here
 * from day one instead of needing a second round of "optimize it after
 * the fact": computing every product's all-time (or date-ranged) totals
 * fresh from every order_item ever recorded, on every single Items-tab
 * visit, gets slower forever as order history grows, even with the best
 * indexes — a table this small (one row per product/variant/day with an
 * actual sale, not one row per order item) is what keeps the Items tab's
 * own list query a plain indexed SELECT whose cost is the page size (or
 * the size of the applied date range), never the size of all order
 * history. A one-time-per-startup recompute (see
 * ProductStatsBackfillRunner) keeps this correct for orders delivered
 * before this table existed, and self-heals if the incremental
 * bookkeeping above ever drifts.
 *
 * product_name is a snapshot (the latest one seen for this product_id),
 * not a live join to the products table — deliberately, since a product
 * can be renamed OR DELETED from the catalog entirely (see
 * ProductService.delete, which has no safety check against existing
 * order history) and this table needs to keep showing a sensible name
 * for historical sales either way, exactly the same "orders are
 * historical snapshots" reasoning OrderItem itself already uses for the
 * very same fields. sale_date is a plain calendar DATE (not an Instant) —
 * it's always the shop-local (Asia/Kolkata) day the order was delivered,
 * matching the Sales tab's own day-bucket convention (see SalesService),
 * computed once and stored, rather than re-derived from a timestamp with
 * a timezone conversion on every single read. */
@Entity
@Table(name = "product_sales_stats", indexes = {
        // Backs the Items tab's own list query (ProductSalesStatRepository
        // .findTopProductsPage), which GROUPs BY product_id over this
        // whole table (optionally date-filtered) — without this, that
        // falls back to a full sequential scan every time, the same
        // problem every other un-indexed aggregation in this project has
        // already run into.
        @Index(name = "idx_product_sales_stats_product_id", columnList = "product_id"),
        // Backs date-range filtering (Apply on the Items tab) and the
        // backfill/self-heal recompute's own GROUP BY.
        @Index(name = "idx_product_sales_stats_sale_date", columnList = "sale_date")
}, uniqueConstraints = {
        // The ON CONFLICT target for every upsert this table ever
        // receives (recordOrderDelivered's per-item upsert, and the
        // backfill's full recompute) — one row per product+variant+day,
        // never more than one, so "add this order's quantity/revenue
        // onto today's row for this product" is always an unambiguous
        // single UPDATE rather than a guess at which of several
        // matching rows to touch.
        @UniqueConstraint(name = "uq_product_sales_stats_key", columnNames = {"product_id", "variant_label", "sale_date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductSalesStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 120)
    private String productName;

    @Column(name = "variant_label", nullable = false, length = 40)
    private String variantLabel;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal revenue;
}
