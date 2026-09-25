package com.freshcutz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** Response for the public "have we seen this phone number before?" check
 * at checkout (see CustomerLookupController). Always HTTP 200 — "no match"
 * is a completely normal outcome (a first-time customer), not an error, so
 * the frontend doesn't need special-case error handling for it. When
 * found=false, every other field is null and the checkout form just starts
 * blank, same as it always has. */
@Getter
@Builder
@AllArgsConstructor
public class CustomerLookupResponse {

    private boolean found;
    private String name;
    private String flatNo;
    private String addressLine;
    private String landmark;
}
