package com.freshcutz.service;

import com.freshcutz.dto.PagedProductStatsResponse;
import com.freshcutz.dto.ProductStatSummaryResponse;
import com.freshcutz.dto.VariantStatResponse;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderItem;
import com.freshcutz.repository.ProductSalesStatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/** Backs the admin Items tab: which products are ordered the most, and how
 * much money they've earned — see ProductSalesStatRepository and its
 * backing entity's own comments for the full "why a rollup table, not a
 * live aggregation" story. Everything here reads from (or writes to) that
 * rollup, never order_items/orders directly — this is the read/write
 * boundary the rest of the app goes through instead of touching the
 * repository on its own. */
@Service
public class ProductStatsService {

    // Matches SalesService's own SHOP_ZONE exactly — both classes need the
    // same "which calendar day did this delivery actually happen on,
    // shop-local" conversion, but duplicated rather than shared, the same
    // small-private-helper-per-service convention CustomerService/
    // OrderService already use for their own toLong/toBigDecimal/toInstant.
    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");

    private final ProductSalesStatRepository productSalesStatRepository;

    public ProductStatsService(ProductSalesStatRepository productSalesStatRepository) {
        this.productSalesStatRepository = productSalesStatRepository;
    }

    /** Paged, optionally date-filtered product ranking for the Items
     * list's infinite scroll — sorted by revenue (highest first) by
     * default, filtered by the Sales tab's All/New/Delivered/Cancelled
     * status (see SalesStatusFilter). Delivered-only reads the fast
     * product_sales_stats rollup (see ProductSalesStatRepository
     * .findTopProductsPage for the query shape and its keyset cursor);
     * anything that includes New or Cancelled orders can't — the rollup
     * only ever knows about delivered ones — so it is computed live from
     * order_items instead (findTopProductsMix), which stays cheap because
     * New orders are few and the period bounds the rest. `size` is
     * clamped the same way every other paged list in this app clamps its
     * own. */
    @Transactional(readOnly = true)
    public PagedProductStatsResponse listTopProducts(LocalDate from, LocalDate to, SalesStatusFilter filter,
                                                       int size, BigDecimal cursorRevenue, Long cursorProductId) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        List<Object[]> rows;
        if (filter == SalesStatusFilter.ALL && from == null && to == null) {
            rows = productSalesStatRepository.findTopProductsAllTimeAll(
                    cursorRevenue, cursorProductId, pageSize + 1);
        } else if (filter == SalesStatusFilter.DELIVERED) {
            rows = productSalesStatRepository.findTopProductsPage(
                    from, to, cursorRevenue, cursorProductId, pageSize + 1);
        } else {
            rows = productSalesStatRepository.findTopProductsMix(
                    filter.includeNew(), filter.includeDelivered(), filter.includeCancelled(),
                    toStart(from), toEnd(to), cursorRevenue, cursorProductId, pageSize + 1);
        }
        boolean hasMore = rows.size() > pageSize;
        List<Object[]> pageRows = hasMore ? rows.subList(0, pageSize) : rows;

