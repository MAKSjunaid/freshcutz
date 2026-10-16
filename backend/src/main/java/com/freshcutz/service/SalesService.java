package com.freshcutz.service;

import com.freshcutz.dto.DailyOverviewResponse;
import com.freshcutz.dto.DailySalesGroup;
import com.freshcutz.dto.NewOrdersCountResponse;
import com.freshcutz.dto.DayOrderRow;
import com.freshcutz.dto.PeriodSummaryResponse;
import com.freshcutz.dto.SalesSummaryResponse;
import com.freshcutz.dto.SumCountRow;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Rolling-window sales numbers for the admin home screen.
 *
 * Every method here is scoped to the database: SUM/COUNT aggregation and
 * date-range filtering all happen in SQL (see OrderRepository's
 * sumAndCountDelivered... and findDeliveredSummaryBetween queries), never by
 * loading full order history into the JVM and looping over it. That's
 * what keeps this fast at any order volume — a store with 50,000 delivered
 * orders costs the same handful of small queries as one with 50. */
@Service
public class SalesService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    // Stand-in for a missing "to" bound, so the bounded queries below
    // never have to handle NULL timestamps.
    private static final Instant FAR_FUTURE = Instant.parse("2200-01-01T00:00:00Z");

    private final OrderRepository orderRepository;

    public SalesService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public SalesSummaryResponse getSummary() {
        Instant startOfToday = LocalDate.now(SHOP_ZONE).atStartOfDay(SHOP_ZONE).toInstant();
        Instant last7Days = Instant.now().minusSeconds(7L * 24 * 3600);
        Instant last30Days = Instant.now().minusSeconds(30L * 24 * 3600);

        // Only DELIVERED orders count as sales, and they count on the day
        // they were DELIVERED (not the day they were placed) — a brand-new
        // order shouldn't show up in "Today" until it's actually fulfilled.
        // Each bucket below is a bare "since this instant" cutoff rather
        // than a cumulative filter, because a delivery within the last 7
        // days is automatically within the last 30 too — so the buckets
        // don't need to be computed from each other or from one big list.
        SumCountRow today = orderRepository.sumAndCountDeliveredSince(OrderStatus.DELIVERED, startOfToday);
        SumCountRow week = orderRepository.sumAndCountDeliveredSince(OrderStatus.DELIVERED, last7Days);
        SumCountRow month = orderRepository.sumAndCountDeliveredSince(OrderStatus.DELIVERED, last30Days);
        SumCountRow allTime = orderRepository.sumAndCountDeliveredSince(OrderStatus.DELIVERED, Instant.EPOCH);
        long newOrders = orderRepository.countByStatus(OrderStatus.NEW);

        return SalesSummaryResponse.builder()
                .todayTotal(today.getTotal())
                .todayOrders(today.getCount())
                .weekTotal(week.getTotal())
                .weekOrders(week.getCount())
                .monthTotal(month.getTotal())
                .monthOrders(month.getCount())
                .allTimeTotal(allTime.getTotal())
                .allTimeOrders(allTime.getCount())
                .newOrders(newOrders)
                .build();
    }

    /** The Sales tab's header AND one page of its By Day list, in one
     * request and one SQL query (OrderRepository.findDailyOverview) —
     * previously three queries over the same orders. [from, to] are
     * inclusive shop-local days; both null together is the All-time
     * period. The response carries every status's numbers for the period
     * and for each day; which of them the screen shows (All / New /
     * Delivered / Cancelled) is the browser's choice, so changing that
     * selection needs no new request.
     *
     * Money and the Delivered count are keyed off delivery date; each
     * status box uses its own date (see OrderMixSql), which for Delivered
     * is the same instant, so `orders` always equals `delivered`.
     *
     * Paging: `size` days per page (0 = header only), `cursorDay` = the
     * oldest day already held (null for the first page). One extra day is
     * fetched to learn `hasMore` without a second query. */
    @Transactional(readOnly = true)
    public DailyOverviewResponse getOverview(LocalDate from, LocalDate to, int size, String cursorDay) {
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = to == null ? FAR_FUTURE : to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        int pageSize = Math.max(0, Math.min(size, 200));
        String cursor = (cursorDay == null || cursorDay.isBlank())
                ? "9999-12-31" : LocalDate.parse(cursorDay).toString();

        // Header only (another sub-tab is open): skip the per-day grouping.
        // All time reads the running totals instead of the orders table.
        if (pageSize == 0) {
            List<Object[]> h = (from == null && to == null)
                    ? orderRepository.findHeaderAllTime()
                    : orderRepository.findHeaderBetween(start, end);
            return DailyOverviewResponse.builder()
                    .summary(summaryFrom(h.get(0)))
                    .days(new ArrayList<>())
                    .hasMore(false)
                    .build();
        }

        List<Object[]> rows = orderRepository.findDailyOverview(
                start, end, SHOP_ZONE.getId(), cursor, pageSize + 1);

        // The header columns repeat on every row; always at least one row.
        PeriodSummaryResponse summary = summaryFrom(rows.get(0));

        List<DailySalesGroup> days = new ArrayList<>();
        for (Object[] row : rows) {
            if (row[4] == null) continue; // header-only / empty page
            days.add(DailySalesGroup.builder()
                    .date((String) row[4])
                    .newOrders(toLong(row[5]))
                    .deliveredOrders(toLong(row[6]))
                    .cancelledOrders(toLong(row[7]))
                    .newTotal(toBigDecimal(row[8]))
                    .deliveredTotal(toBigDecimal(row[9]))
                    .cancelledTotal(toBigDecimal(row[10]))
                    .build());
        }
        boolean hasMore = pageSize > 0 && days.size() > pageSize;
        if (hasMore) days = new ArrayList<>(days.subList(0, pageSize));

        return DailyOverviewResponse.builder().summary(summary).days(days).hasMore(hasMore).build();
    }

    /** Just the "new orders" count — a single cheap COUNT(*), meant to be
     * polled everywhere in the admin panel (not just the Sales tab) to
     * keep the Orders-tab badge live without paying for the full
     * multi-bucket summary above on every tick. */
    public NewOrdersCountResponse getNewOrdersCount() {
        // No @Transactional here on purpose: a cache hit must not open a DB
        // connection (that would wake Neon). The repository call opens its
        // own short read-only transaction only when the cache is stale.
        return NewOrdersCountResponse.builder()
                .newOrders(NewOrdersCountCache.get(() -> orderRepository.countByStatus(OrderStatus.NEW)))
                .build();
    }

    /** One day's orders for the same status filter as the By Day list, as
     * lightweight rows (code, customer, time, total, status — no address,
     * no items) — fetched only when an admin actually expands that day
     * (see AdminOrderController's GET /summary/range/daily/{date}). An
     * order's full detail (address, notes, items) is a further, separate
     * fetch of its own, only when that specific order is tapped open —
     * see OrderService.getById. */
    @Transactional(readOnly = true)
    public List<DayOrderRow> getDayOrders(LocalDate day, SalesStatusFilter filter) {
        Instant start = day.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        List<Object[]> rows = orderRepository.findDayOrdersMix(
                filter.includeNew(), filter.includeDelivered(), filter.includeCancelled(), start, end);

        List<DayOrderRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(DayOrderRow.builder()
                    .id(toLong(row[0]))
                    .orderCode((String) row[1])
                    .customerName((String) row[2])
                    .at(toInstant(row[3]))
                    .totalAmount(toBigDecimal(row[4]))
                    .status((String) row[5])
                    .build());
        }
        return result;
    }

    /** Builds the header from a row whose first four columns are
     * (new_total, delivered_total, cancelled_total, delivered_amount) —
     * the shape of findDailyOverview's header columns and of both
     * findHeader... queries. */
    private PeriodSummaryResponse summaryFrom(Object[] row) {
        long newTotal = toLong(row[0]);
        long deliveredTotal = toLong(row[1]);
        long cancelledTotal = toLong(row[2]);
        return PeriodSummaryResponse.builder()
                .total(toBigDecimal(row[3]))
                .orders(deliveredTotal)
                .all(newTotal + deliveredTotal + cancelledTotal)
                .newOrders(newTotal)
                .delivered(deliveredTotal)
                .cancelled(cancelledTotal)
                .build();
    }

    // Native query results arrive as raw JDBC types, never the Java types
    // an entity getter would give — same helpers CustomerService and
    // OrderService keep for the same reason.
    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        throw new IllegalStateException("Unexpected number type from native query: " + o.getClass());
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        throw new IllegalStateException("Unexpected amount type from native query: " + o.getClass());
    }

    private Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant) return (Instant) o;
        if (o instanceof Timestamp) return ((Timestamp) o).toInstant();
        if (o instanceof Date) return ((Date) o).toInstant();
        throw new IllegalStateException("Unexpected timestamp type from native query: " + o.getClass());
    }

    /** The orders behind a Sales-tab period and status box, in full (items
     * included) — what "Export this period" writes on the By Day sub-tab.
     * [from, to] inclusive shop-local days, both null for All time; each
     * order is in the period by its own status's date, and only statuses
     * the filter includes are kept — the same orders the By Day numbers
     * count. */
    @Transactional(readOnly = true)
    public List<Order> ordersForExport(LocalDate from, LocalDate to, SalesStatusFilter filter) {
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = to == null ? FAR_FUTURE : to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        List<Order> result = new ArrayList<>();
        for (Order o : orderRepository.findAllInPeriod(start, end)) {
            if ((o.getStatus() == OrderStatus.NEW && filter.includeNew())
                    || (o.getStatus() == OrderStatus.DELIVERED && filter.includeDelivered())
                    || (o.getStatus() == OrderStatus.CANCELLED && filter.includeCancelled())) {
                result.add(o);
            }
        }
        return result;
    }

    /** Orders created within [from, to) — used by the export feature's
     * date-filtered download. */
    @Transactional(readOnly = true)
    public List<Order> ordersInRange(Instant from, Instant to) {
        return orderRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to);
    }

    /** Same as above but takes plain admin-picked calendar dates (inclusive
     * of both days, shop-local time) instead of raw Instants. */
    @Transactional(readOnly = true)
    public List<Order> ordersInDateRange(LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        return ordersInRange(start, end);
    }
}
