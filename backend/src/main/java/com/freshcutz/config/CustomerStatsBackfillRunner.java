package com.freshcutz.config;

import com.freshcutz.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the customers table's order_count/total_spent/last_order_at
 * columns correct, including for rows that predate those columns (every
 * one of them starts out NULL when Hibernate adds the column to an
 * existing table) and for any phone that has placed orders but has no
 * customers row at all yet (a customer who ordered before the customers
 * table existed and has never ordered again or edited their address
 * since — see CustomerRepository.backfillCustomerStats's own comment).
 *
 * Unlike OrderStatusBackfillRunner's WHERE-still-NULL guard, this
 * recomputes unconditionally every startup rather than only filling in
 * NULLs: those three columns are also kept current incrementally as
 * orders are placed/delivered/reopened (see CustomerService's
 * recordOrderDelivered/recordOrderUndelivered and
 * CustomerRepository.upsertOnOrderPlaced), so this doubles as a cheap
 * self-heal if any of that incremental bookkeeping ever drifts — a
 * missed call, a manual database edit, a bug — rather than only a
 * one-time cutover. That's a full GROUP BY + DISTINCT ON over the whole
 * orders table on every single startup instead of only the first one,
 * which is the trade this shop's order volume (tens of orders a day, not
 * thousands) can easily afford; if that ever stops being true, this
 * should be narrowed to only the phones actually missing/stale, the same
 * way OrderStatusBackfillRunner's WHERE clause only touches NULL rows. */
@Slf4j
@Component
public class CustomerStatsBackfillRunner implements CommandLineRunner {

    private final CustomerRepository customerRepository;

    public CustomerStatsBackfillRunner(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        int touched = customerRepository.backfillCustomerStats();
        if (touched > 0) {
            log.info("Recomputed order_count/total_spent/last_order_at for {} customer row(s)", touched);
        }
    }
}
