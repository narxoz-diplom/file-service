package com.microservices.fileservice.controller;

import com.microservices.fileservice.dto.FileResponseDto;
import com.microservices.fileservice.dto.IngestUrlRequest;
import com.microservices.fileservice.dto.FileRagSyncResultDto;
import com.microservices.fileservice.dto.RagSyncResultDto;
import com.microservices.fileservice.dto.UpdateFileRequest;
import com.microservices.fileservice.dto.VideoUploadResponseDto;
import com.microservices.fileservice.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;

    @PostMapping("/upload")
    public ResponseEntity<FileResponseDto> uploadFile(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileService.uploadFile(file, jwt));
    }

    @PostMapping("/upload-news-image")
    public ResponseEntity<FileResponseDto> uploadNewsImage(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileService.uploadPublicNewsImage(file, jwt));
    }

    @PostMapping("/course/{courseId}/ingest-url")
    public ResponseEntity<FileResponseDto> ingestUrlToCourse(
            @PathVariable Long courseId,
            @RequestBody IngestUrlRequest body,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(fileService.ingestUrlToCourse(body.getUrl(), jwt, courseId));
    }

    @PostMapping("/upload-to-course")
    public ResponseEntity<FileResponseDto> uploadFileToCourse(
            @RequestParam("file") MultipartFile file,
            @RequestParam("courseId") Long courseId,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileService.uploadFileToCourse(file, jwt, courseId));
    }

    @GetMapping("/course/{courseId}")
    public ResponseEntity<List<FileResponseDto>> getFilesByCourseId(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(fileService.getFilesByCourseId(jwt, courseId));
    }

    @PostMapping("/course/{courseId}/sync-to-rag")
    public ResponseEntity<RagSyncResultDto> syncCourseFilesToRag(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        return ResponseEntity.ok(fileService.syncCourseFilesToRag(jwt, courseId));
    }

    @PostMapping("/upload-to-lesson")
    public ResponseEntity<FileResponseDto> uploadFileToLesson(
            @RequestParam("file") MultipartFile file,
            @RequestParam("lessonId") Long lessonId,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileService.uploadFileToLesson(file, jwt, lessonId));
    }

    @GetMapping
    public ResponseEntity<List<FileResponseDto>> getUserFiles(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(fileService.getUserFiles(jwt));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FileResponseDto> getFile(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(fileService.getFile(id, jwt));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> downloadFile(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        return fileService.downloadFile(id, jwt);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        return fileService.streamFileContent(id, jwt);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FileResponseDto> updateFile(
            @PathVariable Long id,
            @RequestBody UpdateFileRequest updateRequest,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(fileService.updateFile(id, jwt, updateRequest));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) {
        fileService.deleteFile(id, jwt);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/sync-to-rag")
    public ResponseEntity<FileRagSyncResultDto> syncFileToRag(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        return ResponseEntity.ok(fileService.syncFileToRag(id, jwt));
    }

    @GetMapping("/lesson/{lessonId}")
    public ResponseEntity<List<FileResponseDto>> getFilesByLessonId(
            @PathVariable Long lessonId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(fileService.getFilesByLessonId(jwt, lessonId));
    }

    @PostMapping("/upload-video")
    public ResponseEntity<VideoUploadResponseDto> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "lessonId", required = false) Long lessonId,
            @AuthenticationPrincipal Jwt jwt) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(fileService.uploadVideo(file, lessonId, jwt));
    }

    @GetMapping("/videos/{objectName}/stream")
    public ResponseEntity<InputStreamResource> streamVideo(
            @PathVariable String objectName,
            @RequestHeader(value = "Range", required = false) String rangeHeader) throws Exception {
        return fileService.streamVideo(objectName, rangeHeader);
    }
}
