package com.freshcutz.controller;

import com.freshcutz.dto.CustomerSummaryResponse;
import com.freshcutz.service.CustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Customer directory for the admin panel: who's ordered, how many times,
 * and what they ordered before — grouped by phone number. Protected by
 * AdminAuthFilter. */
@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    private final CustomerService customerService;

    public AdminCustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public List<CustomerSummaryResponse> list(@RequestParam(required = false) String q) {
        return customerService.listCustomers(q);
    }
}
