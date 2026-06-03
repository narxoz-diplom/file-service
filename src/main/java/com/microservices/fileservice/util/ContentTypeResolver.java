package com.microservices.fileservice.util;

import com.microservices.fileservice.model.FileEntity;

public final class ContentTypeResolver {

    private ContentTypeResolver() {
    }

    public static String resolveForFile(FileEntity file) {
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            return contentType;
        }
        return resolveFromFileName(file.getOriginalFileName(), "application/octet-stream");
    }

    public static String resolveForVideo(String objectName, String statContentType) {
        if (statContentType != null && !statContentType.isBlank()) {
            return statContentType;
        }
        return resolveFromFileName(objectName, "video/mp4");
    }

    private static String resolveFromFileName(String fileName, String defaultType) {
        if (fileName == null) {
            return defaultType;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".doc")) {
            return "application/msword";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (lower.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        }
        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if (lower.endsWith(".zip")) {
            return "application/zip";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".ogg")) {
            return "video/ogg";
        }
        return defaultType;
    }
}
