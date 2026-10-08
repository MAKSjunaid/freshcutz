package com.freshcutz.repository;

import com.freshcutz.entity.ProductSalesStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Everything the admin Items tab needs, all served straight off the small
 * product_sales_stats rollup (see that entity's own comment for why this
 * table exists at all instead of aggregating order_items fresh on every
 * request) — upsertOnDelivered/subtractOnUndelivered keep it current as
 * orders are delivered/reopened, backfillProductStats recomputes it from
 * scratch (self-healing, same reasoning as CustomerRepository
 * .backfillCustomerStats), and findTopProductsPage/findVariantBreakdown
 * read from it. */
public interface ProductSalesStatRepository extends JpaRepository<ProductSalesStat, Long> {

    /** One row per product, summed across every day (and, unlike the
     * per-variant drill-down below, every variant too) within the
     * optional [from, to] date range — the Items tab's main ranked list.
     * from/to are both null together for the all-time default view and
     * both set together once a range is Applied, same optional-filter
     * convention as every other date-range query in this codebase.
     *
     * Two CTEs:
     *  - totals: the actual SUM(quantity)/SUM(revenue) per product_id,
     *    filtered by the date range — the number the list sorts and
     *    pages by.
     *  - latest_name: this product's single most recently recorded name
     *    (DISTINCT ON picks "the row with max(sale_date) per group",
     *    Postgres's standard idiom for this — same technique
     *    CustomerRepository's own latest_order CTE already uses),
     *    looked up across the WHOLE table regardless of the date range
     *    being viewed — a renamed product should show its current name
     *    even when looking at last month's numbers, not whatever name
     *    happened to be in effect back then.
     *
     * Paged with the same keyset ("seek") cursor shape as every other
     * infinite-scroll tab in this app — cursorRevenue/cursorProductId are
     * null together for the first page, both set (this product's own
     * revenue + id) for "load more" past it. Revenue alone isn't unique
     * enough to seek on by itself (two products can tie), hence
     * product_id as the tiebreaker, same reasoning as the Orders tab's
     * (statusUpdatedAt, id) cursor. The cursor comparison below reads as
     * an ordinary WHERE even though it's comparing against SUM(revenue)
     * — that's only possible because totals is a CTE: by the time the
     * outer SELECT runs, t.revenue is already a plain materialized
     * column from totals, not a live aggregate call, so there's no need
     * for HAVING here the way there would be inside a single ungrouped
     * query. limit is requested as pageSize + 1 by the caller — the same
     * "ask for one more than you need" hasMore trick used throughout
     * this app. */
    @Query(value =
            "WITH totals AS (" +
            "    SELECT product_id, " +
            "           SUM(quantity) AS quantity, " +
            "           SUM(revenue) AS revenue " +
            "    FROM product_sales_stats " +
            "    WHERE (CAST(:from AS date) IS NULL OR sale_date >= CAST(:from AS date)) " +
            "      AND (CAST(:to AS date) IS NULL OR sale_date <= CAST(:to AS date)) " +
            "    GROUP BY product_id" +
            "), " +
            "latest_name AS (" +
            "    SELECT DISTINCT ON (product_id) product_id, product_name " +
            "    FROM product_sales_stats " +
            "    ORDER BY product_id, sale_date DESC" +
            ") " +
            "SELECT t.product_id, ln.product_name, t.quantity, t.revenue, " +
            "       0 AS new_quantity, 0 AS new_revenue, 0 AS cancelled_quantity, 0 AS cancelled_revenue " +
            "FROM totals t " +
            "JOIN latest_name ln ON ln.product_id = t.product_id " +
            "WHERE (CAST(:cursorRevenue AS numeric) IS NULL OR " +
            "       t.revenue < CAST(:cursorRevenue AS numeric) OR " +
            "       (t.revenue = CAST(:cursorRevenue AS numeric) AND " +
            "        t.product_id < CAST(:cursorProductId AS bigint))) " +
            "ORDER BY t.revenue DESC, t.product_id DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findTopProductsPage(@Param("from") LocalDate from,
                                        @Param("to") LocalDate to,
                                        @Param("cursorRevenue") BigDecimal cursorRevenue,
                                        @Param("cursorProductId") Long cursorProductId,
                                        @Param("limit") int limit);

