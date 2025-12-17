package com.microservices.fileservice.controller;

import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.service.FileService;
import com.microservices.fileservice.service.MinioService;
import com.microservices.fileservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {

    private final FileService fileService;
    private final MinioService minioService;

    @PostMapping("/upload")
    public ResponseEntity<FileEntity> uploadFile(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only ADMIN and TEACHER roles can upload files");
        }
        try {
            String userId = jwt.getSubject();
            FileEntity fileEntity = fileService.uploadFile(file, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(fileEntity);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/upload-to-lesson")
    public ResponseEntity<FileEntity> uploadFileToLesson(
            @RequestParam("file") MultipartFile file,
            @RequestParam("lessonId") Long lessonId,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only ADMIN and TEACHER roles can upload files to lessons");
        }
        try {
            String userId = jwt.getSubject();
            FileEntity fileEntity = fileService.uploadFileToLesson(file, userId, lessonId);
            return ResponseEntity.status(HttpStatus.CREATED).body(fileEntity);
        } catch (Exception e) {
            log.error("Error uploading file to lesson: {}", lessonId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping
    public ResponseEntity<List<FileEntity>> getUserFiles(@AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canView(jwt)) {
            throw new AccessDeniedException("Access denied");
        }
        String userId = jwt.getSubject();
        List<FileEntity> files;
        
        if (RoleUtil.isAdmin(jwt)) {
            files = fileService.getAllFiles();
        } else {
            files = fileService.getFilesByUserId(userId);
        }
        return ResponseEntity.ok(files);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileEntity> getFile(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canView(jwt)) {
            throw new AccessDeniedException("Access denied");
        }
        FileEntity file = fileService.getFileById(id);
        
        if (!RoleUtil.isAdmin(jwt) && !file.getUserId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("You can only view your own files");
        }
        
        return ResponseEntity.ok(file);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> downloadFile(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canView(jwt)) {
            throw new AccessDeniedException("Access denied");
        }
        try {
            FileEntity file = fileService.getFileById(id);
            String userId = jwt.getSubject();

            boolean canDownload = false;
            
            if (RoleUtil.isAdmin(jwt)) {
                canDownload = true;
            } else if (file.getUserId().equals(userId)) {
                canDownload = true;
            }
            
            if (!canDownload) {
                throw new AccessDeniedException("You don't have permission to download this file");
            }
            
            InputStream inputStream = fileService.downloadFile(id);
            
            String contentType = file.getContentType();
            if (contentType == null || contentType.isEmpty()) {
                String fileName = file.getOriginalFileName().toLowerCase();
                if (fileName.endsWith(".pdf")) {
                    contentType = "application/pdf";
                } else if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
                    contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    if (fileName.endsWith(".doc")) {
                        contentType = "application/msword";
                    }
                } else if (fileName.endsWith(".xls") || fileName.endsWith(".xlsx")) {
                    contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    if (fileName.endsWith(".xls")) {
                        contentType = "application/vnd.ms-excel";
                    }
                } else if (fileName.endsWith(".zip")) {
                    contentType = "application/zip";
                } else if (fileName.endsWith(".txt")) {
                    contentType = "text/plain";
                } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                    contentType = "image/jpeg";
                } else if (fileName.endsWith(".png")) {
                    contentType = "image/png";
                } else {
                    contentType = "application/octet-stream";
                }
            }
            
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
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error downloading file: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<FileEntity> updateFile(
            @PathVariable Long id,
            @RequestBody Map<String, String> updateRequest,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only ADMIN and TEACHER roles can update files");
        }
        try {
            String userId = jwt.getSubject();
            String newFileName = updateRequest.get("originalFileName");
            FileEntity updated = fileService.updateFile(id, userId, newFileName);
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            log.error("Error updating file: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only ADMIN and TEACHER roles can delete files");
        }
        try {
            String userId = jwt.getSubject();
            fileService.deleteFile(id, userId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Error deleting file: {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/lesson/{lessonId}")
    public ResponseEntity<List<FileEntity>> getFilesByLessonId(
            @PathVariable Long lessonId,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canView(jwt)) {
            throw new AccessDeniedException("Access denied");
        }
        try {
            List<FileEntity> files = fileService.getFilesByLessonId(lessonId);
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            log.error("Error getting files for lesson: {}", lessonId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/upload-video")
    public ResponseEntity<Map<String, Object>> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "lessonId", required = false) Long lessonId,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only ADMIN and TEACHER roles can upload videos");
        }
        try {
            String userId = jwt.getSubject();
            String objectName = minioService.uploadFile(file);
            String videoUrl = "/api/files/videos/" + objectName + "/stream";
            
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("objectName", objectName);
            response.put("videoUrl", videoUrl);
            response.put("fileSize", file.getSize());
            response.put("contentType", file.getContentType());
            response.put("originalFileName", file.getOriginalFilename());
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Error uploading video", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/videos/{objectName}/stream")
    public ResponseEntity<InputStreamResource> streamVideo(
            @PathVariable String objectName,
            @RequestHeader(value = "Range", required = false) String rangeHeader) {
        try {
            String decodedObjectName = objectName;
            try {
                String testDecode = java.net.URLDecoder.decode(objectName, java.nio.charset.StandardCharsets.UTF_8);
                if (!testDecode.equals(objectName) && !testDecode.contains("%")) {
                    decodedObjectName = testDecode;
                }
            } catch (Exception e) {
                log.debug("Could not decode objectName, using original: {}", objectName);
            }
            
            io.minio.StatObjectResponse statObject = minioService.getFileInfo(decodedObjectName);
            long fileSize = statObject.size();
            
            String contentType = statObject.contentType();
            if (contentType == null || contentType.isEmpty()) {
                String fileName = objectName.toLowerCase();
                if (fileName.endsWith(".mp4")) {
                    contentType = "video/mp4";
                } else if (fileName.endsWith(".webm")) {
                    contentType = "video/webm";
                } else if (fileName.endsWith(".ogg")) {
                    contentType = "video/ogg";
                } else {
                    contentType = "video/mp4";
                }
            }
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
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
                InputStream inputStream = minioService.downloadFile(decodedObjectName, rangeStart, contentLength);
                
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header("Content-Type", contentType)
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Length", String.valueOf(contentLength))
                        .header("Content-Range", 
                            String.format("bytes %d-%d/%d", rangeStart, rangeEnd, fileSize))
                        .body(new org.springframework.core.io.InputStreamResource(inputStream));
            } else {
                InputStream inputStream = minioService.downloadFile(decodedObjectName);
                return ResponseEntity.ok()
                        .header("Content-Type", contentType)
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Length", String.valueOf(fileSize))
                        .body(new org.springframework.core.io.InputStreamResource(inputStream));
            }
        } catch (Exception e) {
            log.error("Error streaming video: {}", objectName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

