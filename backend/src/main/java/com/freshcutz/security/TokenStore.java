package com.freshcutz.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny in-memory session store for the admin token. No database table
 * needed: tokens simply expire after a configurable number of hours, or
 * when the app restarts (the admin just logs in again).
 */
@Component
public class TokenStore {

    private final Map<String, Instant> tokenExpiry = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Value("${freshcutz.admin.token-ttl-hours:12}")
    private long ttlHours;

    public String issueToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokenExpiry.put(token, Instant.now().plus(ttlHours, ChronoUnit.HOURS));
        return token;
    }

    public Instant expiryOf(String token) {
        return tokenExpiry.get(token);
    }

    public boolean isValid(String token) {
        if (token == null) {
            return false;
        }
        Instant expiry = tokenExpiry.get(token);
        if (expiry == null) {
            return false;
        }
        if (expiry.isBefore(Instant.now())) {
            tokenExpiry.remove(token);
            return false;
        }
        return true;
    }

    public void revoke(String token) {
        tokenExpiry.remove(token);
    }
}
