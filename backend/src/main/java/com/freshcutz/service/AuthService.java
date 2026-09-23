package com.freshcutz.service;

import com.freshcutz.dto.LoginRequest;
import com.freshcutz.dto.LoginResponse;
import com.freshcutz.exception.UnauthorizedException;
import com.freshcutz.security.TokenStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Checks the single hardcoded admin login (see application.properties /
 * ADMIN_USERNAME + ADMIN_PASSWORD env vars) and issues a bearer token.
 */
@Service
public class AuthService {

    private final TokenStore tokenStore;

    @Value("${freshcutz.admin.username}")
    private String adminUsername;

    @Value("${freshcutz.admin.password}")
    private String adminPassword;

    public AuthService(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    public LoginResponse login(LoginRequest request) {
        boolean ok = constantTimeEquals(adminUsername, request.getUsername())
                && constantTimeEquals(adminPassword, request.getPassword());
        if (!ok) {
            throw new UnauthorizedException("Incorrect username or password");
        }
        String token = tokenStore.issueToken();
        return LoginResponse.builder()
                .token(token)
                .expiresAt(tokenStore.expiryOf(token))
                .build();
    }

    public void logout(String token) {
        tokenStore.revoke(token);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