    /** One product's sales split by variant (e.g. 500 gm vs 1 kg) within
     * the same optional date range the main list is viewing — backs the
     * Items tab's tap-to-expand drill-down. Not paged: a product
     * realistically has one or two variants (see Product.variant1Label/
     * variant2Label), never enough to need it. */
    @Query(value =
            "SELECT variant_label, SUM(quantity) AS quantity, SUM(revenue) AS revenue " +
            "FROM product_sales_stats " +
            "WHERE product_id = :productId " +
            "  AND (CAST(:from AS date) IS NULL OR sale_date >= CAST(:from AS date)) " +
            "  AND (CAST(:to AS date) IS NULL OR sale_date <= CAST(:to AS date)) " +
            "GROUP BY variant_label " +
            "ORDER BY SUM(revenue) DESC",
            nativeQuery = true)
    List<Object[]> findVariantBreakdown(@Param("productId") Long productId,
                                         @Param("from") LocalDate from,
                                         @Param("to") LocalDate to);

    /** Called once per line item of a just-Delivered order (see
     * ProductStatsService.recordOrderDelivered) — one atomic UPSERT
     * rather than read-then-write, same reasoning as every other
     * incremental-stats upsert in this app (CustomerRepository
     * .upsertOnOrderPlaced). product_name is overwritten with whatever
     * this order snapshotted (latest wins, same convention as that
     * method); quantity/revenue are ADDED to whatever this
     * product+variant+day already had, never replaced, since more than
     * one order can deliver the same product on the same day. */
    @Modifying
    @Query(value =
            "INSERT INTO product_sales_stats (product_id, product_name, variant_label, sale_date, quantity, revenue) " +
            "VALUES (:productId, :productName, :variantLabel, CAST(:saleDate AS date), :quantity, :revenue) " +
            "ON CONFLICT (product_id, variant_label, sale_date) DO UPDATE SET " +
            "    product_name = EXCLUDED.product_name, " +
            "    quantity = product_sales_stats.quantity + EXCLUDED.quantity, " +
            "    revenue = product_sales_stats.revenue + EXCLUDED.revenue",
            nativeQuery = true)
    void upsertOnDelivered(@Param("productId") Long productId,
                            @Param("productName") String productName,
                            @Param("variantLabel") String variantLabel,
                            @Param("saleDate") LocalDate saleDate,
                            @Param("quantity") int quantity,
                            @Param("revenue") BigDecimal revenue);

    /** The inverse of upsertOnDelivered — called once per line item when
     * a Delivered order is reopened back to New (see ProductStatsService
     * .recordOrderUndelivered), against the SAME day it was originally
     * added to (the order's deliveredAt at the time it was delivered,
     * captured by OrderService.reopen before that field is cleared) —
     * not "today". GREATEST(..., 0) is purely defensive, the same
     * guard CustomerRepository.subtractFromTotalSpent uses, against this
     * ever firing more times than the matching add. */
    @Modifying
    @Query(value =
            "UPDATE product_sales_stats " +
            "SET quantity = GREATEST(quantity - :quantity, 0), " +
            "    revenue = GREATEST(revenue - :revenue, 0) " +
            "WHERE product_id = :productId AND variant_label = :variantLabel AND sale_date = CAST(:saleDate AS date)",
            nativeQuery = true)
    void subtractOnUndelivered(@Param("productId") Long productId,
                                @Param("variantLabel") String variantLabel,
                                @Param("saleDate") LocalDate saleDate,
                                @Param("quantity") int quantity,
                                @Param("revenue") BigDecimal revenue);

