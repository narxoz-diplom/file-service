package com.microservices.fileservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileRagSyncResultDto {

    private Long fileId;
    private boolean synced;
    private String message;
    private String error;
}
