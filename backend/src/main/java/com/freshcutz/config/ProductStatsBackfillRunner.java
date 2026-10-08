package com.freshcutz.config;

import com.freshcutz.repository.ProductSalesStatRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps product_sales_stats correct — covers every order delivered
 * before this table existed (a brand-new table starts out with nothing
 * in it at all), and self-heals it back to the true numbers if the
 * incremental upsert/subtract that otherwise keeps it current (see
 * ProductStatsService.recordOrderDelivered/recordOrderUndelivered) ever
 * drifts — a missed call, a bug, a manual database edit.
 *
 * Same "recompute unconditionally every startup, not just once" shape as
 * CustomerStatsBackfillRunner, for the same reason: this shop's order
 * volume (tens of orders a day, not thousands) makes a full GROUP BY over
 * order_items on every single startup cheap enough to just always do it,
 * rather than tracking which rows are stale. If that ever stops being
 * true, narrow this the same way OrderStatusBackfillRunner's WHERE clause
 * only touches what's actually missing/NULL. */
@Slf4j
@Component
public class ProductStatsBackfillRunner implements CommandLineRunner {

    // Must match SalesService's own SHOP_ZONE — this recompute needs to
    // group deliveries into the same shop-local calendar days the
    // incremental upsert path (and the Sales tab itself) already uses,
    // or a day's number here could disagree with what delivering an
    // order "just now" would have added.
    private static final String SHOP_ZONE = "Asia/Kolkata";

    private final ProductSalesStatRepository productSalesStatRepository;

    public ProductStatsBackfillRunner(ProductSalesStatRepository productSalesStatRepository) {
        this.productSalesStatRepository = productSalesStatRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        int touched = productSalesStatRepository.backfillProductStats(SHOP_ZONE);
        if (touched > 0) {
            log.info("Recomputed product_sales_stats for {} product/variant/day row(s)", touched);
        }
    }
}
