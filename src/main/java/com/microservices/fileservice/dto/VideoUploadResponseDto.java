package com.microservices.fileservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VideoUploadResponseDto {

    private String objectName;
    private String videoUrl;
    private long fileSize;
    private String contentType;
    private String originalFileName;
}
