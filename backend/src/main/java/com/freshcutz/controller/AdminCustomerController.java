package com.freshcutz.controller;

import com.freshcutz.dto.CustomerOrderResponse;
import com.freshcutz.dto.PagedCustomersResponse;
import com.freshcutz.service.CustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
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

    /** One customer's full order history (items included) — fetched only
     * when an admin actually taps "View past orders" on their card,
     * instead of every customer's history riding along with the list
     * above whether anyone looks at it or not. */
    @GetMapping("/{phone}/orders")
    public List<CustomerOrderResponse> orders(@PathVariable String phone) {
        return customerService.ordersForCustomer(phone);
    }
}
