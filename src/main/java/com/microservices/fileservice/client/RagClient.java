package com.microservices.fileservice.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
@Slf4j
public class RagClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagClient(
            WebClient.Builder webClientBuilder,
            @Value("${rag.service.url:http://localhost:8000}") String ragServiceUrl,
            @Value("${rag.service.api-key:}") String apiKey) {
        WebClient.Builder builder = webClientBuilder
                .baseUrl(ragServiceUrl);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("X-API-Key", apiKey);
        }

        this.webClient = builder.build();
    }

    /**
     * Ingest file into RAG vector store with given collection name.
     *
     * @param fileContent     raw file bytes
     * @param filename        original filename
     * @param collectionName unique collection name (e.g. course_1_file_2)
     * @param metadata       optional metadata (course_id, file_id, etc.)
     * @return true if ingest succeeded
     */
    public boolean ingestFile(byte[] fileContent, String filename, String collectionName, Map<String, Object> metadata) {
        try {
            String metadataJson = metadata != null && !metadata.isEmpty()
                    ? objectMapper.writeValueAsString(metadata)
                    : null;

            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
            bodyBuilder.part("file", new ByteArrayResource(fileContent)).filename(filename);
            bodyBuilder.part("collection_name", collectionName);
            if (metadataJson != null) {
                bodyBuilder.part("metadata", metadataJson);
            }

            var result = webClient.post()
                    .uri("/api/v1/ingest")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(bodyBuilder.build())
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            if (result != null && result.getStatusCode().is2xxSuccessful()) {
                log.info("RAG ingest successful for collection={}", collectionName);
                return true;
            }
        } catch (Exception e) {
            log.error("RAG ingest failed for collection={}: {}", collectionName, e.getMessage());
        }
        return false;
    }
}
