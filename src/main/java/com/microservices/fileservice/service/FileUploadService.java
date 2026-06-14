package com.microservices.fileservice.service;

import com.microservices.fileservice.dto.FileUploadContext;
import com.microservices.fileservice.exception.FileStorageException;
import com.microservices.fileservice.mapper.FileMapper;
import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileUploadService {

    private final FileRepository fileRepository;
    private final MinioService minioService;
    private final FileMapper fileMapper;
    private final FileNotificationService fileNotificationService;
    private final RagSyncService ragSyncService;

    @Transactional
    public FileEntity uploadFile(MultipartFile file, String userId) throws IOException {
        log.info("Uploading file: {} for user: {}", file.getOriginalFilename(), userId);
        FileEntity saved = persistUpload(file, userId, false, null, null, null);
        fileNotificationService.sendFileProcessingMessage(saved.getId(), saved.getObjectName());
        ragSyncService.ingestMultipartSafely(file, saved.getId(), null);
        fileNotificationService.sendUserNotification(userId, "File uploaded successfully: " + file.getOriginalFilename());
        return saved;
    }

    @Transactional
    public FileEntity uploadPublicNewsImage(MultipartFile file, String userId) throws IOException {
        validateNewsImage(file);
        FileEntity saved = persistUpload(file, userId, true, null, null, null);
        fileNotificationService.sendUserNotification(userId, "News image uploaded: " + file.getOriginalFilename());
        return saved;
    }

    @Transactional
    public FileEntity uploadAvatar(MultipartFile file, String userId) throws IOException {
        validateNewsImage(file);
        String folderPrefix = "avatars/" + userId;
        FileEntity saved = persistUpload(file, userId, true, null, null, null, folderPrefix);
        log.info("Avatar uploaded for user {}: {}", userId, saved.getObjectName());
        return saved;
    }

    @Transactional
    public FileEntity uploadFileToLesson(MultipartFile file, String userId, Long lessonId) throws IOException {
        log.info("Uploading file: {} for lesson: {} by user: {}", file.getOriginalFilename(), lessonId, userId);
        FileEntity saved = persistUpload(file, userId, false, lessonId, null, null);
        fileNotificationService.sendFileProcessingMessage(saved.getId(), saved.getObjectName());
        ragSyncService.ingestMultipartSafely(file, saved.getId(), lessonId);
        fileNotificationService.sendUserNotification(userId, "File uploaded to lesson: " + file.getOriginalFilename());
        return saved;
    }

    @Transactional
    public FileEntity uploadFileToCourse(MultipartFile file, String userId, Long courseId) throws Exception {
        log.info("Uploading file: {} for course: {} by user: {}", file.getOriginalFilename(), courseId, userId);
        String collectionName = "course_" + courseId;
        FileEntity saved = persistUpload(file, userId, false, null, courseId, collectionName);
        fileNotificationService.sendUserNotification(userId, "File uploaded to course: " + file.getOriginalFilename());
        ragSyncService.ingestCourseFileSafely(file, collectionName, courseId, saved.getId());
        return saved;
    }

    private FileEntity persistUpload(
            MultipartFile file,
            String userId,
            boolean isPublic,
            Long lessonId,
            Long courseId,
            String ragCollectionName
    ) throws IOException {
        return persistUpload(file, userId, isPublic, lessonId, courseId, ragCollectionName, null);
    }

    private FileEntity persistUpload(
            MultipartFile file,
            String userId,
            boolean isPublic,
            Long lessonId,
            Long courseId,
            String ragCollectionName,
            String folderPrefix
    ) throws IOException {
        String objectName = uploadToMinio(file, folderPrefix);
        FileEntity entity = fileMapper.fromUploadContext(FileUploadContext.builder()
                .originalFileName(file.getOriginalFilename())
                .contentType(file.getContentType())
                .fileSize(file.getSize())
                .objectName(objectName)
                .userId(userId)
                .isPublic(isPublic)
                .lessonId(lessonId)
                .courseId(courseId)
                .ragCollectionName(ragCollectionName)
                .build());
        entity.setPublic(isPublic);
        return fileRepository.save(entity);
    }

    private String uploadToMinio(MultipartFile file) throws IOException {
        return uploadToMinio(file, null);
    }

    private String uploadToMinio(MultipartFile file, String folderPrefix) throws IOException {
        try {
            return minioService.uploadFile(file, folderPrefix);
        } catch (Exception e) {
            log.error("Error uploading file to MinIO", e);
            throw new FileStorageException("Failed to upload file to storage", e);
        }
    }

    private static void validateNewsImage(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IOException("File is required");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new IOException("Only image/* files are allowed");
        }
    }
}
