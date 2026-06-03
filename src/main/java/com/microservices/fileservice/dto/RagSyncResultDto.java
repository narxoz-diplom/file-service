package com.microservices.fileservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagSyncResultDto {

    private Long fileId;
    private Long courseId;
    private Integer total;
    private int synced;
    private int failed;
    private String message;
    private String error;
}
