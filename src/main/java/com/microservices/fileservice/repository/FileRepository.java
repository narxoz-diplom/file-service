package com.microservices.fileservice.repository;

import com.microservices.fileservice.model.FileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileRepository extends JpaRepository<FileEntity, Long> {
    List<FileEntity> findByUserId(String userId);
    List<FileEntity> findByLessonId(Long lessonId);
    Optional<FileEntity> findByObjectName(String objectName);
    void deleteByObjectName(String objectName);
}



