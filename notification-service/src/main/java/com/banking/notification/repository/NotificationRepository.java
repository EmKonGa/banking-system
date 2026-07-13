package com.banking.notification.repository;

import com.banking.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.userId = :userId AND n.read = false")
    void markAllReadByUserId(UUID userId);
}
