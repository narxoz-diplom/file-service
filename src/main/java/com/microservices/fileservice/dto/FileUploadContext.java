package com.microservices.fileservice.dto;

import lombok.Builder;

@Builder
public record FileUploadContext(
        String originalFileName,
        String contentType,
        long fileSize,
        String objectName,
        String userId,
        boolean isPublic,
        Long lessonId,
        Long courseId,
        String ragCollectionName
) {
}
