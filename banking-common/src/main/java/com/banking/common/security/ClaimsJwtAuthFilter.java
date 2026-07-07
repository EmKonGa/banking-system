package com.banking.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * JWT filter that authenticates from token claims only — no DB lookup.
 * Used by downstream services (account, payment, notification) that do not
 * have access to the users table. The full User entity is replaced by JwtPrincipal.
 */
public class ClaimsJwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    public ClaimsJwtAuthFilter(JwtService jwtService, TokenBlacklistService tokenBlacklistService) {
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String token = authHeader.substring(7);
        try {
            String jti = jwtService.extractJti(token);
            if (tokenBlacklistService.isBlacklisted(jti)) {
                chain.doFilter(request, response);
                return;
            }
            String email = jwtService.extractUsername(token);
            if (email != null && !jwtService.isTokenExpired(token)
                    && SecurityContextHolder.getContext().getAuthentication() == null) {
                String role = jwtService.extractRole(token);
                String userIdStr = jwtService.extractUserId(token);
                UUID userId = userIdStr != null ? UUID.fromString(userIdStr) : null;
                var principal = new JwtPrincipal(userId, email, role != null ? role : "USER");
                var authToken = new UsernamePasswordAuthenticationToken(
                        principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + (role != null ? role : "USER"))));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        } catch (Exception ignored) {}
        chain.doFilter(request, response);
    }
}
