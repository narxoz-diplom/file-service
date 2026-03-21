package com.microservices.fileservice.service;

import com.microservices.fileservice.client.RagIngestClient;
import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.repository.FileRepository;
import io.minio.errors.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileService {

    private final FileRepository fileRepository;
    private final MinioService minioService;
    private final RabbitTemplate rabbitTemplate;
    private final RagIngestClient ragIngestClient;

    @Transactional
    public FileEntity uploadFile(MultipartFile file, String userId) throws IOException {
        log.info("Uploading file: {} for user: {}", file.getOriginalFilename(), userId);
        
        String objectName;
        try {
            objectName = minioService.uploadFile(file);
        } catch (Exception e) {
            log.error("Error uploading file to MinIO", e);
            throw new IOException("Failed to upload file to storage", e);
        }
        
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(file.getOriginalFilename());
        fileEntity.setOriginalFileName(file.getOriginalFilename());
        fileEntity.setContentType(file.getContentType());
        fileEntity.setFileSize(file.getSize());
        fileEntity.setObjectName(objectName);
        fileEntity.setBucketName("files");
        fileEntity.setUserId(userId);
        fileEntity.setUploadedAt(LocalDateTime.now());
        fileEntity.setStatus(FileEntity.FileStatus.UPLOADED);
        
        FileEntity saved = fileRepository.save(fileEntity);
        
        sendFileProcessingMessage(saved.getId(), objectName);
        
        if (ragIngestClient.isEnabled()) {
            try {
                ragIngestClient.ingest(file, saved.getId(), null);
            } catch (Exception e) {
                log.warn("RAG ingest failed for file {}: {}", file.getOriginalFilename(), e.getMessage());
            }
        }
        
        sendNotificationMessage(userId, "File uploaded successfully: " + file.getOriginalFilename());
        
        return saved;
    }

    public FileEntity getFileById(Long id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found with id: " + id));
    }

    @Transactional
    public FileEntity uploadFileToLesson(MultipartFile file, String userId, Long lessonId) throws IOException {
        log.info("Uploading file: {} for lesson: {} by user: {}", file.getOriginalFilename(), lessonId, userId);
        
        String objectName;
        try {
            objectName = minioService.uploadFile(file);
        } catch (Exception e) {
            log.error("Error uploading file to MinIO", e);
            throw new IOException("Failed to upload file to storage", e);
        }
        
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(file.getOriginalFilename());
        fileEntity.setOriginalFileName(file.getOriginalFilename());
        fileEntity.setContentType(file.getContentType());
        fileEntity.setFileSize(file.getSize());
        fileEntity.setObjectName(objectName);
        fileEntity.setBucketName("files");
        fileEntity.setUserId(userId);
        fileEntity.setLessonId(lessonId);
        fileEntity.setUploadedAt(LocalDateTime.now());
        fileEntity.setStatus(FileEntity.FileStatus.UPLOADED);
        
        FileEntity saved = fileRepository.save(fileEntity);
        
        sendFileProcessingMessage(saved.getId(), objectName);
        
        if (ragIngestClient.isEnabled()) {
            try {
                ragIngestClient.ingest(file, saved.getId(), lessonId);
            } catch (Exception e) {
                log.warn("RAG ingest failed for file {} lesson {}: {}", file.getOriginalFilename(), lessonId, e.getMessage());
            }
        }
        
        sendNotificationMessage(userId, "File uploaded to lesson: " + file.getOriginalFilename());
        
        return saved;
    }

    @Transactional
    public FileEntity uploadFileToCourse(MultipartFile file, String userId, Long courseId) throws IOException, ServerException, InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Uploading file: {} for course: {} by user: {}", file.getOriginalFilename(), courseId, userId);
        String objectName = minioService.uploadFile(file);
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(file.getOriginalFilename());
        fileEntity.setOriginalFileName(file.getOriginalFilename());
        fileEntity.setContentType(file.getContentType());
        fileEntity.setFileSize(file.getSize());
        fileEntity.setObjectName(objectName);
        fileEntity.setBucketName("files");
        fileEntity.setUserId(userId);
        fileEntity.setCourseId(courseId);
        fileEntity.setUploadedAt(LocalDateTime.now());
        fileEntity.setStatus(FileEntity.FileStatus.UPLOADED);
        FileEntity saved = fileRepository.save(fileEntity);
        String collectionName = "course_" + courseId;
        fileEntity.setRagCollectionName(collectionName);
        saved = fileRepository.save(fileEntity);
        sendNotificationMessage(userId, "File uploaded to course: " + file.getOriginalFilename());
        if (ragIngestClient.isEnabled()) {
            try {
                ragIngestClient.ingestForCourse(file, collectionName, courseId, saved.getId());
            } catch (Exception e) {
                log.warn("RAG ingest failed for course file {}: {}", file.getOriginalFilename(), e.getMessage());
            }
        }
        return saved;
    }

    private static final int MAX_URL_FETCH_BYTES = 5_000_000;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    /**
     * Fetch a public http(s) page, extract text, store as .txt and ingest into the course RAG collection.
     */
    @Transactional
    public FileEntity ingestUrlToCourse(String urlString, String userId, Long courseId)
            throws IOException, ServerException, InsufficientDataException, ErrorResponseException,
            NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException,
            InternalException {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        URI uri;
        try {
            uri = URI.create(urlString.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "DiplomFileService/1.0")
                .GET()
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("URL fetch interrupted", e);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("URL returned status " + resp.statusCode());
        }
        byte[] raw = resp.body();
        if (raw == null || raw.length == 0) {
            throw new IOException("Empty response from URL");
        }
        if (raw.length > MAX_URL_FETCH_BYTES) {
            throw new IOException("Response too large (max " + MAX_URL_FETCH_BYTES + " bytes)");
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        String text;
        if (contentType.contains("html") || looksLikeHtml(raw)) {
            text = htmlToText(new String(raw, StandardCharsets.UTF_8));
        } else {
            text = new String(raw, StandardCharsets.UTF_8);
        }
        text = text.strip();
        if (text.length() > 2_000_000) {
            text = text.substring(0, 2_000_000);
        }
        if (text.isBlank()) {
            throw new IOException("No extractable text from URL");
        }
        byte[] outBytes = text.getBytes(StandardCharsets.UTF_8);
        String path = uri.getPath();
        String slug = (path != null && !path.isBlank() && !"/".equals(path))
                ? path.replaceAll(".*/", "").replaceAll("[^a-zA-Z0-9._-]+", "-")
                : "page";
        if (slug.isBlank() || slug.length() > 80) {
            slug = "page";
        }
        String filename = "url-" + slug + ".txt";
        String objectName = minioService.uploadBytes(outBytes, "text/plain; charset=utf-8", filename);

        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileName(filename);
        fileEntity.setOriginalFileName(filename);
        fileEntity.setContentType("text/plain; charset=utf-8");
        fileEntity.setFileSize((long) outBytes.length);
        fileEntity.setObjectName(objectName);
        fileEntity.setBucketName("files");
        fileEntity.setUserId(userId);
        fileEntity.setCourseId(courseId);
        fileEntity.setUploadedAt(LocalDateTime.now());
        fileEntity.setStatus(FileEntity.FileStatus.UPLOADED);
        FileEntity saved = fileRepository.save(fileEntity);
        String collectionName = "course_" + courseId;
        saved.setRagCollectionName(collectionName);
        saved = fileRepository.save(saved);

        sendNotificationMessage(userId, "URL ingested to course: " + urlString);
        if (ragIngestClient.isEnabled()) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("course_id", String.valueOf(courseId));
            meta.put("file_id", String.valueOf(saved.getId()));
            meta.put("source", "file-service");
            meta.put("source_type", "external_url");
            meta.put("source_url", urlString);
            try {
                ragIngestClient.ingestFromBytes(outBytes, filename, collectionName, meta);
            } catch (Exception e) {
                log.warn("RAG ingest failed for URL {}: {}", urlString, e.getMessage());
            }
        }
        return saved;
    }

    private static boolean looksLikeHtml(byte[] raw) {
        int n = Math.min(raw.length, 2000);
        String head = new String(raw, 0, n, StandardCharsets.UTF_8);
        return head.toLowerCase().contains("<html") || head.toLowerCase().contains("<!doctype html");
    }

    private static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        String s = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        return s.replaceAll("\\s+", " ").strip();
    }

    public List<FileEntity> getFilesByCourseId(Long courseId) {
        return fileRepository.findByCourseId(courseId);
    }

    public List<FileEntity> getFilesByUserId(String userId) {
        return fileRepository.findByUserId(userId);
    }

    public List<FileEntity> getFilesByLessonId(Long lessonId) {
        return fileRepository.findByLessonId(lessonId);
    }

    public List<FileEntity> getAllFiles() {
        return fileRepository.findAll();
    }

    public InputStream downloadFile(Long id) throws Exception {
        FileEntity fileEntity = getFileById(id);
        try {
            return minioService.downloadFile(fileEntity.getObjectName());
        } catch (Exception e) {
            log.error("Error downloading file from MinIO", e);
            throw new Exception("Failed to download file from storage", e);
        }
    }

    @Transactional
    public FileEntity updateFile(Long id, String userId, String newFileName) throws Exception {
        FileEntity fileEntity = getFileById(id);
        
        if (!fileEntity.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized to update this file");
        }
        
        if (newFileName != null && !newFileName.trim().isEmpty()) {
            fileEntity.setOriginalFileName(newFileName);
            fileEntity.setFileName(newFileName);
        }
        
        FileEntity updated = fileRepository.save(fileEntity);
        sendNotificationMessage(userId, "File updated: " + updated.getOriginalFileName());
        
        return updated;
    }

    @Transactional
    public void deleteFile(Long id, String userId) throws Exception {
        FileEntity fileEntity = getFileById(id);
        
        if (!fileEntity.getUserId().equals(userId)) {
            throw new RuntimeException("Unauthorized to delete this file");
        }
        
        try {
            minioService.deleteFile(fileEntity.getObjectName());
        } catch (Exception e) {
            log.error("Error deleting file from MinIO", e);
            throw new Exception("Failed to delete file from storage", e);
        }
        fileRepository.deleteById(id);
        
        sendNotificationMessage(userId, "File deleted: " + fileEntity.getOriginalFileName());
    }

    private void sendFileProcessingMessage(Long fileId, String objectName) {
        Map<String, Object> message = Map.of(
            "fileId", fileId,
            "objectName", objectName,
            "timestamp", LocalDateTime.now().toString()
        );
        rabbitTemplate.convertAndSend("file.processing.queue", message);
        log.info("File processing message sent for fileId: {}", fileId);
    }

    private void sendNotificationMessage(String userId, String message) {
        Map<String, Object> notification = Map.of(
            "userId", userId,
            "message", message,
            "type", "FILE_OPERATION",
            "timestamp", LocalDateTime.now().toString()
        );
        rabbitTemplate.convertAndSend("notification.queue", notification);
        log.info("Notification sent to user: {}", userId);
    }

    /**
     * Re-sync an existing file from MinIO to RAG (ChromaDB).
     * Use when a file was uploaded before RAG was configured, or ingest failed.
     */
    public boolean syncFileToRag(Long fileId) throws Exception {
        FileEntity fileEntity = getFileById(fileId);
        if (!ragIngestClient.isEnabled()) {
            log.warn("RAG service not configured, cannot sync file {}", fileId);
            return false;
        }
        if (fileEntity.getCourseId() == null) {
            log.warn("File {} is not associated with a course, will sync to default collection", fileId);
        }
        return doSyncFileToRag(fileEntity);
    }

    private boolean doSyncFileToRag(FileEntity fileEntity) throws Exception {
        byte[] content;
        try (InputStream is = minioService.downloadFile(fileEntity.getObjectName());
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            content = bytes.toByteArray();
        }
        String collectionName = fileEntity.getRagCollectionName();
        if (collectionName == null) {
            collectionName = fileEntity.getCourseId() != null ? "course_" + fileEntity.getCourseId()
                    : fileEntity.getLessonId() != null ? "lesson_" + fileEntity.getLessonId()
                    : "default";
        }
        Map<String, Object> meta = new java.util.HashMap<>();
        meta.put("file_id", String.valueOf(fileEntity.getId()));
        meta.put("source", "file-service-sync");
        if (fileEntity.getCourseId() != null) {
            meta.put("course_id", String.valueOf(fileEntity.getCourseId()));
        }
        if (fileEntity.getLessonId() != null) {
            meta.put("lesson_id", String.valueOf(fileEntity.getLessonId()));
        }
        return ragIngestClient.ingestFromBytes(
                content,
                fileEntity.getOriginalFileName(),
                collectionName,
                meta
        );
    }

    /**
     * Sync all files of a course from MinIO to RAG (ChromaDB).
     */
    public Map<String, Object> syncCourseFilesToRag(Long courseId) throws Exception {
        List<FileEntity> files = fileRepository.findByCourseId(courseId);
        int synced = 0;
        int failed = 0;
        for (FileEntity f : files) {
            try {
                if (doSyncFileToRag(f)) {
                    synced++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.warn("Failed to sync file {} to RAG: {}", f.getId(), e.getMessage());
                failed++;
            }
        }
        return Map.of(
                "courseId", courseId,
                "total", files.size(),
                "synced", synced,
                "failed", failed
        );
    }
}

