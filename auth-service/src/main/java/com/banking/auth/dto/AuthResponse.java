package com.banking.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken
) {}
