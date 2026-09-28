package com.freshcutz.controller;

import com.freshcutz.dto.DailySalesGroup;
import com.freshcutz.dto.NewOrdersCountResponse;
import com.freshcutz.dto.OrderResponse;
import com.freshcutz.dto.SalesSummaryResponse;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.service.OrderService;
import com.freshcutz.service.SalesService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Order inbox + sales dashboard for the admin panel. Protected by AdminAuthFilter. */
@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final OrderService orderService;
    private final SalesService salesService;

    public AdminOrderController(OrderService orderService, SalesService salesService) {
        this.orderService = orderService;
        this.salesService = salesService;
    }

    @GetMapping
    public List<OrderResponse> list(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return orderService.listAll();
        }
        return orderService.listByStatus(OrderStatus.valueOf(status.toUpperCase()));
    }

    @GetMapping("/summary")
    public SalesSummaryResponse summary() {
        return salesService.getSummary();
    }

    /** Just the "new orders" badge count — polled everywhere in the admin
     * panel (see admin-main.js's background poll) so the Orders tab's
     * badge stays live without recomputing the full dashboard summary. */
    @GetMapping("/summary/new-count")
    public NewOrdersCountResponse newOrdersCount() {
        return salesService.getNewOrdersCount();
    }

    /** Range-wide total + count, broken out day by day with the actual
     * orders listed under each date — backs the Sales tab's date filter.
     * (There's no separate plain-total endpoint: the range-wide total and
     * count shown above this breakdown are summed client-side from these
     * per-day rows instead of asking the database for the same SUM+COUNT
     * a second time.) */
    @GetMapping("/summary/range/daily")
    public List<DailySalesGroup> dailyRangeSummary(@RequestParam String from, @RequestParam String to) {
        return salesService.getDailyRangeSummary(LocalDate.parse(from), LocalDate.parse(to));
    }

    /** Full detail (address, notes, items) for exactly one order — the
     * Sales tab fetches this only when an admin taps a row open, instead
     * of every order in the applied range shipping its full detail on
     * every Apply click. */
    @GetMapping("/{id}")
    public OrderResponse getOne(@PathVariable Long id) {
        return orderService.getById(id);
    }

    @PatchMapping("/{id}/delivered")
    public OrderResponse markDelivered(@PathVariable Long id) {
        return orderService.markDelivered(id);
    }

    @PatchMapping("/{id}/cancelled")
    public OrderResponse markCancelled(@PathVariable Long id) {
        return orderService.markCancelled(id);
    }

    @PatchMapping("/{id}/reopen")
    public OrderResponse reopen(@PathVariable Long id) {
        return orderService.reopen(id);
    }
}
