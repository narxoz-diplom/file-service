package com.microservices.fileservice.exception;

public class FileNotFoundException extends RuntimeException {

    public FileNotFoundException(Long id) {
        super("File not found with id: " + id);
    }
}