    /** One-time-per-startup (see ProductStatsBackfillRunner) full
     * recompute of this whole table from the actual order/order_item
     * history — covers every order delivered before this table existed,
     * and self-heals this table back to the true numbers if the
     * incremental upsert/subtract above ever drifts (a missed call, a
     * bug, a manual database edit). Groups by the shop-local
     * (Asia/Kolkata) calendar day of delivery — :zone is the IANA zone
     * id, same "crosses into raw SQL as a string, not a Java enum"
     * reasoning as OrderRepository.findDailyOverview, which groups
     * by the exact same kind of shop-local day for the Sales tab.
     * Postgres allows GROUP BY to reference a SELECT-list alias
     * (sale_date) directly, so the AT TIME ZONE/to_char expression
     * itself is written only once. MAX(oi.product_name) is a pragmatic
     * "pick one" within this fine a grain (one product+variant+single
     * day) — multiple orders delivered the same product on the same day
     * essentially never disagree on its name, unlike the main list's own
     * latest_name CTE, which deliberately looks across the WHOLE table
     * to find the truly most recent name for a product that might have
     * been renamed since. A full REPLACE (not an increment) on conflict,
     * since this recomputes each row's correct total from the source of
     * truth every time, not adding onto whatever was already there. */
    @Modifying
    @Query(value =
            "INSERT INTO product_sales_stats (product_id, product_name, variant_label, sale_date, quantity, revenue) " +
            "SELECT oi.product_id, " +
            "       MAX(oi.product_name), " +
            "       oi.variant_label, " +
            "       (to_char(o.delivered_at AT TIME ZONE :zone, 'YYYY-MM-DD'))::date AS sale_date, " +
            "       SUM(oi.quantity), " +
            "       SUM(oi.line_total) " +
            "FROM order_items oi " +
            "JOIN orders o ON o.id = oi.order_id " +
            "WHERE o.status = 'DELIVERED' AND o.delivered_at IS NOT NULL AND oi.product_id IS NOT NULL " +
            "GROUP BY oi.product_id, oi.variant_label, sale_date " +
            "ON CONFLICT (product_id, variant_label, sale_date) DO UPDATE SET " +
            "    product_name = EXCLUDED.product_name, " +
            "    quantity = EXCLUDED.quantity, " +
            "    revenue = EXCLUDED.revenue",
            nativeQuery = true)
    int backfillProductStats(@Param("zone") String zone);

    /** The Items list for any status filter other than Delivered-only
     * (All, New, Cancelled — see SalesStatusFilter): product totals
     * computed from order_items joined to the matching orders (each
     * status dated by its own moment, OrderMixSql.PREDICATE) instead of
     * the delivered-only product_sales_stats rollup, which by design
     * knows nothing about orders that aren't delivered. Same row shape
     * and (revenue, product_id) keyset as findTopProductsPage. The name
     * shown is the one from the most recently placed matching order. New
     * orders are few by nature, so this is cheap for every bounded
     * period. from/to are always real instants here. */
    @Query(value =
            "WITH totals AS (" +
            "    SELECT oi.product_id, " +
            "           SUM(oi.quantity) AS quantity, " +
            "           SUM(oi.line_total) AS revenue, " +
            "           COALESCE(SUM(oi.quantity) FILTER (WHERE o.status = 'NEW'), 0) AS new_quantity, " +
            "           COALESCE(SUM(oi.line_total) FILTER (WHERE o.status = 'NEW'), 0) AS new_revenue, " +
            "           COALESCE(SUM(oi.quantity) FILTER (WHERE o.status = 'CANCELLED'), 0) AS cancelled_quantity, " +
            "           COALESCE(SUM(oi.line_total) FILTER (WHERE o.status = 'CANCELLED'), 0) AS cancelled_revenue, " +
            "           (array_agg(oi.product_name ORDER BY o.created_at DESC))[1] AS product_name " +
            "    FROM order_items oi " +
            "    JOIN orders o ON o.id = oi.order_id " +
            "    WHERE oi.product_id IS NOT NULL AND " + OrderMixSql.PREDICATE +
            "    GROUP BY oi.product_id" +
            ") " +
            "SELECT t.product_id, t.product_name, t.quantity, t.revenue, " +
            "       t.new_quantity, t.new_revenue, t.cancelled_quantity, t.cancelled_revenue " +
            "FROM totals t " +
            "WHERE (CAST(:cursorRevenue AS numeric) IS NULL OR " +
            "       t.revenue < CAST(:cursorRevenue AS numeric) OR " +
            "       (t.revenue = CAST(:cursorRevenue AS numeric) AND " +
            "        t.product_id < CAST(:cursorProductId AS bigint))) " +
            "ORDER BY t.revenue DESC, t.product_id DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findTopProductsMix(@Param("incNew") boolean incNew,
                                       @Param("incDelivered") boolean incDelivered,
                                       @Param("incCancelled") boolean incCancelled,
                                       @Param("from") java.time.Instant from,
                                       @Param("to") java.time.Instant to,
                                       @Param("cursorRevenue") BigDecimal cursorRevenue,
                                       @Param("cursorProductId") Long cursorProductId,
                                       @Param("limit") int limit);

