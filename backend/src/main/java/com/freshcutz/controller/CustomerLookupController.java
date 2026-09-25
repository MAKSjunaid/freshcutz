package com.freshcutz.controller;

import com.freshcutz.dto.CustomerLookupResponse;
import com.freshcutz.service.CustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoint the checkout form calls right after the customer types
 * their phone number, so a returning customer's delivery address can be
 * filled in automatically instead of retyped every time. Deliberately NOT
 * under /api/admin — AdminAuthFilter only guards that prefix, and this has
 * to be reachable before the customer has logged into anything (there's no
 * customer login yet). See CustomerService.lookupByPhone() for the one
 * real caveat this first version comes with. */
@RestController
@RequestMapping("/api/customers")
public class CustomerLookupController {

    private final CustomerService customerService;

    public CustomerLookupController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping("/lookup")
    public CustomerLookupResponse lookup(@RequestParam String phone) {
        return customerService.lookupByPhone(phone);
    }
}
