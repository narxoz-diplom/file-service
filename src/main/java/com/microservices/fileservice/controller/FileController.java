package com.microservices.fileservice.controller;

import com.microservices.fileservice.model.Course;
import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.model.Lesson;
import com.microservices.fileservice.service.CourseService;
import com.microservices.fileservice.service.FileService;
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

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {

    private final FileService fileService;
    private final CourseService courseService;

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

    @GetMapping
    public ResponseEntity<List<FileEntity>> getUserFiles(@AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canView(jwt)) {
            throw new AccessDeniedException("Access denied");
        }
        String userId = jwt.getSubject();
        List<FileEntity> files;
        
        // Админ может видеть все файлы, остальные - только свои
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
        
        // Клиент может видеть только свои файлы (если не админ)
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
            
            // Проверка доступа:
            // 1. Админ может скачивать все файлы
            // 2. Пользователь может скачивать свои файлы
            // 3. Если файл привязан к уроку, студент может скачивать файлы уроков курсов, на которые он записан
            boolean canDownload = false;
            
            if (RoleUtil.isAdmin(jwt)) {
                canDownload = true;
            } else if (file.getUserId().equals(userId)) {
                canDownload = true;
            } else if (file.getLesson() != null) {
                // Файл привязан к уроку - проверяем, записан ли студент на курс
                Lesson lesson = file.getLesson();
                Course course = lesson.getCourse();
                if (course != null && course.getEnrolledStudents() != null) {
                    canDownload = course.getEnrolledStudents().contains(userId);
                }
                // Также учитель курса может скачивать файлы
                if (!canDownload && course != null && course.getInstructorId() != null) {
                    canDownload = course.getInstructorId().equals(userId) || RoleUtil.isTeacher(jwt);
                }
            }
            
            if (!canDownload) {
                throw new AccessDeniedException("You don't have permission to download this file");
            }
            
            InputStream inputStream = fileService.downloadFile(id);
            
            // Определяем правильный Content-Type
            String contentType = file.getContentType();
            if (contentType == null || contentType.isEmpty()) {
                // Пытаемся определить по расширению файла
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
            
            // Правильная кодировка имени файла для Content-Disposition
            String encodedFileName = URLEncoder.encode(file.getOriginalFileName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, 
                    "attachment; filename=\"" + file.getOriginalFileName() + "\"; filename*=UTF-8''" + encodedFileName);
            headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.getFileSize()));
            headers.add(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
            headers.add(HttpHeaders.PRAGMA, "no-cache");
            headers.add(HttpHeaders.EXPIRES, "0");
            
            // Создаем InputStreamResource с указанием размера для правильной обработки
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        if (!RoleUtil.canDelete(jwt)) {
            throw new AccessDeniedException("Only ADMIN role can delete files");
        }
        try {
            String userId = jwt.getSubject();
            fileService.deleteFile(id, userId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

