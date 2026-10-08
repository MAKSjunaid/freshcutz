package com.freshcutz.repository;

import com.freshcutz.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Phone is the id, so findById(phone)/save(...) already give the
 * lookup-by-phone and address-only upsert behavior CustomerService needs
 * for the customer-facing profile endpoints — see lookupByPhone/
 * updateAddress there.
 *
 * Everything else here keeps order_count/total_spent/last_order_at on the
 * `customers` row itself up to date incrementally, so the admin Customers
 * tab's list/search query (findCustomerSummariesPage) never has to touch
 * the `orders` table at all — it used to (an earlier version of this
 * query aggregated fresh over every order, every single page load and
 * search keystroke, via two CTEs: a DISTINCT ON and a GROUP BY over the
 * whole orders table). That got slower every day as order history grew,
 * the same problem the Orders tab had before its own pagination work, and
 * fixing it the same way (paging) only raised the ceiling — every page,
 * including the first, still recomputed the totals from scratch. Moving
 * those three numbers onto `customers` itself and updating them at the
 * handful of moments they actually change (order placed, delivered,
 * reopened — see upsertOnOrderPlaced/addToTotalSpent/
 * subtractFromTotalSpent and CustomerService's own methods that call
 * them) turns the list query into a plain indexed SELECT whose cost is
 * the page size, not the size of order history. backfillCustomerStats is
 * the one-time (well, every-startup, see its own comment)
 * catch-up/self-heal for rows that predate these columns. */
public interface CustomerRepository extends JpaRepository<Customer, String> {

