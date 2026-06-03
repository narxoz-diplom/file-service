package com.microservices.fileservice.service;

import com.microservices.fileservice.client.RagIngestClient;
import com.microservices.fileservice.dto.FileRagSyncResultDto;
import com.microservices.fileservice.dto.RagSyncResultDto;
import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagSyncService {

    private final FileRepository fileRepository;
    private final MinioService minioService;
    private final RagIngestClient ragIngestClient;

    public FileRagSyncResultDto syncFile(Long fileId) throws Exception {
        FileEntity fileEntity = fileRepository.findById(fileId)
                .orElseThrow(() -> new com.microservices.fileservice.exception.FileNotFoundException(fileId));
        if (!ragIngestClient.isEnabled()) {
            log.warn("RAG service not configured, cannot sync file {}", fileId);
            return FileRagSyncResultDto.builder()
                    .fileId(fileId)
                    .synced(false)
                    .message("RAG sync failed or RAG service not configured")
                    .build();
        }
        if (fileEntity.getCourseId() == null) {
            log.warn("File {} is not associated with a course, will sync to default collection", fileId);
        }
        boolean success = syncEntityToRag(fileEntity);
        return FileRagSyncResultDto.builder()
                .fileId(fileId)
                .synced(success)
                .message(success ? "File synced to RAG (ChromaDB)" : "RAG sync failed or RAG service not configured")
                .build();
    }

    public RagSyncResultDto syncCourseFiles(Long courseId) throws Exception {
        List<FileEntity> files = fileRepository.findByCourseId(courseId);
        int synced = 0;
        int failed = 0;
        for (FileEntity file : files) {
            try {
                if (syncEntityToRag(file)) {
                    synced++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.warn("Failed to sync file {} to RAG: {}", file.getId(), e.getMessage());
                failed++;
            }
        }
        return RagSyncResultDto.builder()
                .courseId(courseId)
                .total(files.size())
                .synced(synced)
                .failed(failed)
                .build();
    }

    boolean syncEntityToRag(FileEntity fileEntity) throws Exception {
        byte[] content = readObjectBytes(fileEntity.getObjectName());
        String collectionName = resolveCollectionName(fileEntity);
        Map<String, Object> meta = buildSyncMetadata(fileEntity);
        return ragIngestClient.ingestFromBytes(
                content,
                fileEntity.getOriginalFileName(),
                collectionName,
                meta
        );
    }

    void ingestMultipartSafely(
            org.springframework.web.multipart.MultipartFile file,
            Long fileId,
            Long lessonId
    ) {
        if (!ragIngestClient.isEnabled()) {
            return;
        }
        try {
            ragIngestClient.ingest(file, fileId, lessonId);
        } catch (Exception e) {
            log.warn("RAG ingest failed for file {}: {}", file.getOriginalFilename(), e.getMessage());
        }
    }

    void ingestCourseFileSafely(
            org.springframework.web.multipart.MultipartFile file,
            String collectionName,
            Long courseId,
            Long fileId
    ) {
        if (!ragIngestClient.isEnabled()) {
            return;
        }
        try {
            ragIngestClient.ingestForCourse(file, collectionName, courseId, fileId);
        } catch (Exception e) {
            log.warn("RAG ingest failed for course file {}: {}", file.getOriginalFilename(), e.getMessage());
        }
    }

    void ingestUrlBytesSafely(byte[] content, String filename, String collectionName, Map<String, Object> meta) {
        if (!ragIngestClient.isEnabled()) {
            return;
        }
        try {
            ragIngestClient.ingestFromBytes(content, filename, collectionName, meta);
        } catch (Exception e) {
            log.warn("RAG ingest failed for URL file {}: {}", filename, e.getMessage());
        }
    }

    void ingestVideoSafely(org.springframework.web.multipart.MultipartFile file, Long lessonId) {
        if (!ragIngestClient.isEnabled()) {
            return;
        }
        try {
            ragIngestClient.ingest(file, null, lessonId);
        } catch (Exception e) {
            log.warn("RAG ingest failed for video {}: {}", file.getOriginalFilename(), e.getMessage());
        }
    }

    private byte[] readObjectBytes(String objectName) throws Exception {
        try (InputStream is = minioService.downloadFile(objectName);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            return bytes.toByteArray();
        }
    }

    private static String resolveCollectionName(FileEntity fileEntity) {
        if (fileEntity.getRagCollectionName() != null) {
            return fileEntity.getRagCollectionName();
        }
        if (fileEntity.getCourseId() != null) {
            return "course_" + fileEntity.getCourseId();
        }
        if (fileEntity.getLessonId() != null) {
            return "lesson_" + fileEntity.getLessonId();
        }
        return "default";
    }

    private static Map<String, Object> buildSyncMetadata(FileEntity fileEntity) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("file_id", String.valueOf(fileEntity.getId()));
        meta.put("source", "file-service-sync");
        if (fileEntity.getCourseId() != null) {
            meta.put("course_id", String.valueOf(fileEntity.getCourseId()));
        }
        if (fileEntity.getLessonId() != null) {
            meta.put("lesson_id", String.valueOf(fileEntity.getLessonId()));
        }
        return meta;
    }
}
