package com.freshcutz.util;

import com.freshcutz.repository.OrderRepository;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Builds human-friendly order codes like FC-20260922-001. */
@Component
public class OrderCodeGenerator {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderRepository orderRepository;

    public OrderCodeGenerator(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public synchronized String next() {
        String day = ZonedDateTime.now(SHOP_ZONE).format(DAY_FORMAT);
        String prefix = "FC-" + day + "-";
        long countToday = orderRepository.countByOrderCodeStartingWith(prefix);
        String sequence = String.format("%03d", countToday + 1);
        return prefix + sequence;
    }
}
