package com.freshcutz.controller;

import com.freshcutz.dto.CustomerAddressUpdateRequest;
import com.freshcutz.dto.CustomerLookupResponse;
import com.freshcutz.dto.CustomerOrderResponse;
import com.freshcutz.service.CustomerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public endpoints behind the customer-facing "My Account" screen and the
 * checkout form's returning-customer prefill. Deliberately NOT under
 * /api/admin — AdminAuthFilter only guards that prefix, and these have to
 * be reachable before the customer has logged into anything (there's no
 * customer login beyond "type your phone number"). See
 * CustomerService.lookupByPhone()/ordersByPhone() for the one real caveat
 * this comes with: a phone number alone is enough to see this data, with
 * no OTP or password. */
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

    /** Every past order for this phone, newest first — powers the "Your
     * Orders" list on the "My Account" screen. */
    @GetMapping("/orders")
    public List<CustomerOrderResponse> orders(@RequestParam String phone) {
        return customerService.ordersByPhone(phone);
    }

    /** Lets a customer correct their saved delivery address from "My
     * Account" > "Change address", without placing a new order. */
    @PutMapping("/address")
    public CustomerLookupResponse updateAddress(@RequestBody CustomerAddressUpdateRequest request) {
        return customerService.updateAddress(request);
    }
}
