package com.microservices.fileservice.config;

import com.microservices.fileservice.service.MinioService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MinioInitializer {

    private final MinioService minioService;

    @PostConstruct
    public void init() {
        minioService.initializeBucket();
    }
}



