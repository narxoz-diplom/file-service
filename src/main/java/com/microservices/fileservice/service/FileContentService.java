package com.microservices.fileservice.service;

import com.microservices.fileservice.exception.FileStorageException;
import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.util.ContentTypeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileContentService {

    private final MinioService minioService;

    public InputStream openDownloadStream(FileEntity file) {
        try {
            return minioService.downloadFile(file.getObjectName());
        } catch (Exception e) {
            log.error("Error downloading file from MinIO", e);
            throw new FileStorageException("Failed to download file from storage", e);
        }
    }

    public ResponseEntity<InputStreamResource> buildAttachmentResponse(FileEntity file, InputStream inputStream) {
        String contentType = ContentTypeResolver.resolveForFile(file);
        String encodedFileName = URLEncoder.encode(file.getOriginalFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + file.getOriginalFileName() + "\"; filename*=UTF-8''" + encodedFileName);
        headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.getFileSize()));
        headers.add(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        headers.add(HttpHeaders.PRAGMA, "no-cache");
        headers.add(HttpHeaders.EXPIRES, "0");

        InputStreamResource resource = new InputStreamResource(inputStream) {
            @Override
            public long contentLength() {
                return file.getFileSize();
            }
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(file.getFileSize())
                .body(resource);
    }

    public ResponseEntity<InputStreamResource> buildInlineContentResponse(FileEntity file, InputStream inputStream) {
        String contentType = ContentTypeResolver.resolveForFile(file);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CACHE_CONTROL, "public, max-age=300");
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getOriginalFileName() + "\"");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(file.getFileSize())
                .body(new InputStreamResource(inputStream));
    }
}
