package com.freshcutz.repository;

import com.freshcutz.dto.DailySalesRow;
import com.freshcutz.dto.SumCountRow;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /* @EntityGraph loads the lazy `items` collection in the same query
     * instead of leaving it as a lazy proxy. Without it, code that reads
     * an order's items after the fetching transaction has closed (as the
     * xlsx export does) hits "could not initialize proxy - no Session". */
    @EntityGraph(attributePaths = "items")
    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    @EntityGraph(attributePaths = "items")
    List<Order> findAllByOrderByCreatedAtDesc();

    /** Everything the admin Orders tab's All/Delivered/Cancelled infinite
     * scroll needs — a page of orders AND their items — in exactly ONE
     * database round trip, instead of the two-query approach this used
     * before (a page query, then a separate findItemsByOrderIds batch
     * query). That used to cost 2x the network round-trip time to Neon
     * on every single request; this collapses it to 1x.
     *
     * How: the "page" CTE does the real LIMIT-based paging first — same
     * idx_orders_status_created_at/idx_orders_created_at index-backed
     * seek as before — and only THEN joins order_items and aggregates
     * each order's items into a JSON array with jsonb_agg, so Postgres
     * never has to duplicate an order's row once per item before paging
     * (the classic fetch-join-plus-limit trap this whole feature has
     * avoided from the start — see the git history / earlier comments on
     * this file for why @EntityGraph + Pageable doesn't work here).
     *
     * status/search/cursorCreatedAt/cursorId are all genuinely optional —
     * status null means the All tab, search null means no search term,
     * and cursorCreatedAt+cursorId null means the first page (both set
     * together means "load more" past it, using the same keyset/seek
     * approach as before: strictly older than that order). Each is
     * compared as "(CAST(:param AS type) IS NULL OR ...)" with an
     * EXPLICIT cast right on the parameter, rather than leaving Postgres
     * to guess its type from a null value the way the old
     * findByStatusAndSearch query used to — that guessing is exactly
     * what caused the earlier "function lower(bytea) does not exist"
     * bug. Standard CAST(... AS ...) syntax is used here rather than
     * Postgres's shorthand ":param::type" — that shorthand's "::"
     * sitting right next to the ":param" placeholder made Hibernate's
     * native-query parser read the whole thing as one parameter literally
     * named "param::type" instead of recognizing ":param" on its own,
     * which surfaced as "No parameter named ':status'" at runtime.
     * CAST(...) has no colon adjacent to the placeholder, so there's no
     * ambiguity, while still giving Postgres the same explicit type it
     * needs to avoid the null-inference bug above.
     *
     * Returns raw Object[] rows, not a projection interface or Order
     * entities: this is native SQL working with aggregated/joined data
     * that doesn't correspond to a single managed entity, so there's
     * nothing for Hibernate to manage here — OrderService.listPaged maps
     * each row to an OrderResponse by hand (see its mapRow/parseItems
     * helpers), reading items_json with Jackson.
     *
     * limit is requested as pageSize + 1 by the caller (not pageSize) —
     * the extra row, if it comes back, is how OrderService tells whether
     * there's a next page (hasMore), the same "ask for one more than you
     * need" trick Slice<T> did automatically; a raw native query like
     * this has to do that trick by hand instead of getting it for free. */
    @Query(value =
            "WITH page AS (" +
            "    SELECT * FROM orders o " +
            "    WHERE o.status = CAST(:status AS varchar) " +
            "      AND (CAST(:search AS text) IS NULL OR " +
            "           LOWER(o.order_code) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "           LOWER(o.phone) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "           LOWER(o.customer_name) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))) " +
            "      AND (CAST(:cursorStatusUpdatedAt AS timestamptz) IS NULL OR " +
            "           o.status_updated_at < CAST(:cursorStatusUpdatedAt AS timestamptz) OR " +
            "           (o.status_updated_at = CAST(:cursorStatusUpdatedAt AS timestamptz) AND o.id < CAST(:cursorId AS bigint))) " +
            "    ORDER BY o.status_updated_at DESC, o.id DESC " +
            "    LIMIT :limit" +
            ") " +
            "SELECT " +
            "    p.id, p.order_code, p.customer_name, p.phone, p.flat_no, p.address_line, " +
            "    p.landmark, p.notes, p.status, p.total_amount, p.created_at, p.delivered_at, " +
            "    p.status_updated_at, " +
            "    COALESCE(" +
            "        jsonb_agg(" +
            "            jsonb_build_object(" +
            "                'productId', oi.product_id, " +
            "                'productName', oi.product_name, " +
            "                'productImageUrl', oi.product_image_url, " +
            "                'variantLabel', oi.variant_label, " +
            "                'variant', oi.variant, " +
            "                'unitPrice', oi.unit_price, " +
            "                'quantity', oi.quantity, " +
            "                'lineTotal', oi.line_total" +
            "            ) ORDER BY oi.id" +
            "        ) FILTER (WHERE oi.id IS NOT NULL), " +
            "        '[]'::jsonb" +
            "    )::text AS items_json " +
            "FROM page p " +
            "LEFT JOIN order_items oi ON oi.order_id = p.id " +
            "GROUP BY p.id, p.order_code, p.customer_name, p.phone, p.flat_no, p.address_line, " +
            "    p.landmark, p.notes, p.status, p.total_amount, p.created_at, p.delivered_at, " +
            "    p.status_updated_at " +
            "ORDER BY p.status_updated_at DESC, p.id DESC",
            nativeQuery = true)
    List<Object[]> findOrdersWithItemsOneTrip(@Param("status") String status,
                                               @Param("search") String search,
                                               @Param("cursorStatusUpdatedAt") Instant cursorStatusUpdatedAt,
                                               @Param("cursorId") Long cursorId,
                                               @Param("limit") int limit);

    /** Same as findOrdersWithItemsOneTrip, for the "All" tab — but with no
     * status column anywhere in the query, rather than a status parameter
     * that's simply always null. That distinction matters: the JDBC
     * driver reuses a server-side prepared plan after a handful of
     * executions, and once it does, Postgres plans for "status might or
     * might not be given" generically rather than per-call — which, in
     * testing, consistently cost roughly 2x the plain per-status query
     * (measured ~440ms here vs ~225ms for a status-filtered call, same
     * network, same table). Dropping the status condition outright for
     * this one removes that ambiguity, so Postgres only ever has to plan
     * "sort by date, take the top N" — the ideal plan every time. */
    @Query(value =
            "WITH page AS (" +
            "    SELECT * FROM orders o " +
            "    WHERE (CAST(:search AS text) IS NULL OR " +
            "           LOWER(o.order_code) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "           LOWER(o.phone) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "           LOWER(o.customer_name) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))) " +
            "      AND (CAST(:cursorStatusUpdatedAt AS timestamptz) IS NULL OR " +
            "           o.status_updated_at < CAST(:cursorStatusUpdatedAt AS timestamptz) OR " +
            "           (o.status_updated_at = CAST(:cursorStatusUpdatedAt AS timestamptz) AND o.id < CAST(:cursorId AS bigint))) " +
            "    ORDER BY o.status_updated_at DESC, o.id DESC " +
            "    LIMIT :limit" +
            ") " +
            "SELECT " +
            "    p.id, p.order_code, p.customer_name, p.phone, p.flat_no, p.address_line, " +
            "    p.landmark, p.notes, p.status, p.total_amount, p.created_at, p.delivered_at, " +
            "    p.status_updated_at, " +
            "    COALESCE(" +
            "        jsonb_agg(" +
            "            jsonb_build_object(" +
            "                'productId', oi.product_id, " +
            "                'productName', oi.product_name, " +
            "                'productImageUrl', oi.product_image_url, " +
            "                'variantLabel', oi.variant_label, " +
            "                'variant', oi.variant, " +
            "                'unitPrice', oi.unit_price, " +
            "                'quantity', oi.quantity, " +
            "                'lineTotal', oi.line_total" +
            "            ) ORDER BY oi.id" +
            "        ) FILTER (WHERE oi.id IS NOT NULL), " +
            "        '[]'::jsonb" +
            "    )::text AS items_json " +
            "FROM page p " +
            "LEFT JOIN order_items oi ON oi.order_id = p.id " +
            "GROUP BY p.id, p.order_code, p.customer_name, p.phone, p.flat_no, p.address_line, " +
            "    p.landmark, p.notes, p.status, p.total_amount, p.created_at, p.delivered_at, " +
            "    p.status_updated_at " +
            "ORDER BY p.status_updated_at DESC, p.id DESC",
            nativeQuery = true)
    List<Object[]> findAllOrdersWithItemsOneTrip(@Param("search") String search,
                                                  @Param("cursorStatusUpdatedAt") Instant cursorStatusUpdatedAt,
                                                  @Param("cursorId") Long cursorId,
                                                  @Param("limit") int limit);

    /** Overrides JpaRepository's plain findById to eager-fetch items in
     * the same query — used by the Sales tab's on-tap order-detail
     * endpoint (OrderService.getById), so opening one order's full detail
     * costs exactly one query, not a lazy-load proxy fetch later. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findById(Long id);

    Optional<Order> findByOrderCode(String orderCode);

    /** Used by the public "recognize returning customer" lookup at
     * checkout — just their single most recent order, not their whole
     * history (that's what CustomerService.listCustomersPaged() is for, and
     * it's admin-only). Deliberately a targeted query rather than loading
     * every order for this phone number just to read one row. */
    Optional<Order> findFirstByPhoneOrderByCreatedAtDesc(String phone);

    /** Full order history for the customer-facing "My Orders" screen (see
     * CustomerService.ordersByPhone) — every order for this phone, unlike
     * findFirstByPhoneOrderByCreatedAtDesc above which only wants the
     * latest one for address prefill. */
    @EntityGraph(attributePaths = "items")
    List<Order> findByPhoneOrderByCreatedAtDesc(String phone);

    /** One customer's orders that fall in a Sales-tab period — the orders
     * behind a By Customer card's "View past orders". Each order counts
     * in [from, to) by its own status's date, exactly as the By Customer
     * totals do (see OrderMixSql): New by createdAt, Delivered by
     * deliveredAt, Cancelled by statusUpdatedAt. Items fetched in the same
     * query; newest first. from/to are always real instants here (the
     * caller substitutes EPOCH / a far-future instant for a missing bound). */
    @EntityGraph(attributePaths = "items")
    @Query("SELECT o FROM Order o WHERE o.phone = :phone AND (" +
            "(o.status = com.freshcutz.entity.OrderStatus.NEW AND o.createdAt >= :from AND o.createdAt < :to) OR " +
            "(o.status = com.freshcutz.entity.OrderStatus.DELIVERED AND o.deliveredAt >= :from AND o.deliveredAt < :to) OR " +
            "(o.status = com.freshcutz.entity.OrderStatus.CANCELLED AND o.statusUpdatedAt >= :from AND o.statusUpdatedAt < :to)) " +
            "ORDER BY o.createdAt DESC")
    List<Order> findByPhoneInPeriod(@Param("phone") String phone,
                                    @Param("from") Instant from, @Param("to") Instant to);

    /** Every order that falls in a Sales-tab period, each by its own
     * status's date (New: createdAt, Delivered: deliveredAt, Cancelled:
     * statusUpdatedAt — see OrderMixSql), items fetched in the same query,
     * newest first. Backs "Export this period" on the By Day sub-tab; the
     * caller narrows it to the selected status box. */
    @EntityGraph(attributePaths = "items")
    @Query("SELECT o FROM Order o WHERE " +
            "(o.status = com.freshcutz.entity.OrderStatus.NEW AND o.createdAt >= :from AND o.createdAt < :to) OR " +
            "(o.status = com.freshcutz.entity.OrderStatus.DELIVERED AND o.deliveredAt >= :from AND o.deliveredAt < :to) OR " +
            "(o.status = com.freshcutz.entity.OrderStatus.CANCELLED AND o.statusUpdatedAt >= :from AND o.statusUpdatedAt < :to) " +
            "ORDER BY o.createdAt DESC")
    List<Order> findAllInPeriod(@Param("from") Instant from, @Param("to") Instant to);

    List<Order> findByCreatedAtGreaterThanEqualAndStatusNot(Instant from, OrderStatus excludedStatus);

    @EntityGraph(attributePaths = "items")
    List<Order> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant from, Instant to);

    long countByOrderCodeStartingWith(String prefix);

    /** Plain row count for a status — backs the "new orders" badge. A
     * COUNT(*) at the database instead of loading every NEW order just to
     * call .size() on the list. */
    long countByStatus(OrderStatus status);

    /** SUM(total_amount) + COUNT(*), computed by the database, for every
     * order of `status` delivered at or after `from` — backs the Sales
     * tab's Today/Last 7 days/Last 30 days/All time buckets. Each bucket
     * is now one small aggregate query instead of the app loading every
     * delivered order ever placed into memory and adding them up in a
     * loop — this stays cheap no matter how much order history piles up. */
    @Query("SELECT NEW com.freshcutz.dto.SumCountRow(COALESCE(SUM(o.totalAmount), 0), COUNT(o)) " +
            "FROM Order o WHERE o.status = :status AND o.deliveredAt >= :from")
    SumCountRow sumAndCountDeliveredSince(@Param("status") OrderStatus status, @Param("from") Instant from);

    /** Lightweight per-order rows (id, code, customer, delivered-at, total
     * — no address, no items) for every order of `status` delivered in
     * [from, to), newest first. Used two ways: SalesService.getDayOrders()
     * calls it with a single day's [start, end) bounds, backing the Sales
     * tab's per-day expand-on-tap; the xlsx export path (via
     * ordersInRange) doesn't use this — it needs full entities, not this
     * projection. Filtering and sorting both happen in SQL. */
    @Query("SELECT NEW com.freshcutz.dto.DailySalesRow(o.id, o.orderCode, o.customerName, o.deliveredAt, o.totalAmount) " +
            "FROM Order o WHERE o.status = :status AND o.deliveredAt >= :from AND o.deliveredAt < :to " +
            "ORDER BY o.deliveredAt DESC")
    List<DailySalesRow> findDeliveredSummaryBetween(@Param("status") OrderStatus status,
                                                      @Param("from") Instant from, @Param("to") Instant to);

    /** The Sales tab's whole overview in ONE query: the header numbers for
     * the period AND one page of the By Day list.
     *
     * CTE d groups every order in [from, to) — each by its own status's
     * date (see OrderMixSql) — into one row per shop-local calendar day,
     * with per-status counts and amounts. CTE t sums d into the header
     * (counts per status, delivered money). CTE p is the page of days to
     * show, newest first, strictly older than :cursorDay (pass
     * "9999-12-31" for the first page), at most :lim rows. There is no
     * status filter here on purpose: every day carries all three
     * statuses' numbers and the browser picks the ones the selected
     * All/New/Delivered/Cancelled box needs, so switching the box costs no
     * request. The final SELECT is t LEFT JOIN p ON TRUE so the header comes
     * back even when the page is empty (lim 0 = header only, which the
     * other sub-tabs use) — the header columns simply repeat on every
     * row, and the Java side reads them from the first.
     *
     * Columns: 0 new_total, 1 delivered_total, 2 cancelled_total,
     * 3 delivered_amount, 4 day, 5 n_cnt, 6 d_cnt, 7 c_cnt, 8 n_amt,
     * 9 d_amt, 10 c_amt (day..c_amt are NULL when there's no page row).
     * Replaces three separate queries (status counts, delivered
     * sum+count, daily grouping) that each scanned the same orders. */
    @Query(value =
            "WITH d AS ( " +
            "  SELECT to_char((" + OrderMixSql.EFFECTIVE_AT + ") AT TIME ZONE :zone, 'YYYY-MM-DD') AS day, " +
            "         COUNT(*) FILTER (WHERE o.status = 'NEW') AS n_cnt, " +
            "         COUNT(*) FILTER (WHERE o.status = 'DELIVERED') AS d_cnt, " +
            "         COUNT(*) FILTER (WHERE o.status = 'CANCELLED') AS c_cnt, " +
            "         COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'NEW'), 0) AS n_amt, " +
            "         COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'DELIVERED'), 0) AS d_amt, " +
            "         COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'CANCELLED'), 0) AS c_amt " +
            "  FROM orders o " +
            "  WHERE " + OrderMixSql.PREDICATE_ALL +
            "  GROUP BY day " +
            "), t AS ( " +
            "  SELECT COALESCE(SUM(n_cnt), 0) AS new_total, " +
            "         COALESCE(SUM(d_cnt), 0) AS delivered_total, " +
            "         COALESCE(SUM(c_cnt), 0) AS cancelled_total, " +
            "         COALESCE(SUM(d_amt), 0) AS delivered_amount " +
            "  FROM d " +
            "), p AS ( " +
            "  SELECT day, n_cnt, d_cnt, c_cnt, n_amt, d_amt, c_amt FROM d " +
            "  WHERE day < :cursorDay " +
            "  ORDER BY day DESC " +
            "  LIMIT :lim " +
            ") " +
            "SELECT t.new_total, t.delivered_total, t.cancelled_total, t.delivered_amount, " +
            "       p.day, p.n_cnt, p.d_cnt, p.c_cnt, p.n_amt, p.d_amt, p.c_amt " +
            "FROM t LEFT JOIN p ON TRUE " +
            "ORDER BY p.day DESC",
            nativeQuery = true)
    List<Object[]> findDailyOverview(@Param("from") Instant from, @Param("to") Instant to,
                                      @Param("zone") String zone,
                                      @Param("cursorDay") String cursorDay,
                                      @Param("lim") int lim);

    /** Just the Sales header (New / Delivered / Cancelled counts and the
     * delivered money) for a bounded [from, to) period — no grouping by
     * day. Used when only the header is needed (another sub-tab is open),
     * so the server doesn't build a per-day breakdown nobody will see.
     * Same numbers as findDailyOverview's header columns: (new_total,
     * delivered_total, cancelled_total, delivered_amount), one row. */
    @Query(value =
            "SELECT COUNT(*) FILTER (WHERE o.status = 'NEW') AS new_total, " +
            "       COUNT(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered_total, " +
            "       COUNT(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled_total, " +
            "       COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'DELIVERED'), 0) AS delivered_amount " +
            "FROM orders o " +
            "WHERE " + OrderMixSql.PREDICATE_ALL,
            nativeQuery = true)
    List<Object[]> findHeaderBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** The same header for the All-time period without touching the bulk
     * of the orders table: Delivered count and money come from the
     * running totals kept on customers (delivered_count / total_spent —
     * see CustomerRepository's rollup and its startup self-healing
     * backfill), and only the NEW and CANCELLED orders (a small slice of
     * history, via idx_orders_status_created_at) are counted live. Same
     * column order as findHeaderBetween. */
    @Query(value =
            "SELECT (SELECT COUNT(*) FROM orders WHERE status = 'NEW') AS new_total, " +
            "       (SELECT COALESCE(SUM(delivered_count), 0) FROM customers) AS delivered_total, " +
            "       (SELECT COUNT(*) FROM orders WHERE status = 'CANCELLED') AS cancelled_total, " +
            "       (SELECT COALESCE(SUM(total_spent), 0) FROM customers) AS delivered_amount",
            nativeQuery = true)
    List<Object[]> findHeaderAllTime();

    /** The individual orders behind one day of findDailyOverview — pass
     * that day's [start, end) bounds — newest first, as lightweight
     * (id, order_code, customer_name, at, total_amount, status) rows. */
    @Query(value =
            "SELECT o.id, o.order_code, o.customer_name, (" + OrderMixSql.EFFECTIVE_AT + ") AS happened_at, " +
            "       o.total_amount, o.status " +
            "FROM orders o " +
            "WHERE " + OrderMixSql.PREDICATE +
            "ORDER BY happened_at DESC, o.id DESC",
            nativeQuery = true)
    List<Object[]> findDayOrdersMix(@Param("incNew") boolean incNew,
                                     @Param("incDelivered") boolean incDelivered,
                                     @Param("incCancelled") boolean incCancelled,
                                     @Param("from") Instant from, @Param("to") Instant to);

    /** One-time backfill for orders that existed before statusUpdatedAt was
     * introduced — they have NULL there (a brand-new column has no value
     * for rows that already existed when it was added), which would sort
     * unpredictably and break the keyset cursor comparisons in
     * findOrdersWithItemsOneTrip/findAllOrdersWithItemsOneTrip above. Run
     * once at startup by OrderStatusBackfillRunner; already-delivered
     * orders get their real deliveredAt (accurate — that's exactly when
     * they were marked delivered), everything else (NEW or CANCELLED, which
     * has no separate "when cancelled" column) falls back to createdAt,
     * matching the sort order they already had before this change. Safe to
     * run on every startup — the WHERE clause only ever touches rows still
     * NULL, and every order from here on gets statusUpdatedAt set directly
     * by Order.onCreate / OrderService, never NULL to begin with. */
    @Modifying
    @Query(value = "UPDATE orders SET status_updated_at = COALESCE(delivered_at, created_at) " +
            "WHERE status_updated_at IS NULL",
            nativeQuery = true)
    int backfillMissingStatusUpdatedAt();
}
