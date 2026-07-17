package com.banking.auth.service;

import com.banking.auth.dto.AuthResponse;
import com.banking.auth.dto.RefreshTokenRequest;
import com.banking.common.exception.AppException;
import com.banking.common.security.AppProperties;
import com.banking.common.security.JwtService;
import com.banking.common.security.TokenBlacklistService;
import com.banking.user.entity.User;
import com.banking.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the absolute session cap enforced in {@link AuthService#refresh}.
 * The cap must fire on session age regardless of activity, and — because rotation
 * preserves the session's start time — it cannot be reset by refreshing.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceSessionCapTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthenticationManager authenticationManager;
    @Mock JwtService jwtService;
    @Mock RefreshTokenService refreshTokenService;
    @Mock TokenBlacklistService tokenBlacklistService;

    AppProperties props;
    AuthService authService;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        props.setMaxSessionMs(Duration.ofHours(8).toMillis());
        authService = new AuthService(userRepository, passwordEncoder, authenticationManager,
                jwtService, refreshTokenService, tokenBlacklistService, props);
    }

    @Test
    void refresh_pastAbsoluteCap_forcesReloginAndRevokesToken() {
        String token = "refresh-token";
        long start = System.currentTimeMillis() - Duration.ofHours(9).toMillis(); // older than 8h cap
        when(refreshTokenService.getSession(token))
                .thenReturn(Optional.of(new RefreshTokenService.Session(UUID.randomUUID().toString(), start)));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(token)))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(refreshTokenService).delete(token);          // old token revoked
        verify(refreshTokenService, never()).rotate(any(), any());
        verifyNoInteractions(jwtService, userRepository);   // no new tokens issued
    }

    @Test
    void refresh_withinCap_rotatesPreservingSessionAndIssuesTokens() {
        String oldToken = "old-refresh";
        String userId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis() - Duration.ofHours(1).toMillis(); // well within cap
        var session = new RefreshTokenService.Session(userId, start);

        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.fromString(userId));
        when(refreshTokenService.getSession(oldToken)).thenReturn(Optional.of(session));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(user));
        when(refreshTokenService.rotate(oldToken, session)).thenReturn("new-refresh");
        when(jwtService.generateAccessToken(user, userId)).thenReturn("new-access");

        AuthResponse res = authService.refresh(new RefreshTokenRequest(oldToken));

        assertThat(res.accessToken()).isEqualTo("new-access");
        assertThat(res.refreshToken()).isEqualTo("new-refresh");
        // Same Session instance handed to rotate() → sessionStart carried forward, cap not reset.
        verify(refreshTokenService).rotate(oldToken, session);
        verify(refreshTokenService, never()).delete(any());
    }

    @Test
    void refresh_capDisabledWithZero_allowsArbitrarilyOldSession() {
        props.setMaxSessionMs(0); // 0 = no absolute cap
        String oldToken = "old-refresh";
        String userId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis() - Duration.ofDays(30).toMillis(); // ancient
        var session = new RefreshTokenService.Session(userId, start);

        User user = mock(User.class);
        when(user.getId()).thenReturn(UUID.fromString(userId));
        when(refreshTokenService.getSession(oldToken)).thenReturn(Optional.of(session));
        when(userRepository.findById(UUID.fromString(userId))).thenReturn(Optional.of(user));
        when(refreshTokenService.rotate(oldToken, session)).thenReturn("new-refresh");
        when(jwtService.generateAccessToken(user, userId)).thenReturn("new-access");

        AuthResponse res = authService.refresh(new RefreshTokenRequest(oldToken));

        assertThat(res.refreshToken()).isEqualTo("new-refresh"); // not rejected despite age
        verify(refreshTokenService, never()).delete(any());
    }

    @Test
    void refresh_unknownToken_throwsUnauthorized() {
        when(refreshTokenService.getSession("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("nope")))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(refreshTokenService, never()).rotate(any(), any());
    }
}
