package com.banking.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Protects /internal/** endpoints by validating the X-Internal-Secret header.
 * Register via WebMvcConfigurer.addInterceptors() in each service that exposes
 * internal endpoints, restricted to /internal/** path patterns.
 */
@Component
public class InternalSecretInterceptor implements HandlerInterceptor {

    @Value("${internal.secret:internal-secret-change-me}")
    private String internalSecret;

    @Override
    public boolean preHandle(HttpServletRequest request,
                              HttpServletResponse response,
                              Object handler) throws Exception {
        String header = request.getHeader("X-Internal-Secret");
        if (!internalSecret.equals(header)) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Forbidden");
            return false;
        }
        return true;
    }
}
