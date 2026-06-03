package com.microservices.fileservice.service;

import com.microservices.fileservice.model.FileEntity;
import com.microservices.fileservice.util.RoleUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FileAuthorizationService {

    public void requireCanUpload(Jwt jwt) {
        if (!RoleUtil.canUpload(jwt)) {
            throw new AccessDeniedException("Only ADMIN and TEACHER roles can upload files");
        }
    }

    public void requireAdmin(Jwt jwt) {
        if (!RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Only ADMIN can perform this action");
        }
    }

    public void requireCanView(Jwt jwt) {
        if (!RoleUtil.canView(jwt)) {
            throw new AccessDeniedException("Access denied");
        }
    }

    public void assertCanViewFile(Jwt jwt, FileEntity file) {
        requireCanView(jwt);
        if (canAccessFile(jwt, file)) {
            return;
        }
        throw new AccessDeniedException("You can only view your own files");
    }

    public void assertCanDownload(Jwt jwt, FileEntity file) {
        requireCanView(jwt);
        if (canAccessFile(jwt, file)) {
            return;
        }
        throw new AccessDeniedException("You don't have permission to download this file");
    }

    public void assertCanModifyOwnedFile(Jwt jwt, FileEntity file) {
        requireCanUpload(jwt);
        if (!file.getUserId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("Unauthorized to modify this file");
        }
    }

    public void assertCanSyncFile(Jwt jwt, FileEntity file) {
        requireCanUpload(jwt);
        if (!RoleUtil.isAdmin(jwt) && !file.getUserId().equals(jwt.getSubject())) {
            throw new AccessDeniedException("You can only sync your own files");
        }
    }

    public boolean canAccessInlineContent(Jwt jwt, FileEntity file) {
        if (file.isPublic()) {
            return true;
        }
        return jwt != null && RoleUtil.canView(jwt) && canAccessFile(jwt, file);
    }

    /**
     * Personal uploads: owner or admin. Course/lesson materials: any user who can view the platform (teacher, student).
     */
    private boolean canAccessFile(Jwt jwt, FileEntity file) {
        if (RoleUtil.isAdmin(jwt) || file.getUserId().equals(jwt.getSubject())) {
            return true;
        }
        return isCourseMaterial(file);
    }

    private static boolean isCourseMaterial(FileEntity file) {
        return file.getLessonId() != null || file.getCourseId() != null;
    }
}
