package com.freshcutz.dto;

import lombok.Getter;
import lombok.Setter;

/** Body for PUT /api/customers/address — lets a customer fix their saved
 * name and/or delivery address from the "My Account" profile screen
 * without placing a new order. See CustomerService.updateAddress() for
 * how this gets persisted (a standalone Customer profile row — never a
 * past Order). */
@Getter
@Setter
public class CustomerAddressUpdateRequest {
    private String phone;
    private String name;
    private String flatNo;
    private String addressLine;
    private String landmark;
}
