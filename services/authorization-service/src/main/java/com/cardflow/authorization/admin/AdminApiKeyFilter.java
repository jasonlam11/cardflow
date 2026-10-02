package com.cardflow.authorization.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Admin endpoints (transaction listing, review queue, decisions, stats) require
 * X-Admin-Api-Key. Only the dashboard's server side holds the key; browsers
 * never see it. Merchant-facing endpoints (POST /authorizations, /cards) are
 * unaffected. If no key is configured, admin endpoints are closed entirely.
 *
 * Not a user login: it proves "this call came from the dashboard". Per-analyst
 * identity (JWT) is a stretch goal; see ADR 0009.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AdminApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Admin-Api-Key";

    private final byte[] expected;

    public AdminApiKeyFilter(@Value("${cardflow.admin.api-key:}") String apiKey) {
        this.expected = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    static boolean isAdminPath(String method, String path) {
        if (path.startsWith("/reviews") || path.equals("/stats")) {
            return true;
        }
        if (path.equals("/authorizations") && HttpMethod.GET.matches(method)) {
            return true; // listing every transaction
        }
        return path.startsWith("/authorizations/") && path.endsWith("/review");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isAdminPath(request.getMethod(), request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        // Constant-time comparison so response timing doesn't leak how much of the key matched
        boolean ok = expected.length > 0 && provided != null
                && MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Missing or invalid admin API key\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
