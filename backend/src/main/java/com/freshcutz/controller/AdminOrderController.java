package com.freshcutz.controller;

import com.freshcutz.dto.DailyOverviewResponse;
import com.freshcutz.dto.DayOrderRow;
import com.freshcutz.dto.NewOrdersCountResponse;
import com.freshcutz.dto.OrderResponse;
import com.freshcutz.dto.PagedOrdersResponse;
import com.freshcutz.dto.SalesSummaryResponse;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.service.OrderService;
import com.freshcutz.service.SalesService;
import com.freshcutz.service.SalesStatusFilter;
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

    /** The Sales tab's header (money + status boxes) and one page of the
     * By Day list in a single response. from/to omitted together is the
     * All-time period; every day carries all statuses' numbers (the
     * browser applies the All/New/Delivered/Cancelled selection);
     * `size` days per page (0 = just
     * the header); `cursorDay` (yyyy-MM-dd) is the oldest day the client
     * already holds, omitted for the first page. See
     * SalesService.getOverview. */
    @GetMapping("/summary/overview")
    public DailyOverviewResponse overview(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(defaultValue = "31") int size,
                                          @RequestParam(required = false) String cursorDay) {
        return salesService.getOverview(parseDate(from), parseDate(to), size, cursorDay);
    }

    private LocalDate parseDate(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value);
    }

    /** A single day's individual orders, as lightweight rows — fetched
     * only when an admin expands that day in the Sales tab's By Day list,
     * not shipped up front for every day in the range. `status` is the
     * same All/New/Delivered/Cancelled filter the day list was built
     * with (blank means All). */
    @GetMapping("/summary/range/daily/{date}")
    public List<DayOrderRow> dayOrders(@PathVariable String date,
                                       @RequestParam(required = false) String status) {
        return salesService.getDayOrders(LocalDate.parse(date), SalesStatusFilter.parse(status));
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