    /** One row per phone number that has ever placed an order — the admin
     * Customers tab's whole list, straight off the customers table, which
     * upsertOnOrderPlaced/addToTotalSpent/subtractFromTotalSpent/
     * backfillCustomerStats keep current. No join to orders, no
     * aggregation at read time — just a filter, a sort, and a limit,
     * all of which idx_customers_order_count_last_order_at (and a plain
     * phone/name scan for search, fine at this shop's customer-list size)
     * can serve directly.
     *
     * Sorted by order_count DESC by default — repeat customers first —
     * with last_order_at then phone as tiebreakers (most recently active
     * first among customers with the same order count, phone purely to
     * guarantee a strict order when even that ties). NULLS LAST on both
     * DESC keys so a row that's somehow never been backfilled (shouldn't
     * happen past the first startup, see CustomerStatsBackfillRunner)
     * sinks to the bottom instead of jumping to the top.
     *
     * search/cursorOrderCount/cursorLastOrderAt/cursorPhone are all
     * genuinely optional — null search means no filter, and the three
     * cursor params null together mean the first page (all three set
     * together means "load more" past it) — keyset/seek pagination over
     * all three sort keys at once, the same reasoning as the Orders tab's
     * own two-key (createdAt, id) cursor, just one key longer since the
     * primary sort key (order_count) alone isn't unique enough to seek
     * on by itself. limit is requested as pageSize + 1 by the caller, the
     * same "ask for one more than you need" hasMore trick OrderRepository's
     * own paged queries use. */
    @Query(value =
            "SELECT phone, name, flat_no, address_line, landmark, " +
            "       COALESCE(order_count, 0) AS order_count, " +
            "       COALESCE(total_spent, 0) AS total_spent, " +
            "       last_order_at " +
            "FROM customers " +
            "WHERE (CAST(:search AS text) IS NULL OR " +
            "       LOWER(phone) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "       LOWER(name) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))) " +
            "  AND (CAST(:cursorOrderCount AS bigint) IS NULL OR " +
            "       COALESCE(order_count, 0) < CAST(:cursorOrderCount AS bigint) OR " +
            "       (COALESCE(order_count, 0) = CAST(:cursorOrderCount AS bigint) AND " +
            "        last_order_at < CAST(:cursorLastOrderAt AS timestamptz)) OR " +
            "       (COALESCE(order_count, 0) = CAST(:cursorOrderCount AS bigint) AND " +
            "        last_order_at = CAST(:cursorLastOrderAt AS timestamptz) AND " +
            "        phone < CAST(:cursorPhone AS varchar))) " +
            "ORDER BY order_count DESC NULLS LAST, last_order_at DESC NULLS LAST, phone DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findCustomerSummariesPage(@Param("search") String search,
                                              @Param("cursorOrderCount") Long cursorOrderCount,
                                              @Param("cursorLastOrderAt") Instant cursorLastOrderAt,
                                              @Param("cursorPhone") String cursorPhone,
                                              @Param("limit") int limit);

    /** Called once per order placement (see CustomerService.upsertFromOrder),
     * replacing a plain JPA save() so this is one atomic UPSERT instead of
     * a read-then-write — two orders landing for the same phone at nearly
     * the same instant can't race and clobber each other's order_count
     * increment this way. Mirrors the exact address-sync behavior that
     * already existed here (always overwrite name/flat_no/address_line/
     * landmark with this latest order's — see upsertFromOrder's own
     * comment): only order_count/last_order_at are new. total_spent is
     * deliberately left out of the UPDATE SET list entirely — a freshly
     * placed order hasn't been delivered yet, so it defaults to 0 only
     * for a brand-new row and otherwise keeps whatever it already was. */
    @Modifying
    @Query(value =
            "INSERT INTO customers " +
            "    (phone, name, flat_no, address_line, landmark, order_count, total_spent, delivered_count, last_order_at, updated_at) " +
            "VALUES (:phone, :name, :flatNo, :addressLine, :landmark, 1, 0, 0, :lastOrderAt, now()) " +
            "ON CONFLICT (phone) DO UPDATE SET " +
            "    name = EXCLUDED.name, " +
            "    flat_no = EXCLUDED.flat_no, " +
            "    address_line = EXCLUDED.address_line, " +
            "    landmark = EXCLUDED.landmark, " +
            "    order_count = COALESCE(customers.order_count, 0) + 1, " +
            "    last_order_at = EXCLUDED.last_order_at, " +
            "    updated_at = now()",
            nativeQuery = true)
    void upsertOnOrderPlaced(@Param("phone") String phone,
                             @Param("name") String name,
                             @Param("flatNo") String flatNo,
                             @Param("addressLine") String addressLine,
                             @Param("landmark") String landmark,
                             @Param("lastOrderAt") Instant lastOrderAt);

    /** Adds a delivered order's amount onto this phone's running total —
     * called from OrderService.markDelivered via CustomerService. A
     * single atomic UPDATE rather than read-modify-write, same reasoning
     * as upsertOnOrderPlaced. */
    @Modifying
    @Query(value =
            "UPDATE customers SET total_spent = COALESCE(total_spent, 0) + :amount, " +
            "    delivered_count = COALESCE(delivered_count, 0) + 1, updated_at = now() " +
            "WHERE phone = :phone",
            nativeQuery = true)
    void addToTotalSpent(@Param("phone") String phone, @Param("amount") BigDecimal amount);

    /** The inverse of addToTotalSpent — called when a Delivered order is
     * reopened back to New (OrderService.reopen), since that order no
     * longer counts as delivered. GREATEST(..., 0) is purely defensive,
     * guarding against this ever being called more times than the
     * matching add (which shouldn't happen — reopen only subtracts once
     * per delivery, and re-delivering adds it straight back). */
    @Modifying
    @Query(value =
            "UPDATE customers SET total_spent = GREATEST(COALESCE(total_spent, 0) - :amount, 0), " +
            "    delivered_count = GREATEST(COALESCE(delivered_count, 0) - 1, 0), updated_at = now() " +
            "WHERE phone = :phone",
            nativeQuery = true)
    void subtractFromTotalSpent(@Param("phone") String phone, @Param("amount") BigDecimal amount);

    /** One-time (well, every-startup — see CustomerStatsBackfillRunner's
     * own comment) catch-up for order_count/total_spent/last_order_at:
     * recomputes all three fresh from the orders table and writes them
     * onto `customers`, inserting a row for any phone that has orders but
     * no profile row yet (a customer who ordered before the `customers`
     * table existed and has never since placed another order or edited
     * their address). Deliberately does NOT touch name/flat_no/
     * address_line/landmark on conflict — those are already correctly
     * kept current by upsertOnOrderPlaced (latest order wins) and
     * CustomerService.updateAddress (a customer's own profile edit);
     * overwriting them here from the latest order's data on every startup
     * would silently undo a profile edit a customer made since their last
     * order. Uses the exact same three-CTE shape
     * findCustomerSummariesPage used to compute these numbers at read
     * time, before this change — see this file's own class comment for
     * why that's no longer done on every page load. */
    @Modifying
    @Query(value =
            "WITH latest_order AS (" +
            "    SELECT DISTINCT ON (o.phone) " +
            "        o.phone, o.customer_name, o.flat_no, o.address_line, o.landmark " +
            "    FROM orders o " +
            "    ORDER BY o.phone, o.created_at DESC" +
            "), " +
            "agg AS (" +
            "    SELECT o.phone, " +
            "           COUNT(*) AS order_count, " +
            "           COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'DELIVERED'), 0) AS total_spent, " +
            "           COUNT(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered_count, " +
            "           MAX(o.created_at) AS last_order_at " +
            "    FROM orders o " +
            "    GROUP BY o.phone" +
            ") " +
            "INSERT INTO customers " +
            "    (phone, name, flat_no, address_line, landmark, order_count, total_spent, delivered_count, last_order_at, updated_at) " +
            "SELECT lo.phone, lo.customer_name, lo.flat_no, lo.address_line, lo.landmark, " +
            "       agg.order_count, agg.total_spent, agg.delivered_count, agg.last_order_at, now() " +
            "FROM latest_order lo " +
            "JOIN agg ON agg.phone = lo.phone " +
            "ON CONFLICT (phone) DO UPDATE SET " +
            "    order_count = EXCLUDED.order_count, " +
            "    total_spent = EXCLUDED.total_spent, " +
            "    delivered_count = EXCLUDED.delivered_count, " +
            "    last_order_at = EXCLUDED.last_order_at",
            nativeQuery = true)
    int backfillCustomerStats();

    /** Sales tab "By Customer", All-time period: customers ranked by
     * total_spent (delivered orders only) straight off the running totals
     * on this table — no join to orders, no aggregation at read time,
     * same reasoning as findCustomerSummariesPage. Only customers with at
     * least one delivered order appear (delivered_count > 0): this is a
     * sales ranking, not a directory. Search, cursor and limit work
     * exactly as in findCustomerSummariesPage, with (total_spent, phone)
     * as the two keyset keys instead of three. Rows come back as
     * (phone, name, flat_no, address_line, landmark, delivered_orders,
     * spent) — the same shape as findTopCustomersBetween below, so
     * CustomerService maps both identically. */
    @Query(value =
            "SELECT phone, name, flat_no, address_line, landmark, " +
            "       COALESCE(delivered_count, 0) AS delivered_orders, " +
            "       total_spent AS spent, " +
            "       0 AS new_orders, 0 AS cancelled_orders, 0 AS new_spent, 0 AS cancelled_spent " +
            "FROM customers " +
            "WHERE COALESCE(delivered_count, 0) > 0 " +
            "  AND (CAST(:search AS text) IS NULL OR " +
            "       LOWER(phone) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "       LOWER(name) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))) " +
            "  AND (CAST(:cursorSpent AS numeric) IS NULL OR " +
            "       total_spent < CAST(:cursorSpent AS numeric) OR " +
            "       (total_spent = CAST(:cursorSpent AS numeric) AND " +
            "        phone < CAST(:cursorPhone AS varchar))) " +
            "ORDER BY total_spent DESC, phone DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findTopCustomersAllTime(@Param("search") String search,
                                            @Param("cursorSpent") BigDecimal cursorSpent,
                                            @Param("cursorPhone") String cursorPhone,
                                            @Param("limit") int limit);

    /** Sales tab "By Customer", any bounded period: aggregates only the
     * delivered orders whose delivered_at falls in [from, to) by phone
     * (an index range scan on idx_orders_status_delivered_at — cost
     * scales with that window, not with total order history), then joins
     * the small result to customers for name/address. Same return shape
     * and keyset (spent, phone) as findTopCustomersAllTime. Search is
     * applied after the aggregation, on the already-small per-phone
     * result. from/to are always real instants here — CustomerService
     * substitutes EPOCH / a far-future instant for a missing bound. */
    @Query(value =
            "WITH agg AS (" +
            "    SELECT o.phone, COUNT(*) AS delivered_orders, SUM(o.total_amount) AS spent " +
            "    FROM orders o " +
            "    WHERE o.status = 'DELIVERED' " +
            "      AND o.delivered_at >= CAST(:from AS timestamptz) " +
            "      AND o.delivered_at < CAST(:to AS timestamptz) " +
            "    GROUP BY o.phone" +
            ") " +
            "SELECT c.phone, c.name, c.flat_no, c.address_line, c.landmark, a.delivered_orders, a.spent, " +
            "       0 AS new_orders, 0 AS cancelled_orders, 0 AS new_spent, 0 AS cancelled_spent " +
            "FROM agg a " +
            "JOIN customers c ON c.phone = a.phone " +
            "WHERE (CAST(:search AS text) IS NULL OR " +
            "       LOWER(c.phone) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "       LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))) " +
            "  AND (CAST(:cursorSpent AS numeric) IS NULL OR " +
            "       a.spent < CAST(:cursorSpent AS numeric) OR " +
            "       (a.spent = CAST(:cursorSpent AS numeric) AND " +
            "        c.phone < CAST(:cursorPhone AS varchar))) " +
            "ORDER BY a.spent DESC, c.phone DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findTopCustomersBetween(@Param("from") Instant from,
                                            @Param("to") Instant to,
                                            @Param("search") String search,
                                            @Param("cursorSpent") BigDecimal cursorSpent,
                                            @Param("cursorPhone") String cursorPhone,
                                            @Param("limit") int limit);

    /** Sales tab "By Customer" for any status filter other than
     * Delivered-only (All, New, Cancelled — see SalesStatusFilter):
     * aggregates the matching orders by phone straight from the orders
     * table, each status dated by its own moment (OrderMixSql.PREDICATE),
     * then joins the small result to customers for name/address. Ranked
     * by the total of those orders, with the same (spent, phone) keyset
     * and the same row shape as the two Delivered-only queries above plus
     * the last two columns filled in for real: how many of the orders are
     * New and how many Cancelled. New orders are few by nature (they
     * leave New as soon as they're actioned), so this stays cheap for
     * every bounded period; only All-time with Cancelled included reads a
     * lot of rows. from/to are always real instants here. */
    @Query(value =
            "WITH agg AS (" +
            "    SELECT o.phone, COUNT(*) AS orders, SUM(o.total_amount) AS spent, " +
            "           COUNT(*) FILTER (WHERE o.status = 'NEW') AS new_orders, " +
            "           COUNT(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled_orders, " +
            "           COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'NEW'), 0) AS new_spent, " +
            "           COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'CANCELLED'), 0) AS cancelled_spent " +
            "    FROM orders o " +
            "    WHERE " + OrderMixSql.PREDICATE +
            "    GROUP BY o.phone" +
            ") " +
            "SELECT c.phone, c.name, c.flat_no, c.address_line, c.landmark, " +
            "       a.orders, a.spent, a.new_orders, a.cancelled_orders, a.new_spent, a.cancelled_spent " +
            "FROM agg a " +
            "JOIN customers c ON c.phone = a.phone " +
            "WHERE (CAST(:search AS text) IS NULL OR " +
            "       LOWER(c.phone) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "       LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%'))) " +
            "  AND (CAST(:cursorSpent AS numeric) IS NULL OR " +
            "       a.spent < CAST(:cursorSpent AS numeric) OR " +
            "       (a.spent = CAST(:cursorSpent AS numeric) AND " +
            "        c.phone < CAST(:cursorPhone AS varchar))) " +
            "ORDER BY a.spent DESC, c.phone DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findTopCustomersMix(@Param("incNew") boolean incNew,
                                        @Param("incDelivered") boolean incDelivered,
                                        @Param("incCancelled") boolean incCancelled,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to,
                                        @Param("search") String search,
                                        @Param("cursorSpent") BigDecimal cursorSpent,
                                        @Param("cursorPhone") String cursorPhone,
                                        @Param("limit") int limit);

    /** Sales tab "By Customer" for the heaviest case — All-time period
     * with the All filter — without reading a single delivered order:
     * Delivered figures come off the running totals on this table
     * (delivered_count / total_spent), and only the NEW and CANCELLED
     * orders (a small slice of history) are aggregated live from orders,
     * by phone, and added on top. Same row shape and (spent, phone)
     * keyset as findTopCustomersMix, whose All-time answer this
     * reproduces: spent = delivered total + new total + cancelled total,
     * orders = all three counts. Customers with no orders at all are
     * left out. */
    @Query(value =
            "WITH x AS (" +
            "    SELECT o.phone, COUNT(*) AS cnt, SUM(o.total_amount) AS amt, " +
            "           COUNT(*) FILTER (WHERE o.status = 'NEW') AS new_orders, " +
            "           COUNT(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled_orders, " +
            "           COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'NEW'), 0) AS new_spent, " +
            "           COALESCE(SUM(o.total_amount) FILTER (WHERE o.status = 'CANCELLED'), 0) AS cancelled_spent " +
            "    FROM orders o " +
            "    WHERE o.status IN ('NEW', 'CANCELLED') " +
            "    GROUP BY o.phone" +
            "), r AS (" +
            "    SELECT c.phone, c.name, c.flat_no, c.address_line, c.landmark, " +
            "           COALESCE(c.delivered_count, 0) + COALESCE(x.cnt, 0) AS orders, " +
            "           COALESCE(c.total_spent, 0) + COALESCE(x.amt, 0) AS spent, " +
            "           COALESCE(x.new_orders, 0) AS new_orders, " +
            "           COALESCE(x.cancelled_orders, 0) AS cancelled_orders, " +
            "           COALESCE(x.new_spent, 0) AS new_spent, " +
            "           COALESCE(x.cancelled_spent, 0) AS cancelled_spent " +
            "    FROM customers c " +
            "    LEFT JOIN x ON x.phone = c.phone " +
            "    WHERE (CAST(:search AS text) IS NULL OR " +
            "           LOWER(c.phone) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')) OR " +
            "           LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS text), '%')))" +
            ") " +
            "SELECT r.phone, r.name, r.flat_no, r.address_line, r.landmark, " +
            "       r.orders, r.spent, r.new_orders, r.cancelled_orders, r.new_spent, r.cancelled_spent " +
            "FROM r " +
            "WHERE r.orders > 0 " +
            "  AND (CAST(:cursorSpent AS numeric) IS NULL OR " +
            "       r.spent < CAST(:cursorSpent AS numeric) OR " +
            "       (r.spent = CAST(:cursorSpent AS numeric) AND " +
            "        r.phone < CAST(:cursorPhone AS varchar))) " +
            "ORDER BY r.spent DESC, r.phone DESC " +
            "LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findTopCustomersAllTimeAll(@Param("search") String search,
                                               @Param("cursorSpent") BigDecimal cursorSpent,
                                               @Param("cursorPhone") String cursorPhone,
                                               @Param("limit") int limit);
}
