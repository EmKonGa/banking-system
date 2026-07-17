package com.banking.auth.service;

import com.banking.auth.dto.*;
import com.banking.common.exception.AppException;
import com.banking.common.security.AppProperties;
import com.banking.common.security.JwtService;
import com.banking.common.security.TokenBlacklistService;
import com.banking.user.entity.Role;
import com.banking.user.entity.User;
import com.banking.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
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
    private final TokenBlacklistService tokenBlacklistService;
    private final AppProperties props;

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
        RefreshTokenService.Session session = refreshTokenService.getSession(request.refreshToken())
                .orElseThrow(() -> new AppException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED));

        // Absolute session cap: once a session is older than maxSessionMs, force a
        // full re-login even if the user has been continuously active. Rotation
        // preserves sessionStart, so this can't be reset by refreshing.
        // A cap of 0 (or negative) disables the check — an unbounded session.
        long cap = props.getMaxSessionMs();
        if (cap > 0 && System.currentTimeMillis() - session.sessionStart() > cap) {
            refreshTokenService.delete(request.refreshToken());
            throw new AppException("Session expired. Please log in again.", HttpStatus.UNAUTHORIZED);
        }

        User user = userRepository.findById(UUID.fromString(session.userId()))
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        String newRefreshToken = refreshTokenService.rotate(request.refreshToken(), session);
        String newAccessToken = jwtService.generateAccessToken(user, user.getId().toString());
        return new AuthResponse(newAccessToken, newRefreshToken);
    }

    public void logout(String accessToken, String refreshToken) {
        String jti = jwtService.extractJti(accessToken);
        Date expiration = jwtService.extractExpiration(accessToken);
        long ttlMs = expiration.getTime() - System.currentTimeMillis();
        if (ttlMs > 0) {
            tokenBlacklistService.blacklistToken(jti, Duration.ofMillis(ttlMs));
        }
        refreshTokenService.delete(refreshToken);
    }

    private AuthResponse buildTokenPair(User user) {
        String accessToken = jwtService.generateAccessToken(user, user.getId().toString());
        String refreshToken = refreshTokenService.create(user.getId().toString());
        return new AuthResponse(accessToken, refreshToken);
    }
}