    /** One product's variant split for the same non-Delivered-only status
     * filters as findTopProductsMix — the live-from-orders counterpart of
     * findVariantBreakdown. */
    @Query(value =
            "SELECT oi.variant_label, SUM(oi.quantity) AS quantity, SUM(oi.line_total) AS revenue " +
            "FROM order_items oi " +
            "JOIN orders o ON o.id = oi.order_id " +
            "WHERE oi.product_id = :productId AND " + OrderMixSql.PREDICATE +
            "GROUP BY oi.variant_label " +
            "ORDER BY SUM(oi.line_total) DESC",
            nativeQuery = true)
    List<Object[]> findVariantBreakdownMix(@Param("productId") Long productId,
                                            @Param("incNew") boolean incNew,
                                            @Param("incDelivered") boolean incDelivered,
                                            @Param("incCancelled") boolean incCancelled,
                                            @Param("from") java.time.Instant from,
                                            @Param("to") java.time.Instant to);

    /** The Items list for the heaviest case — All-time period with the
     * All filter — without reading any delivered order_items: Delivered
     * quantity/revenue come off the product_sales_stats rollup, and only
     * the NEW and CANCELLED items (a small slice of history) are summed
     * live from order_items/orders and added on top, per product. Same
     * row shape and (revenue, product_id) keyset as findTopProductsMix,
     * whose All-time answer this reproduces. Name: the rollup's most
     * recent recorded name when the product has delivered sales,
     * otherwise the one from its latest live order. */
    @Query(value =
            "WITH rolled AS (" +
            "    SELECT product_id, SUM(quantity) AS quantity, SUM(revenue) AS revenue " +
            "    FROM product_sales_stats GROUP BY product_id" +
            "), live AS (" +
            "    SELECT oi.product_id, SUM(oi.quantity) AS quantity, SUM(oi.line_total) AS revenue, " +
            "           COALESCE(SUM(oi.quantity) FILTER (WHERE o.status = 'NEW'), 0) AS new_quantity, " +
            "           COALESCE(SUM(oi.line_total) FILTER (WHERE o.status = 'NEW'), 0) AS new_revenue, " +
            "           COALESCE(SUM(oi.quantity) FILTER (WHERE o.status = 'CANCELLED'), 0) AS cancelled_quantity, " +
            "           COALESCE(SUM(oi.line_total) FILTER (WHERE o.status = 'CANCELLED'), 0) AS cancelled_revenue, " +
            "           (array_agg(oi.product_name ORDER BY o.created_at DESC))[1] AS product_name " +
            "    FROM order_items oi " +
            "    JOIN orders o ON o.id = oi.order_id " +
            "    WHERE oi.product_id IS NOT NULL AND o.status IN ('NEW', 'CANCELLED') " +
            "    GROUP BY oi.product_id" +
            "), latest_name AS (" +
            "    SELECT DISTINCT ON (product_id) product_id, product_name " +
            "    FROM product_sales_stats " +
            "    ORDER BY product_id, sale_date DESC" +
            "), totals AS (" +
            "    SELECT COALESCE(r.product_id, l.product_id) AS product_id, " +
            "           COALESCE(ln.product_name, l.product_name) AS product_name, " +
            "           COALESCE(r.quantity, 0) + COALESCE(l.quantity, 0) AS quantity, " +
            "           COALESCE(r.revenue, 0) + COALESCE(l.revenue, 0) AS revenue, " +
            "           COALESCE(l.new_quantity, 0) AS new_quantity, COALESCE(l.new_revenue, 0) AS new_revenue, " +
            "           COALESCE(l.cancelled_quantity, 0) AS cancelled_quantity, " +
            "           COALESCE(l.cancelled_revenue, 0) AS cancelled_revenue " +
            "    FROM rolled r " +
            "    FULL JOIN live l ON l.product_id = r.product_id " +
            "    LEFT JOIN latest_name ln ON ln.product_id = COALESCE(r.product_id, l.product_id)" +
            ") " +
            "SELECT t.product_id, t.product_name, t.quantity, t.revenue, " +
            "       t.new_quantity, t.new_revenue, t.cancelled_quantity, t.cancelled_revenue " +
            "FROM totals t " +
            "WHERE (CAST(:cursorRevenue AS numeric) IS NULL OR " +
            "       t.revenue < CAST(:cursorRevenue AS numeric) OR " +
            "       (t.revenue = CAST(:cursorRevenue AS numeric) AND " +
            "        t.product_id < CAST(:cursorProductId AS bigint))) " +
            "ORDER BY t.revenue DESC, t.product_id DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findTopProductsAllTimeAll(@Param("cursorRevenue") BigDecimal cursorRevenue,
                                              @Param("cursorProductId") Long cursorProductId,
                                              @Param("limit") int limit);

