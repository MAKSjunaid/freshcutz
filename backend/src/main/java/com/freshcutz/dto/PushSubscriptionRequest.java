package com.freshcutz.dto;

import lombok.Getter;
import lombok.Setter;

/** Mirrors the browser's PushSubscription.toJSON() shape exactly, so
 * admin-push.js can POST the subscription object it gets from
 * pushManager.subscribe() straight through with no reshaping. */
@Getter
@Setter
public class PushSubscriptionRequest {
    private String endpoint;
    private Keys keys;

    @Getter
    @Setter
    public static class Keys {
        private String p256dh;
        private String auth;
    }
}
