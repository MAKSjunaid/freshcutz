package com.freshcutz.service;

import com.freshcutz.dto.CustomerLookupResponse;
import com.freshcutz.dto.CustomerSummaryResponse;
import com.freshcutz.dto.OrderResponse;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Groups order history by phone number so the admin can see who's a
 * repeat customer, how many times they've ordered, and what they ordered
 * before. One phone number = one customer; their most recent order supplies
 * the display name (a customer might type it slightly differently between
 * orders). Purely a read-side view over existing orders — doesn't change
 * how orders themselves are stored. */
@Service
public class CustomerService {

    private final OrderRepository orderRepository;

    public CustomerService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public List<CustomerSummaryResponse> listCustomers(String query) {
        List<Order> allOrders = orderRepository.findAllByOrderByCreatedAtDesc(); // newest first

        Map<String, List<Order>> byPhone = allOrders.stream()
                .collect(Collectors.groupingBy(Order::getPhone, LinkedHashMap::new, Collectors.toList()));

        List<CustomerSummaryResponse> customers = new ArrayList<>();
        for (Map.Entry<String, List<Order>> entry : byPhone.entrySet()) {
            List<Order> orders = entry.getValue();
            Order latest = orders.get(0);

            BigDecimal totalSpent = orders.stream()
                    .filter(o -> o.getStatus() == OrderStatus.DELIVERED)
                    .map(Order::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            customers.add(CustomerSummaryResponse.builder()
                    .phone(entry.getKey())
                    .name(latest.getCustomerName())
                    .orderCount(orders.size())
                    .totalSpent(totalSpent)
                    .lastOrderAt(latest.getCreatedAt())
                    .orders(orders.stream().map(OrderResponse::from).collect(Collectors.toList()))
                    .build());
        }

        customers.sort(Comparator.comparing(CustomerSummaryResponse::getLastOrderAt).reversed());
        return filter(customers, query);
    }

    /** Public, unauthenticated lookup used at checkout to recognize a
     * returning customer by phone number and prefill their delivery
     * address — see CustomerLookupController. Strips everything but
     * digits before matching, since that's what the checkout form now
     * sends for both this lookup and the phone it saves on the order
     * itself (see Utils.normalizePhone on the frontend); a blank/garbage
     * phone just means "not found", never an error.
     *
     * NOTE: right now this hands back a saved address to anyone who
     * knows (or guesses) a phone number, with no verification — that's
     * deliberate for this first version, and is meant to be closed by
     * OTP verification on a future round, not by this method. */
    @Transactional(readOnly = true)
    public CustomerLookupResponse lookupByPhone(String phone) {
        String normalized = phone == null ? "" : phone.replaceAll("\\D", "");
        if (normalized.isBlank()) {
            return CustomerLookupResponse.builder().found(false).build();
        }
        return orderRepository.findFirstByPhoneOrderByCreatedAtDesc(normalized)
                .map(o -> CustomerLookupResponse.builder()
                        .found(true)
                        .name(o.getCustomerName())
                        .flatNo(o.getFlatNo())
                        .addressLine(o.getAddressLine())
                        .landmark(o.getLandmark())
                        .build())
                .orElseGet(() -> CustomerLookupResponse.builder().found(false).build());
    }

    private List<CustomerSummaryResponse> filter(List<CustomerSummaryResponse> customers, String query) {
        if (query == null || query.isBlank()) {
            return customers;
        }
        String q = query.trim().toLowerCase();
        return customers.stream()
                .filter(c -> c.getPhone().toLowerCase().contains(q) || c.getName().toLowerCase().contains(q))
                .collect(Collectors.toList());
    }
}
