package com.freshcutz.controller;

import com.freshcutz.dto.CustomerOrderResponse;
import com.freshcutz.dto.PagedCustomerSalesResponse;
import com.freshcutz.dto.PagedCustomersResponse;
import com.freshcutz.service.CustomerService;
import com.freshcutz.service.SalesStatusFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Customer directory for the admin panel: who's ordered, how many times,
 * and where to deliver — grouped by phone number. Protected by
 * AdminAuthFilter. */
@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    private final CustomerService customerService;

    public AdminCustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** Paged (default 10 at a time) + optionally search-filtered customers
     * for the Customers tab's infinite scroll — see CustomerService
     * .listCustomersPaged for why this replaced the old "load every order
     * ever placed" approach, and for the default sort (most orders first).
     * cursorOrderCount/cursorLastOrderAt/cursorPhone are all omitted for
     * the first page and all sent (the last customer's own orderCount/
     * lastOrderAt/phone) for every "load more" after that — keyset
     * pagination, same shape as the Orders tab's own cursor params. */
    @GetMapping
    public PagedCustomersResponse list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long cursorOrderCount,
            @RequestParam(required = false) Instant cursorLastOrderAt,
            @RequestParam(required = false) String cursorPhone) {
        return customerService.listCustomersPaged(q, size, cursorOrderCount, cursorLastOrderAt, cursorPhone);
    }

    /** Customers ranked by the total of their orders, highest first — the
     * Sales tab's "By Customer" view. from/to (plain yyyy-MM-dd) are
     * both omitted for the All-time period and both sent for every other
     * one, same convention as the other Sales endpoints; status is the
     * All/New/Delivered/Cancelled filter (blank means All); q optionally
     * filters by phone/name. cursorSpent/cursorPhone are both omitted for
     * the first page and both sent (the last customer's own spent/phone)
     * for every "load more" — keyset pagination. See CustomerService
     * .listTopCustomers for the two query paths behind this. */
    @GetMapping("/top")
    public PagedCustomerSalesResponse top(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) BigDecimal cursorSpent,
            @RequestParam(required = false) String cursorPhone) {
        return customerService.listTopCustomers(parseDate(from), parseDate(to), SalesStatusFilter.parse(status),
                q, size, cursorSpent, cursorPhone);
    }

    private LocalDate parseDate(String value) {
        return (value == null || value.isBlank()) ? null : LocalDate.parse(value);
    }

    /** One customer's orders (items included) — fetched only when an admin
     * actually taps "View past orders" on their card, instead of every
     * customer's history riding along with the list above whether anyone
     * looks at it or not. With no parameters it's the full history (the
     * Customer Info tab); the Sales tab's By Customer view passes the
     * selected period (from/to) and status so the list matches the card's
     * own order count and total. */
    @GetMapping("/{phone}/orders")
    public List<CustomerOrderResponse> orders(@PathVariable String phone,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) String status) {
        return customerService.ordersForCustomer(phone, parseDate(from), parseDate(to),
                SalesStatusFilter.parse(status));
    }
}
