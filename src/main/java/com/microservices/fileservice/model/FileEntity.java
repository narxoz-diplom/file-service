package com.microservices.fileservice.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "files")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String originalFileName;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false)
    private String objectName;

    @Column(nullable = false)
    private String bucketName;

    @Column(nullable = false)
    private String userId;

    @Column
    private Long lessonId;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    @Column
    private LocalDateTime processedAt;

    @Enumerated(EnumType.STRING)
    private FileStatus status = FileStatus.UPLOADED;

    public enum FileStatus {
        UPLOADED, PROCESSING, PROCESSED, FAILED
    }
}



