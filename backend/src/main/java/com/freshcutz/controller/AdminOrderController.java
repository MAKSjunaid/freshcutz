package com.freshcutz.controller;

import com.freshcutz.dto.DailySalesGroup;
import com.freshcutz.dto.DailySalesRow;
import com.freshcutz.dto.NewOrdersCountResponse;
import com.freshcutz.dto.OrderResponse;
import com.freshcutz.dto.PagedOrdersResponse;
import com.freshcutz.dto.SalesSummaryResponse;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.service.OrderService;
import com.freshcutz.service.SalesService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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

    /** Paged (default 10 at a time) + optionally search-filtered orders for
     * the Orders tab's infinite scroll — status omitted/blank means the
     * "All" tab, same as before. The older unpaged behavior is gone from
     * this endpoint entirely (nothing else called it, so there's no
     * compatibility concern) — see OrderService.listPaged for why.
     *
     * cursorStatusUpdatedAt/cursorId are both omitted for the first page
     * and both sent (the last order's own statusUpdatedAt/id) for every
     * "load more" after that — keyset pagination instead of a page
     * number, so the database never has to walk past and discard rows
     * just to find where the next page starts. There's no "page" param
     * anymore: an OFFSET-based page number is exactly what this replaces.
     * Keyed on statusUpdatedAt rather than createdAt so the Delivered/
     * Cancelled/All tabs page through orders in the same "most recently
     * actioned first" order they're displayed in — see
     * OrderService.listPaged and Order.statusUpdatedAt for why. */
    @GetMapping
    public PagedOrdersResponse list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Instant cursorStatusUpdatedAt,
            @RequestParam(required = false) Long cursorId) {
        OrderStatus parsedStatus = (status == null || status.isBlank()) ? null : OrderStatus.valueOf(status.toUpperCase());
        return orderService.listPaged(parsedStatus, search, size, cursorStatusUpdatedAt, cursorId);
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

    /** One row per calendar day in [from, to] — just the day's total and
     * order count, NOT its individual orders — backs the Sales tab's
     * Apply click. (There's no separate plain range-total endpoint: the
     * range-wide total and count shown above this breakdown are summed
     * client-side from these per-day totals instead of asking the
     * database for the same SUM+COUNT a second time.) */
    @GetMapping("/summary/range/daily")
    public List<DailySalesGroup> dailyRangeSummary(@RequestParam String from, @RequestParam String to) {
        return salesService.getDailyRangeSummary(LocalDate.parse(from), LocalDate.parse(to));
    }

    /** A single day's individual delivered orders, as lightweight rows —
     * fetched only when an admin expands that day in the Sales tab's
     * breakdown, not shipped up front for every day in the range. */
    @GetMapping("/summary/range/daily/{date}")
    public List<DailySalesRow> dayOrders(@PathVariable String date) {
        return salesService.getDayOrders(LocalDate.parse(date));
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
