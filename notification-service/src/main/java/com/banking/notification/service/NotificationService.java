package com.banking.notification.service;

import com.banking.common.exception.AppException;
import com.banking.common.security.JwtPrincipal;
import com.banking.notification.dto.NotificationResponse;
import com.banking.notification.entity.Notification;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPushService notificationPushService;

    public void create(UUID userId, String message, NotificationType type) {
        Notification saved = notificationRepository.save(Notification.builder()
                .userId(userId)
                .message(message)
                .type(type)
                .build());
        NotificationResponse response = NotificationResponse.from(saved);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notificationPushService.push(userId, response);
                }
            });
        } else {
            notificationPushService.push(userId, response);
        }
    }

    @Transactional(readOnly = true)
    public Slice<NotificationResponse> myNotifications(Pageable pageable) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(currentUserId(), pageable)
                .map(NotificationResponse::from);
    }

    @Transactional
    public NotificationResponse markRead(UUID id) {
        UUID userId = currentUserId();
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new AppException("Notification not found", HttpStatus.NOT_FOUND));
        if (!notification.getUserId().equals(userId)) {
            throw new AppException("Notification not found", HttpStatus.NOT_FOUND);
        }
        notification.setRead(true);
        return NotificationResponse.from(notification);
    }

    @Transactional
    public void markAllRead() {
        notificationRepository.markAllReadByUserId(currentUserId());
    }

    private UUID currentUserId() {
        JwtPrincipal principal = (JwtPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal.id();
    }
}
