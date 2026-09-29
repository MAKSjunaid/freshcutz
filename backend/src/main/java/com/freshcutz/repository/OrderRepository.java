package com.freshcutz.repository;

import com.freshcutz.dto.DailySalesRow;
import com.freshcutz.dto.DailyTotalRow;
import com.freshcutz.dto.SumCountRow;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /* @EntityGraph loads the lazy `items` collection in the same queryy
     * instead of leaving it as a lazy proxy. Without it, code that reads
     * an order's items after the fetching transaction has closed (as the
     * xlsx export does) hits "could not initialize proxy - no Session". */
    @EntityGraph(attributePaths = "items")
    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    @EntityGraph(attributePaths = "items")
    List<Order> findAllByOrderByCreatedAtDesc();

    /** Overrides JpaRepository's plain findById to eager-fetch items in
     * the same query — used by the Sales tab's on-tap order-detail
     * endpoint (OrderService.getById), so opening one order's full detail
     * costs exactly one query, not a lazy-load proxy fetch later. */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findById(Long id);

    Optional<Order> findByOrderCode(String orderCode);

    /** Used by the public "recognize returning customer" lookup at
     * checkout — just their single most recent order, not their whole
     * history (that's what CustomerService.listCustomers() is for, and
     * it's admin-only). Deliberately a targeted query rather than loading
     * every order for this phone number just to read one row. */
    Optional<Order> findFirstByPhoneOrderByCreatedAtDesc(String phone);

    /** Full order history for the customer-facing "My Orders" screen (see
     * CustomerService.ordersByPhone) — every order for this phone, unlike
     * findFirstByPhoneOrderByCreatedAtDesc above which only wants the
     * latest one for address prefill. */
    @EntityGraph(attributePaths = "items")
    List<Order> findByPhoneOrderByCreatedAtDesc(String phone);

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

    /** One row per shop-local CALENDAR DAY within [from, to) — just the
     * day, its total, and its order count — backing the Sales tab's Apply
     * click. This is a native query (not JPQL) because grouping by a
     * timezone-local day needs Postgres's own AT TIME ZONE + to_char, which
     * JPQL has no equivalent for; :zone is the IANA zone id (e.g.
     * "Asia/Kolkata"), not a Java enum, since it crosses straight into raw
     * SQL. Doing the grouping here instead of in Java means a date range
     * spanning thousands of orders costs one small grouped query — as many
     * rows as there are DAYS in the range, not orders — instead of pulling
     * every individual order's row over the network just to fold them into
     * day buckets in the JVM. */
    @Query(value =
            "SELECT to_char(o.delivered_at AT TIME ZONE :zone, 'YYYY-MM-DD') AS day, " +
            "COALESCE(SUM(o.total_amount), 0) AS total, " +
            "COUNT(*) AS orders " +
            "FROM orders o " +
            "WHERE o.status = :status AND o.delivered_at >= :from AND o.delivered_at < :to " +
            "GROUP BY day " +
            "ORDER BY day DESC",
            nativeQuery = true)
    List<DailyTotalRow> findDailyTotalsBetween(@Param("status") String status,
                                                @Param("from") Instant from, @Param("to") Instant to,
                                                @Param("zone") String zone);
}
