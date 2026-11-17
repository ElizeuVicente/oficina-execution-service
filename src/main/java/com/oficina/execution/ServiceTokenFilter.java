package com.oficina.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Shared credential for the local service integration; TLS is required outside a trusted network. */
@Component
class ServiceTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final Set<UUID> allowedBranches;
    @org.springframework.beans.factory.annotation.Autowired
    ServiceTokenFilter(@Value("${execution.service-token}") String token,
            @Value("${execution.allowed-branches:}") String branches) {
        if (token.isBlank() || token.length() < 32) throw new IllegalArgumentException("execution.service-token must have at least 32 characters");
        expected = token.getBytes(StandardCharsets.UTF_8);
        allowedBranches = branches.isBlank() ? Set.of() : Arrays.stream(branches.split(",", -1))
                .map(String::trim).map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    ServiceTokenFilter(String token) { this(token, ""); }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader("X-Service-Token");
        if (token == null || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        if (!allowedBranches.isEmpty()) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            String prefix = "/api/branches/";
            if (!path.startsWith(prefix)) { response.sendError(HttpServletResponse.SC_FORBIDDEN); return; }
            String id = path.substring(prefix.length()).split("/", 2)[0];
            try {
                UUID branch = UUID.fromString(id);
                if (!branch.toString().equalsIgnoreCase(id) || !allowedBranches.contains(branch)) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN); return;
                }
            } catch (IllegalArgumentException invalid) { response.sendError(HttpServletResponse.SC_FORBIDDEN); return; }
        }
        chain.doFilter(request, response);
    }
}
