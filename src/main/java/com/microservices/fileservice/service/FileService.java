package com.microservices.fileservice.service;

import com.microservices.fileservice.dto.FileRagSyncResultDto;
import com.microservices.fileservice.dto.FileResponseDto;
import com.microservices.fileservice.dto.RagSyncResultDto;
import com.microservices.fileservice.dto.UpdateFileRequest;
import com.microservices.fileservice.dto.VideoUploadResponseDto;
import com.microservices.fileservice.exception.FileNotFoundException;
import com.microservices.fileservice.exception.FileStorageException;
import com.microservices.fileservice.mapper.FileMapper;
import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.repository.FileRepository;
import com.microservices.fileservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    private final FileRepository fileRepository;
    private final MinioService minioService;
    private final FileMapper fileMapper;
    private final FileUploadService fileUploadService;
    private final FileAuthorizationService fileAuthorizationService;
    private final FileContentService fileContentService;
    private final FileNotificationService fileNotificationService;
    private final UrlIngestService urlIngestService;
    private final RagSyncService ragSyncService;
    private final VideoStreamingService videoStreamingService;

    public FileResponseDto uploadFile(MultipartFile file, Jwt jwt) throws Exception {
        fileAuthorizationService.requireCanUpload(jwt);
        return fileMapper.toResponse(fileUploadService.uploadFile(file, jwt.getSubject()));
    }

    public FileResponseDto uploadPublicNewsImage(MultipartFile file, Jwt jwt) throws Exception {
        fileAuthorizationService.requireAdmin(jwt);
        return fileMapper.toResponse(fileUploadService.uploadPublicNewsImage(file, jwt.getSubject()));
    }

    public FileResponseDto uploadAvatar(MultipartFile file, Jwt jwt) throws Exception {
        fileAuthorizationService.requireCanView(jwt);
        return fileMapper.toResponse(fileUploadService.uploadAvatar(file, jwt.getSubject()));
    }

    public FileResponseDto ingestUrlToCourse(String url, Jwt jwt, Long courseId) throws Exception {
        fileAuthorizationService.requireCanUpload(jwt);
        return fileMapper.toResponse(urlIngestService.ingestUrlToCourse(url, jwt.getSubject(), courseId));
    }

    public FileResponseDto uploadFileToCourse(MultipartFile file, Jwt jwt, Long courseId) throws Exception {
        fileAuthorizationService.requireCanUpload(jwt);
        return fileMapper.toResponse(fileUploadService.uploadFileToCourse(file, jwt.getSubject(), courseId));
    }

    public FileResponseDto uploadFileToLesson(MultipartFile file, Jwt jwt, Long lessonId) throws Exception {
        fileAuthorizationService.requireCanUpload(jwt);
        return fileMapper.toResponse(fileUploadService.uploadFileToLesson(file, jwt.getSubject(), lessonId));
    }

    public List<FileResponseDto> getFilesByCourseId(Jwt jwt, Long courseId) {
        fileAuthorizationService.requireCanView(jwt);
        return fileMapper.toResponseList(fileRepository.findByCourseId(courseId));
    }

    public RagSyncResultDto syncCourseFilesToRag(Jwt jwt, Long courseId) throws Exception {
        fileAuthorizationService.requireCanUpload(jwt);
        return ragSyncService.syncCourseFiles(courseId);
    }

    public List<FileResponseDto> getUserFiles(Jwt jwt) {
        fileAuthorizationService.requireCanView(jwt);
        List<FileEntity> files = RoleUtil.isAdmin(jwt)
                ? fileRepository.findAll()
                : fileRepository.findByUserId(jwt.getSubject());
        return fileMapper.toResponseList(files);
    }

    public FileResponseDto getFile(Long id, Jwt jwt) {
        FileEntity file = getEntityById(id);
        fileAuthorizationService.assertCanViewFile(jwt, file);
        return fileMapper.toResponse(file);
    }

    public ResponseEntity<InputStreamResource> downloadFile(Long id, Jwt jwt) {
        FileEntity file = getEntityById(id);
        fileAuthorizationService.assertCanDownload(jwt, file);
        InputStream stream = fileContentService.openDownloadStream(file);
        return fileContentService.buildAttachmentResponse(file, stream);
    }

    public ResponseEntity<InputStreamResource> streamFileContent(Long id, Jwt jwt) {
        FileEntity file = getEntityById(id);
        if (!fileAuthorizationService.canAccessInlineContent(jwt, file)) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
        InputStream stream = fileContentService.openDownloadStream(file);
        return fileContentService.buildInlineContentResponse(file, stream);
    }

    @Transactional
    public FileResponseDto updateFile(Long id, Jwt jwt, UpdateFileRequest request) {
        fileAuthorizationService.requireCanUpload(jwt);
        FileEntity file = getEntityById(id);
        fileAuthorizationService.assertCanModifyOwnedFile(jwt, file);

        String newFileName = request != null ? request.getOriginalFileName() : null;
        if (newFileName != null && !newFileName.trim().isEmpty()) {
            file.setOriginalFileName(newFileName);
            file.setFileName(newFileName);
        }

        FileEntity updated = fileRepository.save(file);
        fileNotificationService.sendUserNotification(jwt.getSubject(), "File updated: " + updated.getOriginalFileName());
        return fileMapper.toResponse(updated);
    }

    @Transactional
    public void deleteFile(Long id, Jwt jwt) {
        fileAuthorizationService.requireCanUpload(jwt);
        FileEntity file = getEntityById(id);
        fileAuthorizationService.assertCanModifyOwnedFile(jwt, file);

        try {
            minioService.deleteFile(file.getObjectName());
        } catch (Exception e) {
            log.error("Error deleting file from MinIO", e);
            throw new FileStorageException("Failed to delete file from storage", e);
        }
        fileRepository.deleteById(id);
        fileNotificationService.sendUserNotification(jwt.getSubject(), "File deleted: " + file.getOriginalFileName());
    }

    public FileRagSyncResultDto syncFileToRag(Long id, Jwt jwt) throws Exception {
        FileEntity file = getEntityById(id);
        fileAuthorizationService.assertCanSyncFile(jwt, file);
        return ragSyncService.syncFile(id);
    }

    public List<FileResponseDto> getFilesByLessonId(Jwt jwt, Long lessonId) {
        fileAuthorizationService.requireCanView(jwt);
        return fileMapper.toResponseList(fileRepository.findByLessonId(lessonId));
    }

    public VideoUploadResponseDto uploadVideo(MultipartFile file, Long lessonId, Jwt jwt) throws Exception {
        fileAuthorizationService.requireCanUpload(jwt);
        return videoStreamingService.uploadVideo(file, lessonId, jwt.getSubject());
    }

    public ResponseEntity<InputStreamResource> streamVideo(String objectName, String rangeHeader, Jwt jwt) throws Exception {
        fileAuthorizationService.requireCanView(jwt);
        String decodedObjectName = decodeObjectName(objectName);
        var fileOpt = fileRepository.findByObjectName(decodedObjectName)
                .or(() -> fileRepository.findByObjectName(objectName));
        if (fileOpt.isPresent()) {
            FileEntity file = fileOpt.get();
            if (!fileAuthorizationService.canAccessInlineContent(jwt, file)) {
                throw new org.springframework.security.access.AccessDeniedException("Access denied");
            }
        } else if (!videoStreamingService.storageObjectExists(decodedObjectName)) {
            throw new FileNotFoundException("Video not found: " + decodedObjectName);
        } else {
            log.warn("Streaming video {} from storage without files metadata row", decodedObjectName);
        }
        return videoStreamingService.streamVideo(decodedObjectName, rangeHeader);
    }

    private static String decodeObjectName(String objectName) {
        try {
            String decoded = java.net.URLDecoder.decode(objectName, java.nio.charset.StandardCharsets.UTF_8);
            if (!decoded.equals(objectName) && !decoded.contains("%")) {
                return decoded;
            }
        } catch (Exception ignored) {
            // use original
        }
        return objectName;
    }

    private FileEntity getEntityById(Long id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new FileNotFoundException(id));
    }
}
