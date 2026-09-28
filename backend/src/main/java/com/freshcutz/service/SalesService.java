package com.freshcutz.service;

import com.freshcutz.dto.DailySalesGroup;
import com.freshcutz.dto.NewOrdersCountResponse;
import com.freshcutz.dto.DailySalesRow;
import com.freshcutz.dto.SalesSummaryResponse;
import com.freshcutz.dto.SumCountRow;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    /** [from, to] window of delivered orders, broken out day by day with
     * each day's orders listed under it — backs the Sales tab's date
     * filter. There's no separate plain range-total query: the range-wide
     * total and count the Sales tab shows above this breakdown are summed
     * client-side from these per-day rows instead of asking the database
     * for the same SUM+COUNT a second time. Each order in the list
     * is a lightweight DailySalesRow (code, customer, date, total only);
     * the full order (address, notes, items) is fetched separately, one
     * at a time, only when an admin taps a row open — see
     * AdminOrderController's GET /{id} and OrderService.getById. */
    @Transactional(readOnly = true)
    public List<DailySalesGroup> getDailyRangeSummary(LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();

        // One query, already filtered to the range and sorted newest
        // first by the database — not a load of every delivered order
        // ever placed followed by filtering/sorting in Java.
        List<DailySalesRow> rows = orderRepository.findDeliveredSummaryBetween(OrderStatus.DELIVERED, start, end);

        // Newest day first — same ordering as every other order listing
        // in this app.
        Map<LocalDate, List<DailySalesRow>> byDate = new TreeMap<>(Comparator.reverseOrder());
        for (DailySalesRow row : rows) {
            LocalDate day = row.getDeliveredAt().atZone(SHOP_ZONE).toLocalDate();
            byDate.computeIfAbsent(day, d -> new ArrayList<>()).add(row);
        }

        List<DailySalesGroup> groups = new ArrayList<>();
        for (Map.Entry<LocalDate, List<DailySalesRow>> entry : byDate.entrySet()) {
            List<DailySalesRow> dayRows = entry.getValue();
            BigDecimal dayTotal = BigDecimal.ZERO;
            for (DailySalesRow row : dayRows) {
                dayTotal = dayTotal.add(row.getTotalAmount());
            }
            groups.add(DailySalesGroup.builder()
                    .date(entry.getKey().toString())
                    .total(dayTotal)
                    .orders(dayRows.size())
                    .rows(dayRows)
                    .build());
        }

        return groups;
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
