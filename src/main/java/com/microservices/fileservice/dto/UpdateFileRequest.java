package com.microservices.fileservice.dto;

import lombok.Data;

@Data
public class UpdateFileRequest {
    private String originalFileName;
}
