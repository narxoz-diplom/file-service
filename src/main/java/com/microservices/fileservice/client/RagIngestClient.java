package com.microservices.fileservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class RagIngestClient {

    private final RestTemplate restTemplate;
    private final String ragServiceUrl;
    private final String ragApiKey;

    private static final String INGEST_PATH = "/api/v1/ingest";

    public RagIngestClient(
            RestTemplate restTemplate,
            @Value("${rag.service.url:}") String ragServiceUrl,
            @Value("${rag.service.api-key:}") String ragApiKey) {
        this.restTemplate = restTemplate;
        this.ragServiceUrl = ragServiceUrl == null ? "" : ragServiceUrl.trim();
        this.ragApiKey = ragApiKey == null ? "" : ragApiKey.trim();
    }

    private void applyRagAuth(HttpHeaders headers) {
        if (!ragApiKey.isEmpty()) {
            headers.set("X-API-Key", ragApiKey);
        }
    }

    public boolean isEnabled() {
        return !ragServiceUrl.isEmpty();
    }

    public boolean ingest(MultipartFile file, Long fileId, Long lessonId) {
        String collectionName = lessonId != null ? "lesson_" + lessonId : "default";
        Map<String, Object> meta = new HashMap<>();
        if (fileId != null) meta.put("file_id", String.valueOf(fileId));
        if (lessonId != null) meta.put("lesson_id", String.valueOf(lessonId));
        meta.put("source", "file-service");
        return ingest(file, collectionName, meta);
    }

    public boolean ingestForCourse(MultipartFile file, String collectionName, Long courseId, Long fileId) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("course_id", String.valueOf(courseId));
        meta.put("file_id", String.valueOf(fileId));
        meta.put("source", "file-service");
        return ingest(file, collectionName, meta);
    }

    public boolean ingestFromBytes(byte[] content, String filename, String collectionName, Map<String, Object> meta) {
        if (!isEnabled()) {
            log.debug("RAG service URL not set, skipping ingest");
            return false;
        }
        if (content == null || content.length == 0) {
            log.warn("Empty content, skipping RAG ingest: {}", filename);
            return false;
        }
        if (filename == null) filename = "file";
        String metadataJson = mapToJson(meta);

        String finalFilename = filename;
        ByteArrayResource fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return finalFilename;
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        applyRagAuth(headers);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("metadata", metadataJson);
        body.add("collection_name", collectionName);

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        String url = ragServiceUrl + INGEST_PATH;

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("RAG ingest (sync) successful for file: {}, collection: {}", filename, collectionName);
                return true;
            }
            log.warn("RAG ingest (sync) returned {} for file: {}", response.getStatusCode(), filename);
            return false;
        } catch (Exception e) {
            log.warn("RAG ingest (sync) failed for file: {}: {}", filename, e.getMessage());
            return false;
        }
    }

    private boolean ingest(MultipartFile file, String collectionName, Map<String, Object> meta) {
        if (!isEnabled()) {
            log.debug("RAG service URL not set, skipping ingest");
            return false;
        }
        if (file == null || file.isEmpty()) {
            return false;
        }
        String filename = file.getOriginalFilename();
        if (filename == null) filename = "file";
        String metadataJson = mapToJson(meta);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            log.warn("Failed to read file bytes for RAG ingest: {}", e.getMessage());
            return false;
        }
        if (bytes.length == 0) {
            log.warn("File is empty, skipping RAG ingest: {}", filename);
            return false;
        }

        String finalFilename = filename;
        ByteArrayResource fileResource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return finalFilename;
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        applyRagAuth(headers);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("metadata", metadataJson);
        body.add("collection_name", collectionName);

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        String url = ragServiceUrl + INGEST_PATH;

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("RAG ingest successful for file: {}, collection: {}", filename, collectionName);
                return true;
            }
            log.warn("RAG ingest returned {} for file: {}", response.getStatusCode(), filename);
            return false;
        } catch (Exception e) {
            log.warn("RAG ingest failed for file: {}: {}", filename, e.getMessage());
            return false;
        }
    }

    private static String mapToJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(",");
            sb.append("\"").append(k).append("\":");
            if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append("\"").append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            }
        });
        sb.append("}");
        return sb.toString();
    }

}
