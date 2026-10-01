package com.freshcutz.config;

import com.freshcutz.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** One-time cutover for the new Order.statusUpdatedAt column (added so the
 * admin Orders tab's Delivered/Cancelled/All lists sort by "most recently
 * actioned" instead of "originally placed" — see Order.statusUpdatedAt's
 * own comment for the full story). Every order that existed before this
 * column was added has it NULL; this fills it in once, right after
 * startup, so the sort/keyset-cursor queries in
 * OrderRepository.findOrdersWithItemsOneTrip/findAllOrdersWithItemsOneTrip
 * never have to deal with a NULL there.
 *
 * Safe to run on every single startup, not just the first: the UPDATE's
 * WHERE clause only ever touches rows still NULL, and after the first run
 * there are none left (every order from then on gets statusUpdatedAt set
 * directly — see Order.onCreate and OrderService's markDelivered/
 * markCancelled/reopen — so it's never NULL to begin with). */
@Slf4j
@Component
public class OrderStatusBackfillRunner implements CommandLineRunner {

    private final OrderRepository orderRepository;

    public OrderStatusBackfillRunner(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        int updated = orderRepository.backfillMissingStatusUpdatedAt();
        if (updated > 0) {
            log.info("Backfilled status_updated_at for {} pre-existing order(s)", updated);
        }
    }
}
