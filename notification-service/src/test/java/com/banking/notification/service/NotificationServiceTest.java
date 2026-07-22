package com.banking.notification.service;

import com.banking.common.exception.AppException;
import com.banking.common.security.JwtPrincipal;
import com.banking.notification.dto.NotificationResponse;
import com.banking.notification.entity.Notification;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The inbox is per-user and every endpoint derives its user from the token rather than a
 * parameter. These tests pin that down — a notification can name a counterparty and an amount, so
 * reading someone else's is a real disclosure, not just an ownership technicality.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock NotificationPushService notificationPushService;

    @InjectMocks NotificationService notificationService;

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID INTRUDER = UUID.randomUUID();

    @BeforeEach
    void authenticate() {
        authenticateAs(OWNER);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtPrincipal(userId, userId + "@example.com", "USER"), null, List.of()));
    }

    private Notification notification(UUID id, UUID userId, boolean read) {
        return Notification.builder()
                .id(id).userId(userId).message("You received 25.00")
                .type(NotificationType.PAYMENT_RECEIVED).read(read)
                .build();
    }

    /**
     * The id comes straight from the URL, so without the ownership check any authenticated user
     * could mark — and by reading the response, see — another user's notification. It answers 404
     * rather than 403 so the response cannot be used to probe which ids exist.
     */
    @Test
    void markingAnotherUsersNotificationIsNotFound() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification(id, OWNER, false)));
        authenticateAs(INTRUDER);

        assertThatThrownBy(() -> notificationService.markRead(id))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("Notification not found")
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** A missing id gives the same answer as someone else's, for the same reason. */
    @Test
    void markingAMissingNotificationIsNotFound() {
        UUID id = UUID.randomUUID();
        when(notificationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(id))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The owner's own notification flips to read; the entity is dirty-checked, not re-saved. */
    @Test
    void ownerMarksTheirOwnNotificationRead() {
        UUID id = UUID.randomUUID();
        Notification n = notification(id, OWNER, false);
        when(notificationRepository.findById(id)).thenReturn(Optional.of(n));

        NotificationResponse response = notificationService.markRead(id);

        assertThat(n.isRead()).isTrue();
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.read()).isTrue();
    }

    /** The listing takes no user parameter — it must resolve the caller from the token. */
    @Test
    void inboxIsScopedToTheAuthenticatedUser() {
        Pageable pageable = PageRequest.of(0, 20);
        UUID id = UUID.randomUUID();
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(OWNER, pageable))
                .thenReturn(new SliceImpl<>(List.of(notification(id, OWNER, false)), pageable, false));

        Slice<NotificationResponse> result = notificationService.myNotifications(pageable);

        assertThat(result.getContent()).extracting(NotificationResponse::id).containsExactly(id);
        verify(notificationRepository).findByUserIdOrderByCreatedAtDesc(OWNER, pageable);
    }

    @Test
    void markAllReadAppliesOnlyToTheAuthenticatedUser() {
        notificationService.markAllRead();

        verify(notificationRepository).markAllReadByUserId(OWNER);
    }

    /**
     * create() is called by the Kafka consumer, which is not inside a Spring transaction. The
     * else-branch has to push directly — registering a synchronization there would silently drop
     * the WebSocket message, and the user would see nothing until they reloaded.
     */
    @Test
    void createPushesImmediatelyWhenThereIsNoTransaction() {
        UUID recipient = UUID.randomUUID();
        when(notificationRepository.save(org.mockito.ArgumentMatchers.any(Notification.class)))
                .thenAnswer(inv -> {
                    Notification n = inv.getArgument(0);
                    n.setId(UUID.randomUUID());
                    return n;
                });

        notificationService.create(recipient, "You received 25.00", NotificationType.PAYMENT_RECEIVED);

        ArgumentCaptor<NotificationResponse> pushed = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(notificationPushService).push(org.mockito.ArgumentMatchers.eq(recipient), pushed.capture());
        assertThat(pushed.getValue().message()).isEqualTo("You received 25.00");
        assertThat(pushed.getValue().read()).isFalse();
    }

    /** A read-only listing must not touch the push channel. */
    @Test
    void readingTheInboxDoesNotPush() {
        Pageable pageable = PageRequest.of(0, 20);
        when(notificationRepository.findByUserIdOrderByCreatedAtDesc(OWNER, pageable))
                .thenReturn(new SliceImpl<>(List.of(), pageable, false));

        notificationService.myNotifications(pageable);

        verifyNoInteractions(notificationPushService);
    }
}
