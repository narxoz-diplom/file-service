package com.microservices.fileservice.mapper;

import com.microservices.fileservice.dto.FileResponseDto;
import com.microservices.fileservice.dto.FileUploadContext;
import com.microservices.fileservice.dto.VideoUploadResponseDto;
import com.microservices.fileservice.model.FileEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;

@Mapper(componentModel = "spring", imports = {LocalDateTime.class, FileEntity.FileStatus.class})
public interface FileMapper {

    FileResponseDto toResponse(FileEntity entity);

    List<FileResponseDto> toResponseList(List<FileEntity> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "processedAt", ignore = true)
    @Mapping(target = "bucketName", constant = "files")
    @Mapping(target = "fileName", source = "originalFileName")
    @Mapping(target = "uploadedAt", expression = "java(LocalDateTime.now())")
    @Mapping(target = "status", expression = "java(FileStatus.UPLOADED)")
    FileEntity fromUploadContext(FileUploadContext context);

    default VideoUploadResponseDto toVideoUploadResponse(String objectName, MultipartFile file) {
        return VideoUploadResponseDto.builder()
                .objectName(objectName)
                .videoUrl("/api/files/videos/" + objectName + "/stream")
                .fileSize(file.getSize())
                .contentType(file.getContentType())
                .originalFileName(file.getOriginalFilename())
                .build();
    }
}
