package com.banking.auth.service;

import com.banking.auth.dto.*;
import com.banking.common.exception.AppException;
import com.banking.user.entity.Role;
import com.banking.user.entity.User;
import com.banking.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final StringRedisTemplate redis;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new AppException("Email already registered", HttpStatus.CONFLICT);
        }
        User user = User.builder()
                .fullName(request.fullName())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .role(Role.USER)
                .build();
        userRepository.save(user);
        return buildTokenPair(user);
    }

    public AuthResponse login(LoginRequest request) {
        var auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );
        return buildTokenPair((User) auth.getPrincipal());
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        String userId = refreshTokenService.getUserId(request.refreshToken())
                .orElseThrow(() -> new AppException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED));

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        // Rotate: old token is deleted, new one is issued
        String newRefreshToken = refreshTokenService.rotate(request.refreshToken(), userId);
        String newAccessToken = jwtService.generateAccessToken(user);
        return new AuthResponse(newAccessToken, newRefreshToken);
    }

    public void logout(String accessToken, String refreshToken) {
        // Blacklist the access token for its remaining lifetime
        String jti = jwtService.extractJti(accessToken);
        Date expiration = jwtService.extractExpiration(accessToken);
        long ttlMs = expiration.getTime() - System.currentTimeMillis();
        if (ttlMs > 0) {
            redis.opsForValue().set("blacklist:" + jti, "true", Duration.ofMillis(ttlMs));
        }
        // Invalidate the refresh token
        refreshTokenService.delete(refreshToken);
    }

    private AuthResponse buildTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = refreshTokenService.create(user.getId().toString());
        return new AuthResponse(accessToken, refreshToken);
    }
}