    /** Every product's variant split in one go, for the same period and
     * status filter as findTopProductsMix — what "Export this period"
     * writes into the By Item sheet's Variants column. Live from
     * order_items/orders (an export is rare, so no rollup shortcut).
     * Rows: (product_id, variant_label, quantity, revenue), grouped by
     * product, biggest revenue first. */
    @Query(value =
            "SELECT oi.product_id, oi.variant_label, SUM(oi.quantity) AS quantity, SUM(oi.line_total) AS revenue " +
            "FROM order_items oi " +
            "JOIN orders o ON o.id = oi.order_id " +
            "WHERE oi.product_id IS NOT NULL AND " + OrderMixSql.PREDICATE +
            "GROUP BY oi.product_id, oi.variant_label " +
            "ORDER BY oi.product_id, SUM(oi.line_total) DESC",
            nativeQuery = true)
    List<Object[]> findAllVariantsMix(@Param("incNew") boolean incNew,
                                       @Param("incDelivered") boolean incDelivered,
                                       @Param("incCancelled") boolean incCancelled,
                                       @Param("from") java.time.Instant from,
                                       @Param("to") java.time.Instant to);

    /** One product's variant split for All-time + All: rollup rows plus
     * the live NEW/CANCELLED lines, summed per variant — the rollup-based
     * counterpart of findVariantBreakdownMix. */
    @Query(value =
            "SELECT u.variant_label, SUM(u.quantity) AS quantity, SUM(u.revenue) AS revenue " +
            "FROM (" +
            "    SELECT variant_label, quantity, revenue " +
            "    FROM product_sales_stats WHERE product_id = :productId " +
            "    UNION ALL " +
            "    SELECT oi.variant_label, oi.quantity, oi.line_total " +
            "    FROM order_items oi " +
            "    JOIN orders o ON o.id = oi.order_id " +
            "    WHERE oi.product_id = :productId AND o.status IN ('NEW', 'CANCELLED')" +
            ") u " +
            "GROUP BY u.variant_label " +
            "ORDER BY SUM(u.revenue) DESC",
            nativeQuery = true)
    List<Object[]> findVariantBreakdownAllTimeAll(@Param("productId") Long productId);
}
