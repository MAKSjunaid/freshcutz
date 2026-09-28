package com.freshcutz.service;

import com.freshcutz.dto.DailySalesGroup;
import com.freshcutz.dto.NewOrdersCountResponse;
import com.freshcutz.dto.DailySalesRow;
import com.freshcutz.dto.DailyTotalRow;
import com.freshcutz.dto.SalesSummaryResponse;
import com.freshcutz.dto.SumCountRow;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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

    /** Just the "new orders" count — a single cheap COUNT(*), meant to be
     * polled everywhere in the admin panel (not just the Sales tab) to
     * keep the Orders-tab badge live without paying for the full
     * multi-bucket summary above on every tick. */
    @Transactional(readOnly = true)
    public NewOrdersCountResponse getNewOrdersCount() {
        return NewOrdersCountResponse.builder()
                .newOrders(orderRepository.countByStatus(OrderStatus.NEW))
                .build();
    }

    /** [from, to] window of delivered orders, totaled day by day — backs
     * the Sales tab's Apply click. One grouped SQL query, one row per
     * calendar day in the range (see OrderRepository.findDailyTotalsBetween)
     * — NOT one row per order. The range-wide total/count the Sales tab
     * shows above this breakdown are summed client-side from these per-day
     * totals instead of asking the database for the same SUM+COUNT a
     * second time. An individual day's actual orders are fetched
     * separately, only once an admin expands that day — see
     * getDayOrders() below and AdminOrderController's
     * GET /summary/range/daily/{date}. */
    @Transactional(readOnly = true)
    public List<DailySalesGroup> getDailyRangeSummary(LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();

        List<DailyTotalRow> totals = orderRepository.findDailyTotalsBetween(
                OrderStatus.DELIVERED.name(), start, end, SHOP_ZONE.getId());

        List<DailySalesGroup> groups = new ArrayList<>();
        for (DailyTotalRow row : totals) {
            groups.add(DailySalesGroup.builder()
                    .date(row.getDay())
                    .total(row.getTotal())
                    .orders(row.getOrders())
                    .build());
        }
        return groups;
    }

    /** One day's worth of delivered orders, as lightweight rows (code,
     * customer, date, total — no address, no items) — fetched only when
     * an admin actually expands that day in the Sales tab's breakdown
     * (see AdminOrderController's GET /summary/range/daily/{date}). An
     * order's full detail (address, notes, items) is a further, separate
     * fetch of its own, only when that specific order is tapped open —
     * see OrderService.getById. */
    @Transactional(readOnly = true)
    public List<DailySalesRow> getDayOrders(LocalDate day) {
        Instant start = day.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        return orderRepository.findDeliveredSummaryBetween(OrderStatus.DELIVERED, start, end);
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
