package com.microservices.fileservice.util;

import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds MinIO/S3-safe object keys. Original display names stay in the database only.
 */
public final class ObjectNameSanitizer {

    private static final Pattern SAFE_EXTENSION = Pattern.compile("^[.a-z0-9]{1,12}$");
    private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\\\^*|\"<>#%\\n\\r\\t]");

    private ObjectNameSanitizer() {
    }

    public static String toStorageKey(String originalFilename) {
        return UUID.randomUUID() + extractSafeExtension(originalFilename);
    }

    static String extractSafeExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        String baseName = Paths.get(originalFilename).getFileName().toString();
        int dot = baseName.lastIndexOf('.');
        if (dot < 0 || dot == baseName.length() - 1) {
            return "";
        }
        String ext = baseName.substring(dot).toLowerCase(Locale.ROOT);
        ext = UNSAFE_CHARS.matcher(ext).replaceAll("");
        if (!SAFE_EXTENSION.matcher(ext).matches()) {
            return "";
        }
        return ext;
    }
}
