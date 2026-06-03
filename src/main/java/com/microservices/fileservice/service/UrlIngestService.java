package com.microservices.fileservice.service;

import com.microservices.fileservice.dto.FileUploadContext;
import com.microservices.fileservice.mapper.FileMapper;
import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlIngestService {

    private static final int MAX_URL_FETCH_BYTES = 5_000_000;
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private final MinioService minioService;
    private final FileRepository fileRepository;
    private final FileMapper fileMapper;
    private final FileNotificationService fileNotificationService;
    private final RagSyncService ragSyncService;

    @Transactional
    public FileEntity ingestUrlToCourse(String urlString, String userId, Long courseId) throws Exception {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL is required");
        }
        URI uri = parseUrl(urlString);
        UrlFetchResult fetchResult = fetchUrl(uri);
        String text = extractText(fetchResult);
        byte[] outBytes = text.getBytes(StandardCharsets.UTF_8);
        String filename = buildFilename(uri);

        String objectName = minioService.uploadBytes(outBytes, "text/plain; charset=utf-8", filename);
        String collectionName = "course_" + courseId;

        FileEntity saved = fileRepository.save(fileMapper.fromUploadContext(FileUploadContext.builder()
                .originalFileName(filename)
                .contentType("text/plain; charset=utf-8")
                .fileSize(outBytes.length)
                .objectName(objectName)
                .userId(userId)
                .isPublic(false)
                .courseId(courseId)
                .ragCollectionName(collectionName)
                .build()));

        fileNotificationService.sendUserNotification(userId, "URL ingested to course: " + urlString);

        Map<String, Object> meta = new HashMap<>();
        meta.put("course_id", String.valueOf(courseId));
        meta.put("file_id", String.valueOf(saved.getId()));
        meta.put("source", "file-service");
        meta.put("source_type", "external_url");
        meta.put("source_url", urlString);
        ragSyncService.ingestUrlBytesSafely(outBytes, filename, collectionName, meta);

        return saved;
    }

    private static URI parseUrl(String urlString) {
        try {
            URI uri = URI.create(urlString.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Only http and https URLs are allowed");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL", e);
        }
    }

    private record UrlFetchResult(byte[] body, String contentType) {
    }

    private UrlFetchResult fetchUrl(URI uri) throws IOException {
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
        return new UrlFetchResult(raw, contentType);
    }

    private static String extractText(UrlFetchResult fetchResult) throws IOException {
        byte[] raw = fetchResult.body();
        String text;
        if (fetchResult.contentType().contains("html") || looksLikeHtml(raw)) {
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
        return text;
    }

    private static String buildFilename(URI uri) {
        String path = uri.getPath();
        String slug = (path != null && !path.isBlank() && !"/".equals(path))
                ? path.replaceAll(".*/", "").replaceAll("[^a-zA-Z0-9._-]+", "-")
                : "page";
        if (slug.isBlank() || slug.length() > 80) {
            slug = "page";
        }
        return "url-" + slug + ".txt";
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
}
