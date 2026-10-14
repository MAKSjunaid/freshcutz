package com.freshcutz.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshcutz.dto.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Guards every /api/admin/** endpoint except the login endpoint itself.
 * Expects "Authorization: Bearer &lt;token&gt;" issued by AdminAuthController.
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    private static final String PROTECTED_PREFIX = "/api/admin";
    private static final String LOGIN_PATH = "/api/admin/auth/login";

    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public AdminAuthFilter(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith(PROTECTED_PREFIX)) {
            return true;
        }
        if (path.equals(LOGIN_PATH)) {
            return true;
        }
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;

        if (!tokenStore.isValid(token)) {
            writeUnauthorized(request, response, request.getRequestURI());
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, String path)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        // This filter runs before Spring MVC's CORS handling, so without these
        // headers the browser hides the 401 from the page and reports it as a
        // network failure ("could not reach the server"). The body is only
        // "please log in again", so echoing the caller's origin is harmless.
        String origin = request.getHeader("Origin");
        if (origin != null) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
        }
        ApiError error = ApiError.builder()
                .timestamp(Instant.now())
                .status(401)
                .error("Unauthorized")
                .message("Please log in again")
                .path(path)
                .build();
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
