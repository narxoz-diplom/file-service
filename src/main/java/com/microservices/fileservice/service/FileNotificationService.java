package com.microservices.fileservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileNotificationService {

    private final RabbitTemplate rabbitTemplate;

    public void sendFileProcessingMessage(Long fileId, String objectName) {
        Map<String, Object> message = Map.of(
                "fileId", fileId,
                "objectName", objectName,
                "timestamp", LocalDateTime.now().toString()
        );
        rabbitTemplate.convertAndSend("file.processing.queue", message);
        log.info("File processing message sent for fileId: {}", fileId);
    }

    public void sendUserNotification(String userId, String message) {
        Map<String, Object> notification = Map.of(
                "userId", userId,
                "message", message,
                "type", "FILE_OPERATION",
                "timestamp", LocalDateTime.now().toString()
        );
        rabbitTemplate.convertAndSend("notification.queue", notification);
        log.info("Notification sent to user: {}", userId);
    }
}
