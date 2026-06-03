package com.microservices.fileservice.dto;

import com.microservices.fileservice.model.FileEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FileResponseDto {

    private Long id;
    private String fileName;
    private String originalFileName;
    private String contentType;
    private Long fileSize;
    private String objectName;
    private String bucketName;
    private String userId;
    private boolean isPublic;
    private Long lessonId;
    private Long courseId;
    private String ragCollectionName;
    private LocalDateTime uploadedAt;
    private LocalDateTime processedAt;
    private FileEntity.FileStatus status;
}
