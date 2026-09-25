package com.freshcutz.service;

import com.freshcutz.dto.CustomerAddressUpdateRequest;
import com.freshcutz.dto.CustomerLookupResponse;
import com.freshcutz.dto.CustomerOrderResponse;
import com.freshcutz.dto.CustomerSummaryResponse;
import com.freshcutz.dto.OrderResponse;
import com.freshcutz.entity.Customer;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.CustomerRepository;
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
    private final CustomerRepository customerRepository;

    public CustomerService(OrderRepository orderRepository, CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
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

        return customerRepository.findById(normalized)
                .map(c -> CustomerLookupResponse.builder()
                        .found(true)
                        .name(c.getName())
                        .flatNo(c.getFlatNo())
                        .addressLine(c.getAddressLine())
                        .landmark(c.getLandmark())
                        .build())
                // No profile row yet (a customer who ordered before this table
                // existed) — fall back to their most recent order, same as
                // before. It gets a real profile row the moment they place
                // their next order (see upsertFromOrder) or edit their
                // address, so this fallback is only ever a one-time thing.
                .orElseGet(() -> orderRepository.findFirstByPhoneOrderByCreatedAtDesc(normalized)
                        .map(o -> CustomerLookupResponse.builder()
                                .found(true)
                                .name(o.getCustomerName())
                                .flatNo(o.getFlatNo())
                                .addressLine(o.getAddressLine())
                                .landmark(o.getLandmark())
                                .build())
                        .orElseGet(() -> CustomerLookupResponse.builder().found(false).build()));
    }

    /** Keeps the profile table in sync with whatever address a customer
     * most recently actually placed an order with — called once, right
     * after an order is saved (see OrderService.placeOrder). The order
     * itself is never touched again after this; only this profile row is
     * written to on future address changes. */
    @Transactional
    public void upsertFromOrder(Order order) {
        customerRepository.save(Customer.builder()
                .phone(order.getPhone())
                .name(order.getCustomerName())
                .flatNo(order.getFlatNo())
                .addressLine(order.getAddressLine())
                .landmark(order.getLandmark())
                .build());
    }

    /** Full order history for the customer-facing "My Orders" screen — same
     * unauthenticated-by-phone pattern as lookupByPhone() above, so it
     * comes with the same caveat: knowing a phone number is all it takes
     * to see every order ever placed under it (items, amounts, delivery
     * notes included). Deliberately excludes customerName/phone from each
     * row (see CustomerOrderResponse) since the customer already knows
     * who they are. */
    @Transactional(readOnly = true)
    public List<CustomerOrderResponse> ordersByPhone(String phone) {
        String normalized = phone == null ? "" : phone.replaceAll("\\D", "");
        if (normalized.isBlank()) {
            return List.of();
        }
        return orderRepository.findByPhoneOrderByCreatedAtDesc(normalized).stream()
                .map(CustomerOrderResponse::from)
                .collect(Collectors.toList());
    }

    /** Lets a customer fix their saved delivery address from the profile
     * screen without placing a new order. Writes to the standalone
     * Customer profile row only — deliberately never to any Order, so
     * editing your address here can't rewrite where a past order says it
     * was delivered. Returns the updated snapshot in the same shape
     * lookupByPhone uses, so the profile screen can just re-render
     * straight from the response. */
    @Transactional
    public CustomerLookupResponse updateAddress(CustomerAddressUpdateRequest request) {
        String normalized = request.getPhone() == null ? "" : request.getPhone().replaceAll("\\D", "");
        if (normalized.isBlank()) {
            throw new BadRequestException("Phone number is required");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Please enter your name");
        }
        if (request.getFlatNo() == null || request.getFlatNo().isBlank()) {
            throw new BadRequestException("Please enter your flat / house no.");
        }
        if (request.getAddressLine() == null || request.getAddressLine().isBlank()) {
            throw new BadRequestException("Please enter your address");
        }

        // Writes only to the profile table — never to any placed order.
        // An order is a permanent record of what was actually delivered
        // where (and under what name); changing your saved name/address
        // afterwards must not rewrite that history.
        Customer saved = customerRepository.save(Customer.builder()
                .phone(normalized)
                .name(request.getName().trim())
                .flatNo(request.getFlatNo().trim())
                .addressLine(request.getAddressLine().trim())
                .landmark(blankToNull(request.getLandmark()))
                .build());

        return CustomerLookupResponse.builder()
                .found(true)
                .name(saved.getName())
                .flatNo(saved.getFlatNo())
                .addressLine(saved.getAddressLine())
                .landmark(saved.getLandmark())
                .build();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
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
