package com.freshcutz.controller;

import com.freshcutz.dto.OrderResponse;
import com.freshcutz.dto.RangeSummaryResponse;
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

    @GetMapping("/summary/range")
    public RangeSummaryResponse rangeSummary(@RequestParam String from, @RequestParam String to) {
        return salesService.getRangeSummary(LocalDate.parse(from), LocalDate.parse(to));
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
