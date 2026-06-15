package com.microservices.fileservice.service;

import com.microservices.fileservice.dto.FileUploadContext;
import com.microservices.fileservice.dto.VideoUploadResponseDto;
import com.microservices.fileservice.mapper.FileMapper;
import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.repository.FileRepository;
import com.microservices.fileservice.util.ContentTypeResolver;
import io.minio.StatObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
@Slf4j
public class VideoStreamingService {

    private final MinioService minioService;
    private final FileMapper fileMapper;
    private final RagSyncService ragSyncService;
    private final FileRepository fileRepository;

    public VideoUploadResponseDto uploadVideo(MultipartFile file, Long lessonId, String userId) throws Exception {
        String objectName = minioService.uploadFile(file);
        fileRepository.save(fileMapper.fromUploadContext(FileUploadContext.builder()
                .originalFileName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "video")
                .contentType(file.getContentType() != null ? file.getContentType() : "video/mp4")
                .fileSize(file.getSize())
                .objectName(objectName)
                .userId(userId)
                .isPublic(false)
                .lessonId(lessonId)
                .ragCollectionName(lessonId != null ? "lesson_" + lessonId : null)
                .build()));
        try {
            ragSyncService.ingestVideoSafely(file, lessonId);
        } catch (Exception e) {
            log.warn("RAG ingest failed for uploaded video {} (lesson {})", objectName, lessonId, e);
        }
        return fileMapper.toVideoUploadResponse(objectName, file);
    }

    public boolean storageObjectExists(String objectName) {
        try {
            minioService.getFileInfo(decodeObjectName(objectName));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public ResponseEntity<InputStreamResource> streamVideo(String objectName, String rangeHeader) throws Exception {
        String decodedObjectName = decodeObjectName(objectName);
        StatObjectResponse statObject = minioService.getFileInfo(decodedObjectName);
        long fileSize = statObject.size();
        String contentType = ContentTypeResolver.resolveForVideo(objectName, statObject.contentType());

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            return buildRangeResponse(decodedObjectName, rangeHeader, fileSize, contentType);
        }

        InputStream inputStream = minioService.downloadFile(decodedObjectName);
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", String.valueOf(fileSize))
                .body(new InputStreamResource(inputStream));
    }

    private ResponseEntity<InputStreamResource> buildRangeResponse(
            String objectName,
            String rangeHeader,
            long fileSize,
            String contentType
    ) throws Exception {
        String[] ranges = rangeHeader.substring(6).split("-");
        long rangeStart = Long.parseLong(ranges[0]);
        long rangeEnd = ranges.length > 1 && !ranges[1].isEmpty()
                ? Long.parseLong(ranges[1])
                : fileSize - 1;

        if (rangeStart < 0 || rangeEnd >= fileSize || rangeStart > rangeEnd) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header("Content-Range", "bytes */" + fileSize)
                    .build();
        }

        long contentLength = rangeEnd - rangeStart + 1;
        InputStream inputStream = minioService.downloadFile(objectName, rangeStart, contentLength);

        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header("Content-Type", contentType)
                .header("Accept-Ranges", "bytes")
                .header("Content-Length", String.valueOf(contentLength))
                .header("Content-Range", String.format("bytes %d-%d/%d", rangeStart, rangeEnd, fileSize))
                .body(new InputStreamResource(inputStream));
    }

    private static String decodeObjectName(String objectName) {
        try {
            String decoded = URLDecoder.decode(objectName, StandardCharsets.UTF_8);
            if (!decoded.equals(objectName) && !decoded.contains("%")) {
                return decoded;
            }
        } catch (Exception ignored) {
            // use original object name
        }
        return objectName;
    }
}
