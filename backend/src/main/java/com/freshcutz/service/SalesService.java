package com.freshcutz.service;

import com.freshcutz.dto.RangeSummaryResponse;
import com.freshcutz.dto.SalesSummaryResponse;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Rolling-window sales numbers for the admin home screen. */
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
        List<Order> deliveredOrders = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.DELIVERED);

        BigDecimal todayTotal = BigDecimal.ZERO;
        BigDecimal weekTotal = BigDecimal.ZERO;
        BigDecimal monthTotal = BigDecimal.ZERO;
        BigDecimal allTimeTotal = BigDecimal.ZERO;
        long todayCount = 0;
        long weekCount = 0;
        long monthCount = 0;
        long allTimeCount = 0;

        for (Order order : deliveredOrders) {
            Instant deliveredAt = order.getDeliveredAt();
            if (deliveredAt == null) continue;

            allTimeTotal = allTimeTotal.add(order.getTotalAmount());
            allTimeCount++;

            if (deliveredAt.isBefore(last30Days)) continue;
            monthTotal = monthTotal.add(order.getTotalAmount());
            monthCount++;
            if (!deliveredAt.isBefore(last7Days)) {
                weekTotal = weekTotal.add(order.getTotalAmount());
                weekCount++;
            }
            if (!deliveredAt.isBefore(startOfToday)) {
                todayTotal = todayTotal.add(order.getTotalAmount());
                todayCount++;
            }
        }

        long newOrders = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.NEW).size();

        return SalesSummaryResponse.builder()
                .todayTotal(todayTotal)
                .todayOrders(todayCount)
                .weekTotal(weekTotal)
                .weekOrders(weekCount)
                .monthTotal(monthTotal)
                .monthOrders(monthCount)
                .allTimeTotal(allTimeTotal)
                .allTimeOrders(allTimeCount)
                .newOrders(newOrders)
                .build();
    }

    /** Total + order count for an admin-picked "from" to "to" date range
     * (inclusive of both days, shop-local time), for the Sales tab's date
     * filter. Only DELIVERED orders count, keyed by their delivery date —
     * same rule as getSummary() above. */
    @Transactional(readOnly = true)
    public RangeSummaryResponse getRangeSummary(LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();

        List<Order> deliveredOrders = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.DELIVERED);
        BigDecimal total = BigDecimal.ZERO;
        long count = 0;
        for (Order order : deliveredOrders) {
            Instant deliveredAt = order.getDeliveredAt();
            if (deliveredAt == null) continue;
            if (!deliveredAt.isBefore(start) && deliveredAt.isBefore(end)) {
                total = total.add(order.getTotalAmount());
                count++;
            }
        }

        return RangeSummaryResponse.builder()
                .from(from.toString())
                .to(to.toString())
                .total(total)
                .orders(count)
                .build();
    }

    /** Orders created within [from, to) — used by both the range summary
     * above and the export feature's date-filtered download. */
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
