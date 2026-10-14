package com.freshcutz.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin session tokens.
 *
 * Tokens are SIGNED, not stored: each one carries its own expiry and an
 * HMAC-SHA256 signature, so any running copy of the app can check it without
 * remembering anything. That means an admin stays signed in across a backend
 * restart, a redeploy or a Render sleep/wake — the old in-memory version
 * forgot every session the moment the app restarted, which logged the admin
 * out on the next page refresh.
 *
 * The signing key comes from, in order:
 *   1. freshcutz.admin.token-secret  (env ADMIN_TOKEN_SECRET) if set, else
 *   2. the admin username + password (so changing the admin password
 *      automatically signs every session out).
 *
 * Logging out revokes the token for as long as this copy of the app keeps
 * running (a small in-memory list that is trimmed as tokens expire).
 */
@Component
public class TokenStore {

    private static final String VERSION = "v1";

    private final SecureRandom random = new SecureRandom();
    /** nonce -> expiry (epoch seconds) of tokens that were logged out */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    @Value("${freshcutz.admin.token-ttl-hours:12}")
    private long ttlHours;

    @Value("${freshcutz.admin.token-secret:}")
    private String configuredSecret;

    @Value("${freshcutz.admin.username:}")
    private String adminUsername;

    @Value("${freshcutz.admin.password:}")
    private String adminPassword;

    private volatile byte[] key;

    public String issueToken() {
        long exp = Instant.now().plus(ttlHours, ChronoUnit.HOURS).getEpochSecond();
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String body = VERSION + "." + exp + "." + nonce;
        return body + "." + sign(body);
    }

    /** When the token stops working, or null if it is not a token we issued. */
    public Instant expiryOf(String token) {
        Parsed p = parse(token);
        return p == null ? null : Instant.ofEpochSecond(p.exp);
    }

    public boolean isValid(String token) {
        Parsed p = parse(token);
        if (p == null) {
            return false;
        }
        if (p.exp <= Instant.now().getEpochSecond()) {
            return false;
        }
        return !revoked.containsKey(p.nonce);
    }

    public void revoke(String token) {
        Parsed p = parse(token);
        if (p == null) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        revoked.values().removeIf(exp -> exp <= now); // keep the list small
        revoked.put(p.nonce, p.exp);
    }

    // ---- internals -------------------------------------------------------

    private record Parsed(long exp, String nonce) {}

    /** Returns the token's contents only if its signature is genuine. */
    private Parsed parse(String token) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            return null;
        }
        String body = parts[0] + "." + parts[1] + "." + parts[2];
        byte[] expected = sign(body).getBytes(StandardCharsets.UTF_8);
        byte[] actual = parts[3].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            return null;
        }
        try {
            return new Parsed(Long.parseLong(parts[1]), parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign admin token", e);
        }
    }

    private byte[] signingKey() {
        byte[] k = key;
        if (k == null) {
            String secret = (configuredSecret != null && !configuredSecret.isBlank())
                    ? configuredSecret
                    : "freshcutz-admin-token|" + adminUsername + "|" + adminPassword;
            try {
                k = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            key = k;
        }
        return k;
    }
}
