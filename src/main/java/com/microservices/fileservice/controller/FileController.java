package com.microservices.fileservice.controller;

import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.service.FileService;
import com.microservices.fileservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
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
import java.util.List;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

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
            
            // Клиент может скачивать только свои файлы (если не админ)
            if (!RoleUtil.isAdmin(jwt) && !file.getUserId().equals(jwt.getSubject())) {
                throw new AccessDeniedException("You can only download your own files");
            }
            
            InputStream inputStream = fileService.downloadFile(id);
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename=\"" + file.getOriginalFileName() + "\"")
                    .contentType(MediaType.parseMediaType(file.getContentType()))
                    .body(new InputStreamResource(inputStream));
        } catch (Exception e) {
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