        List<ProductStatSummaryResponse> items = pageRows.stream()
                .map(this::mapProductRow)
                .collect(Collectors.toList());
        return PagedProductStatsResponse.builder()
                .items(items)
                .hasMore(hasMore)
                .build();
    }

    /** Every product of the By Item ranking for a period and status box,
     * not just one page — walks the same keyset pages as the screen (100
     * at a time) so the numbers match the list exactly. Backs "Export this
     * period" on the By Item sub-tab. */
    @Transactional(readOnly = true)
    public List<ProductStatSummaryResponse> listAllTopProducts(LocalDate from, LocalDate to, SalesStatusFilter filter) {
        List<ProductStatSummaryResponse> all = new java.util.ArrayList<>();
        BigDecimal cursorRevenue = null;
        Long cursorProductId = null;
        while (true) {
            PagedProductStatsResponse page = listTopProducts(from, to, filter, 100, cursorRevenue, cursorProductId);
            all.addAll(page.getItems());
            if (!page.isHasMore() || page.getItems().isEmpty()) break;
            ProductStatSummaryResponse last = page.getItems().get(page.getItems().size() - 1);
            cursorRevenue = last.getRevenue();
            cursorProductId = last.getProductId();
        }
        return all;
    }

    /** Variant split of every product for the same period/status — one
     * query, keyed by product id. */
    @Transactional(readOnly = true)
    public java.util.Map<Long, List<VariantStatResponse>> listAllVariants(LocalDate from, LocalDate to,
                                                                           SalesStatusFilter filter) {
        java.util.Map<Long, List<VariantStatResponse>> byProduct = new java.util.LinkedHashMap<>();
        for (Object[] row : productSalesStatRepository.findAllVariantsMix(
                filter.includeNew(), filter.includeDelivered(), filter.includeCancelled(),
                toStart(from), toEnd(to))) {
            byProduct.computeIfAbsent(toLong(row[0]), k -> new java.util.ArrayList<>())
                    .add(VariantStatResponse.builder()
                            .variantLabel((String) row[1])
                            .quantity(toLong(row[2]))
                            .revenue(toBigDecimal(row[3]))
                            .build());
        }
        return byProduct;
    }

    // [from, to] as the half-open instant window the live (non-rollup)
    // queries want — a missing bound means "no limit on that side".
    private Instant toStart(LocalDate from) {
        return from == null ? Instant.EPOCH : from.atStartOfDay(SHOP_ZONE).toInstant();
    }

    private Instant toEnd(LocalDate to) {
        return to == null ? Instant.parse("2200-01-01T00:00:00Z") : to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
    }

    private ProductStatSummaryResponse mapProductRow(Object[] row) {
        return ProductStatSummaryResponse.builder()
                .productId(toLong(row[0]))
                .productName((String) row[1])
                .quantity(toLong(row[2]))
                .revenue(toBigDecimal(row[3]))
                .newQuantity(toLong(row[4]))
                .newRevenue(toBigDecimal(row[5]))
                .cancelledQuantity(toLong(row[6]))
                .cancelledRevenue(toBigDecimal(row[7]))
                .build();
    }

    /** One product's sales split by variant, within the same optional date
     * range the main list is viewing — backs the Items tab's tap-to-expand
     * drill-down (see AdminItemStatsController's GET /{productId}/variants).
     * Not paged — see findVariantBreakdown's own comment for why. */
    @Transactional(readOnly = true)
    public List<VariantStatResponse> listVariantBreakdown(Long productId, LocalDate from, LocalDate to,
                                                           SalesStatusFilter filter) {
        List<Object[]> rows;
        if (filter == SalesStatusFilter.ALL && from == null && to == null) {
            rows = productSalesStatRepository.findVariantBreakdownAllTimeAll(productId);
        } else if (filter == SalesStatusFilter.DELIVERED) {
            rows = productSalesStatRepository.findVariantBreakdown(productId, from, to);
        } else {
            rows = productSalesStatRepository.findVariantBreakdownMix(
                    productId, filter.includeNew(), filter.includeDelivered(), filter.includeCancelled(),
                    toStart(from), toEnd(to));
        }
        return rows.stream()
                .map(row -> VariantStatResponse.builder()
                        .variantLabel((String) row[0])
                        .quantity(toLong(row[1]))
                        .revenue(toBigDecimal(row[2]))
                        .build())
                .collect(Collectors.toList());
    }

    /** Adds a just-Delivered order's items onto this table's running
     * totals — called from OrderService.markDelivered right after the
     * order itself is saved as Delivered, once per line item, each as its
     * own atomic UPSERT (see ProductSalesStatRepository.upsertOnDelivered).
     * The day each item counts toward is this order's own deliveredAt
     * (shop-local calendar day), matching the Sales tab's own "a delivery
     * counts on the day it was delivered" convention — never "today", in
     * case this is ever called somewhere other than the instant of
     * delivery. */
    @Transactional
    public void recordOrderDelivered(Order order) {
        LocalDate saleDate = toShopLocalDate(order.getDeliveredAt());
        if (saleDate == null) {
            return; // shouldn't happen for an order that was actually just marked Delivered
        }
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                continue; // no stable product reference to roll this item up under
            }
            productSalesStatRepository.upsertOnDelivered(
                    item.getProductId(),
                    item.getProductName(),
                    item.getVariantLabel(),
                    saleDate,
                    item.getQuantity(),
                    item.getLineTotal());
        }
    }

    /** The inverse — called from OrderService.reopen only when the order
     * being reopened was Delivered, once per line item, subtracting back
     * out of the SAME day's row it was originally added to.
     * previousDeliveredAt is that order's deliveredAt from just before
     * OrderService.reopen clears it to null — this method can't recover
     * that date from the order itself once reopen has run, so the caller
     * has to capture and pass it through. */
    @Transactional
    public void recordOrderUndelivered(Order order, Instant previousDeliveredAt) {
        LocalDate saleDate = toShopLocalDate(previousDeliveredAt);
        if (saleDate == null) {
            return; // shouldn't happen for an order that was actually Delivered before this reopen
        }
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                continue;
            }
            productSalesStatRepository.subtractOnUndelivered(
                    item.getProductId(),
                    item.getVariantLabel(),
                    saleDate,
                    item.getQuantity(),
                    item.getLineTotal());
        }
    }

    private LocalDate toShopLocalDate(Instant instant) {
        return instant == null ? null : instant.atZone(SHOP_ZONE).toLocalDate();
    }

    private Long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        throw new IllegalStateException("Unexpected count type from native query: " + o.getClass());
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        throw new IllegalStateException("Unexpected amount type from native query: " + o.getClass());
    }
}
