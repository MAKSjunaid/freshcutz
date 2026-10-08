package com.freshcutz.controller;

import com.freshcutz.dto.PagedProductStatsResponse;
import com.freshcutz.dto.VariantStatResponse;
import com.freshcutz.service.ProductStatsService;
import com.freshcutz.service.SalesStatusFilter;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Sales tab's By Item view: which products are ordered the most, and how
 * much money they bring in — paged, period- and status-filterable (the
 * All/New/Delivered/Cancelled boxes), with a per-variant drill-down. Protected by AdminAuthFilter. See ProductStatsService and
 * ProductSalesStatRepository for where the actual numbers come from. */
@RestController
@RequestMapping("/api/admin/items")
public class AdminItemStatsController {

    private final ProductStatsService productStatsService;

    public AdminItemStatsController(ProductStatsService productStatsService) {
        this.productStatsService = productStatsService;
    }

    /** Paged (default 10 at a time) product ranking, highest revenue
     * first — the Items tab's infinite scroll. from/to are both omitted
     * for the all-time default view and both sent (as plain yyyy-MM-dd
     * dates) once a range is Applied, same convention as the Sales tab's
     * own range endpoints. cursorRevenue/cursorProductId are both
     * omitted for the first page and both sent (the last product's own
     * revenue/id) for every "load more" after that — keyset pagination,
     * same shape as every other infinite-scroll tab in this app. */
    @GetMapping
    public PagedProductStatsResponse list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) BigDecimal cursorRevenue,
            @RequestParam(required = false) Long cursorProductId) {
        return productStatsService.listTopProducts(parseDate(from), parseDate(to), SalesStatusFilter.parse(status),
                size, cursorRevenue, cursorProductId);
    }

    /** One product's sales split by variant (e.g. 500 gm vs 1 kg), within
     * the same optional date range the main list is viewing — fetched
     * only when an admin taps that product open on the Items tab. */
    @GetMapping("/{productId}/variants")
    public List<VariantStatResponse> variants(
            @PathVariable Long productId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status) {
        return productStatsService.listVariantBreakdown(productId, parseDate(from), parseDate(to),
                SalesStatusFilter.parse(status));
    }

    private LocalDate parseDate(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value);
    }
}
