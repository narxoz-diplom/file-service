package com.microservices.fileservice.service;

import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    private final FileRepository fileRepository;
    private final MinioService minioService;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public FileEntity uploadFile(MultipartFile file, String userId) throws IOException {
        log.info("Uploading file: {} for user: {}", file.getOriginalFilename(), userId);
        
        String objectName;
        try {
            objectName = minioService.uploadFile(file);
        } catch (Exception e) {
            log.error("Error uploading file to MinIO", e);
            throw new IOException("Failed to upload file to storage", e);
        }
        
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(file.getOriginalFilename());
        fileEntity.setOriginalFileName(file.getOriginalFilename());
        fileEntity.setContentType(file.getContentType());
        fileEntity.setFileSize(file.getSize());
        fileEntity.setObjectName(objectName);
        fileEntity.setBucketName("files");
        fileEntity.setUserId(userId);
        fileEntity.setUploadedAt(LocalDateTime.now());
        fileEntity.setStatus(FileEntity.FileStatus.UPLOADED);
        
        FileEntity saved = fileRepository.save(fileEntity);
        
        // Send message to RabbitMQ for processing
        sendFileProcessingMessage(saved.getId(), objectName);
        
        // Send notification message
        sendNotificationMessage(userId, "File uploaded successfully: " + file.getOriginalFilename());
        
        return saved;
    }

    // Не используем кеширование для FileEntity, так как он может содержать lazy-связанные сущности (lesson)
    public FileEntity getFileById(Long id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + id));
    }

    @Transactional
    public FileEntity uploadFileToLesson(MultipartFile file, String userId, Long lessonId) throws IOException {
        log.info("Uploading file: {} for lesson: {} by user: {}", file.getOriginalFilename(), lessonId, userId);
        
        String objectName;
        try {
            objectName = minioService.uploadFile(file);
        } catch (Exception e) {
            log.error("Error uploading file to MinIO", e);
            throw new IOException("Failed to upload file to storage", e);
        }
        
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(file.getOriginalFilename());
        fileEntity.setOriginalFileName(file.getOriginalFilename());
        fileEntity.setContentType(file.getContentType());
        fileEntity.setFileSize(file.getSize());
        fileEntity.setObjectName(objectName);
        fileEntity.setBucketName("files");
        fileEntity.setUserId(userId);
        fileEntity.setLessonId(lessonId); // Привязываем файл к уроку через ID
        fileEntity.setUploadedAt(LocalDateTime.now());
        fileEntity.setStatus(FileEntity.FileStatus.UPLOADED);
        
        FileEntity saved = fileRepository.save(fileEntity);
        
        // Send message to RabbitMQ for processing
        sendFileProcessingMessage(saved.getId(), objectName);
        
        // Send notification message
        sendNotificationMessage(userId, "File uploaded to lesson: " + file.getOriginalFilename());
        
        return saved;
    }

    // Не используем кеширование для списков файлов, так как FileEntity содержит lazy-связанные сущности
    public List<FileEntity> getFilesByUserId(String userId) {
        return fileRepository.findByUserId(userId);
    }

    public List<FileEntity> getFilesByLessonId(Long lessonId) {
        return fileRepository.findByLessonId(lessonId);
    }

    public List<FileEntity> getAllFiles() {
        return fileRepository.findAll();
    }

    public InputStream downloadFile(Long id) throws Exception {
        FileEntity fileEntity = getFileById(id);
        try {
            return minioService.downloadFile(fileEntity.getObjectName());
        } catch (Exception e) {
            log.error("Error downloading file from MinIO", e);
            throw new Exception("Failed to download file from storage", e);
        }
    }

    @Transactional
    public void deleteFile(Long id, String userId) throws Exception {
        FileEntity fileEntity = getFileById(id);
        
        if (!fileEntity.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized to delete this file");
        }
        
        try {
            minioService.deleteFile(fileEntity.getObjectName());
        } catch (Exception e) {
            log.error("Error deleting file from MinIO", e);
            throw new Exception("Failed to delete file from storage", e);
        }
        fileRepository.deleteById(id);
        
        sendNotificationMessage(userId, "File deleted: " + fileEntity.getOriginalFileName());
    }

    private void sendFileProcessingMessage(Long fileId, String objectName) {
        Map<String, Object> message = Map.of(
            "fileId", fileId,
            "objectName", objectName,
            "timestamp", LocalDateTime.now().toString()
        );
        rabbitTemplate.convertAndSend("file.processing.queue", message);
        log.info("File processing message sent for fileId: {}", fileId);
    }

    private void sendNotificationMessage(String userId, String message) {
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

