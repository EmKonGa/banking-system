package com.banking.notification.service;

import com.banking.common.exception.AppException;
import com.banking.notification.dto.NotificationResponse;
import com.banking.notification.entity.Notification;
import com.banking.notification.entity.NotificationType;
import com.banking.notification.repository.NotificationRepository;
import com.banking.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public void create(UUID userId, String message, NotificationType type) {
        Notification saved = notificationRepository.save(Notification.builder()
                .userId(userId)
                .message(message)
                .type(type)
                .build());
        NotificationResponse response = NotificationResponse.from(saved);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingTemplate.convertAndSendToUser(
                        userId.toString(), "/queue/notifications", response);
            }
        });
    }

    public List<NotificationResponse> myNotifications() {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(currentUser().getId())
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Transactional
    public NotificationResponse markRead(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new AppException("Notification not found", HttpStatus.NOT_FOUND));
        if (!notification.getUserId().equals(currentUser().getId())) {
            throw new AppException("Notification not found", HttpStatus.NOT_FOUND);
        }
        notification.setRead(true);
        return NotificationResponse.from(notification);
    }

    @Transactional
    public void markAllRead() {
        notificationRepository.markAllReadByUserId(currentUser().getId());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
